package dev.deadcraft.client.fx;

import dev.deadcraft.client.fx.Functions.Emitter;
import dev.deadcraft.client.fx.Functions.Force;
import dev.deadcraft.client.fx.Functions.Initializer;
import dev.deadcraft.client.fx.Functions.Operator;
import dev.deadcraft.client.fx.Functions.PreEmission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * One running particle system and its children: Deadlock's effect played in the effect's own frame (Source
 * units, Z up; {@link Effects} places it in the world). Built per play from the pack's definition, since
 * emitters and some operators keep state. The timing (sub-steps, children's delays, the end cap) follows
 * ValveResourceFormat's simulation (MIT licence).
 */
public final class FxSystem {
	public static final int MAX_CONTROL_POINTS = 64;

	final Definition def;
	final FxSystem parent;
	final List<Child> children = new ArrayList<>();
	final List<Particle> particles = new ArrayList<>();
	final List<Emitter> emitters = new ArrayList<>();
	final List<Initializer> initializers = new ArrayList<>();
	final List<Operator> operators = new ArrayList<>();
	final List<PreEmission> preEmission = new ArrayList<>();
	final List<Force> forces = new ArrayList<>();
	final List<Renderers.Renderer> renderers = new ArrayList<>();
	private final ControlPoint[] cps;
	private final float[] camera;
	private final SplittableRandom random;
	private final int seed;

	float age, frameTime = 0.1f, previousFrameTime;
	boolean inEndCap;
	private float endCapStart;
	private boolean started;
	private int emitted;
	private final Particle constants = new Particle();

	record Child(FxSystem system, float delay, boolean endCap) {
	}

	/** A root system (its control points are its own; set them before the first update). */
	public FxSystem(FxPack pack, String path, long seed) {
		this(pack, pack.definition(path), null, newCps(), new float[3], seed, 0);
	}

	private FxSystem(FxPack pack, Definition def, FxSystem parent, ControlPoint[] cps, float[] camera, long seed, int depth) {
		this.def = def;
		this.parent = parent;
		this.cps = cps;
		this.camera = camera;
		this.random = new SplittableRandom(seed);
		this.seed = (int) seed;
		Map<String, Object> m = def.data;
		Compiler c = new Compiler(def.path);
		if (m.get("m_ConstantColor") instanceof List<?> col && col.size() >= 4) {
			constants.color[0] = Kv3.num(col.get(0)) / 255f;
			constants.color[1] = Kv3.num(col.get(1)) / 255f;
			constants.color[2] = Kv3.num(col.get(2)) / 255f;
			constants.alpha = Kv3.num(col.get(3)) / 255f;
		}
		constants.radius = Kv3.f(m, "m_flConstantRadius", 5);
		constants.lifetime = Kv3.f(m, "m_flConstantLifespan", 1);
		constants.roll = (float) Math.toRadians(Kv3.f(m, "m_flConstantRotation", 0));
		constants.rollSpeed = (float) Math.toRadians(Kv3.f(m, "m_flConstantRotationSpeed", 0));
		float[] n = Kv3.vec(m, "m_ConstantNormal", 0, 0, 1);
		constants.setVector(Particle.NORMAL, n[0], n[1], n[2]);
		constants.sequence = Kv3.i(m, "m_nConstantSequenceNumber", 0);
		for (Map<String, Object> f : Functions.functions(m, "m_Emitters")) add(f, c, "emitter");
		for (Map<String, Object> f : Functions.functions(m, "m_Initializers")) add(f, c, "initializer");
		for (Map<String, Object> f : Functions.functions(m, "m_Operators")) add(f, c, "operator");
		for (Map<String, Object> f : Functions.functions(m, "m_PreEmissionOperators")) add(f, c, "pre-emission");
		for (Map<String, Object> f : Functions.functions(m, "m_ForceGenerators")) add(f, c, "force");
		for (Map<String, Object> f : Functions.functions(m, "m_Renderers")) {
			if (Kv3.b(f, "m_bDisableOperator", false)) continue;
			Renderers.Renderer r = Renderers.create(f, c);
			if (r != null) renderers.add(r);
		}
		pack.reportUnsupported(def.path, c.unsupported);
		if (depth < 8) {
			for (Map<String, Object> ch : Functions.functions(m, "m_Children")) {
				if (Kv3.b(ch, "m_bDisableChild", false)) continue;
				String ref = Kv3.s(ch, "m_ChildRef", null);
				if (ref == null) continue;
				Definition childDef = pack.definition(ref);
				if (childDef == null) continue;
				FxSystem child = new FxSystem(pack, childDef, this, cps, camera, seed * 31 + children.size() + 1, depth + 1);
				children.add(new Child(child, Kv3.f(ch, "m_flDelay", 0), Kv3.b(ch, "m_bEndCap", false)));
			}
		}
	}

	private static ControlPoint[] newCps() {
		ControlPoint[] cps = new ControlPoint[MAX_CONTROL_POINTS];
		for (int i = 0; i < cps.length; i++) cps[i] = new ControlPoint();
		return cps;
	}

	private void add(Map<String, Object> f, Compiler c, String kind) {
		if (Kv3.b(f, "m_bDisableOperator", false)) return;
		String cls = Kv3.s(f, "_class", "?");
		Object fn = switch (kind) {
			case "emitter" -> Functions.emitter(cls, f, c);
			case "initializer" -> Functions.initializer(cls, f, c);
			case "operator" -> Functions.operator(cls, f, c);
			case "pre-emission" -> Functions.preEmission(cls, f, c);
			default -> Functions.force(cls, f, c);
		};
		if (fn instanceof Emitter e) emitters.add(e);
		else if (fn instanceof Initializer i) initializers.add(i);
		else if (fn instanceof Operator o) operators.add(o);
		else if (fn instanceof PreEmission p) preEmission.add(p);
		else if (fn instanceof Force fo) forces.add(fo);
		else if (!Functions.ignored(cls)) c.unsupported(kind + " " + cls);
	}

	// ---- control points and the camera -----------------------------------------------------------

	public ControlPoint cp(int i) {
		return cps[Math.max(0, Math.min(MAX_CONTROL_POINTS - 1, i))];
	}

	boolean cpIsSet(int i) {
		return i >= 0 && i < MAX_CONTROL_POINTS && cps[i].set;
	}

	void setCpValue(int i, float[] v) {
		cp(i).position(v[0], v[1], v[2]);
	}

	/** Where the camera is, in the effect's frame (read by camera-distance inputs and to face sprites). */
	public void camera(float x, float y, float z) {
		camera[0] = x;
		camera[1] = y;
		camera[2] = z;
	}

	float[] camera() {
		return camera;
	}

	// ---- randomness ------------------------------------------------------------------------------

	float rng() {
		return (float) random.nextDouble();
	}

	/** A random number fixed for one particle and one input. */
	float random(int particleId, int ordinal) {
		int h = (particleId * 0x27d4eb2d) ^ (ordinal * 0x165667b1) ^ seed;
		h ^= h >>> 15;
		h *= 0x2c1b3c6d;
		h ^= h >>> 12;
		h *= 0x297a2d39;
		h ^= h >>> 15;
		return (h >>> 8) / (float) (1 << 24);
	}

	/** A random direction (written to out) and a radius fraction for a uniform ball. */
	float unitBall(float[] out) {
		float cosPolar = rng() * 2 - 1, azimuth = rng() * (float) (2 * Math.PI);
		float fraction = (float) Math.cbrt(rng());
		float sinPolar = (float) Math.sqrt(Math.max(0, 1 - cosPolar * cosPolar));
		out[0] = sinPolar * (float) Math.cos(azimuth) * fraction;
		out[1] = sinPolar * (float) Math.sin(azimuth) * fraction;
		out[2] = cosPolar * fraction;
		return fraction;
	}

	float rngExp(float exp, float min, float max) {
		return min + (max - min) * (float) Math.pow(rng(), exp);
	}

	float endCapAge() {
		return inEndCap ? age - endCapStart : 0;
	}

	// ---- running ---------------------------------------------------------------------------------

	private void start() {
		started = true;
		for (Initializer i : initializers) i.reset();
		for (Operator o : operators) o.reset();
		for (Emitter e : emitters) e.start(this);
	}

	/** Advances the system (and its children) by a frame. */
	public void update(float dt) {
		if (parent == null && dt > 0) {
			// Control point velocities, from how far each moved this frame (read by speed inputs).
			for (ControlPoint p : cps) {
				if (!p.set) continue;
				for (int k = 0; k < 3; k++) {
					p.vel[k] = started ? (p.pos[k] - p.prevPos[k]) / dt : 0;
					p.prevPos[k] = p.pos[k];
				}
			}
		}
		if (!started) start();
		float max = def.maxTimeStep;
		float remaining = Math.min(dt, max * 10);
		while (remaining > 1e-6f) {
			float step = Math.min(remaining, max);
			remaining -= step;
			simulate(step);
		}
		for (Child ch : children) {
			if (ch.endCap ? !inEndCap : age < ch.delay) continue;
			ch.system.update(dt);
		}
	}

	private void simulate(float dt) {
		frameTime = dt;
		age += dt;
		for (Particle p : particles) p.age = age - p.creationTime;
		for (PreEmission p : preEmission) {
			if (p.strength(this) > 0) p.operate(this, dt);
		}
		for (Emitter e : emitters) {
			float s = e.strength(this);
			if (s > 0) e.emit(this, dt, s);
		}
		for (Operator o : operators) {
			float s = o.strength(this);
			if (s > 0) o.operate(this, dt, s);
		}
		particles.removeIf(p -> p.killed);
		for (int i = 0; i < particles.size(); i++) particles.get(i).index = i;
		previousFrameTime = dt;
	}

	/** Spawns one particle that has already lived {@code ageAtSpawn} seconds. */
	void emit(float ageAtSpawn) {
		if (particles.size() >= def.maxParticles) return;
		Particle p = new Particle();
		p.copyFrom(constants);
		p.uniqueId = emitted++;
		p.id = p.uniqueId + seed;
		p.index = particles.size();
		float[] at = cp(0).pos;
		p.pos[0] = at[0];
		p.pos[1] = at[1];
		p.pos[2] = at[2];
		p.creationTime = age - ageAtSpawn;
		p.age = ageAtSpawn;
		particles.add(p);
		for (Initializer i : initializers) {
			if (i.runsNow(this)) i.init(p, this);
		}
		for (int k = 0; k < 3; k++) p.prev[k] = p.pos[k] - p.vel[k] * frameTime;
		p.initial = new Particle();
		p.initial.copyFrom(p);
	}

	/** Stops emitting; the end cap plays (its children start, end-cap operators run) and the rest fades. */
	public void stop() {
		for (Emitter e : emitters) e.stop();
		if (!inEndCap) {
			inEndCap = true;
			endCapStart = age;
		}
		for (Child ch : children) {
			if (!ch.endCap) ch.system.stop();
		}
	}

	/** Nothing left to draw and nothing more to come (end-cap children only count once started). */
	public boolean finished() {
		if (!particles.isEmpty()) return false;
		for (Emitter e : emitters) if (!e.finished) return false;
		for (Child ch : children) {
			if (ch.endCap) {
				if (inEndCap && (!ch.system.started || !ch.system.finished())) return false;
			} else if (!ch.system.started) {
				// A delayed child still to come, unless the system was stopped first.
				if (!inEndCap) return false;
			} else if (!ch.system.finished()) {
				return false;
			}
		}
		return true;
	}

	public float age() {
		return age;
	}

	/** Every system in the tree, this one first. */
	public void forEach(java.util.function.Consumer<FxSystem> visit) {
		visit.accept(this);
		for (Child ch : children) {
			if (ch.system.started) ch.system.forEach(visit);
		}
	}

	public int particleCount() {
		int[] n = {0};
		forEach(s -> n[0] += s.particles.size());
		return n[0];
	}

	/** A particle system as the pack holds it: Deadlock's definition, parsed once and shared. */
	public static final class Definition {
		final String path;
		final Map<String, Object> data;
		final int maxParticles, behaviorVersion;
		final float maxTimeStep;

		Definition(String path, Map<String, Object> data) {
			this.path = path;
			this.data = data;
			maxParticles = Math.min(Kv3.i(data, "m_nMaxParticles", 1000), 2000);
			behaviorVersion = Kv3.i(data, "m_nBehaviorVersion", 0);
			float step = Kv3.f(data, "m_flMaximumTimeStep", 0.1f);
			maxTimeStep = step <= 0 ? 0.1f : step;
		}
	}
}
