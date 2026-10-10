package dev.deadcraft.client.fx;

import dev.deadcraft.client.fx.Inputs.FloatInput;
import dev.deadcraft.client.fx.Inputs.Transform;
import dev.deadcraft.client.fx.Inputs.VecInput;
import java.util.List;
import java.util.Map;

/**
 * The particle functions Deadlock's effects are built from: emitters, initializers, operators, pre-emission
 * operators and force generators, by their {@code _class} names. Each running system compiles its own (some
 * keep state). Semantics follow ValveResourceFormat's particle simulation (MIT licence,
 * github.com/ValveResourceFormat/ValveResourceFormat); classes not here are skipped and reported.
 */
final class Functions {
	private Functions() {
	}

	/** What every function shares: when it runs (end cap) and how strongly (operator fades). */
	abstract static class Function {
		private final int endCap; // 0 always, 1 only outside the end cap, 2 only in it
		private final FloatInput opStrength;
		private final float fadeInStart, fadeInEnd, fadeOutStart, fadeOutEnd, oscillate;

		Function(Map<String, Object> m, Compiler c) {
			endCap = switch (Kv3.s(m, "m_nOpEndCapState", "PARTICLE_ENDCAP_ALWAYS_ON")) {
				case "PARTICLE_ENDCAP_ENDCAP_OFF" -> 1;
				case "PARTICLE_ENDCAP_ENDCAP_ON" -> 2;
				default -> 0;
			};
			opStrength = Inputs.floatInput(m.get("m_flOpStrength"), 1, c);
			fadeInStart = Kv3.f(m, "m_flOpStartFadeInTime", 0);
			fadeInEnd = Kv3.f(m, "m_flOpEndFadeInTime", 0);
			fadeOutStart = Kv3.f(m, "m_flOpStartFadeOutTime", 0);
			fadeOutEnd = Kv3.f(m, "m_flOpEndFadeOutTime", 0);
			oscillate = Kv3.f(m, "m_flOpFadeOscillatePeriod", 0);
		}

		final boolean runsNow(FxSystem sys) {
			return endCap == 0 || sys.inEndCap == (endCap == 2);
		}

		final float strength(FxSystem sys) {
			if (!runsNow(sys)) return 0;
			float s = opStrength.get(null, sys);
			if (s <= 0) return s;
			if (fadeInStart == 0 && fadeInEnd == 0 && fadeOutStart == 0 && fadeOutEnd == 0) return s;
			float t = oscillate > 0 ? (sys.age / oscillate) % 1 : sys.age;
			if (fadeInStart > t) return 0;
			if (fadeOutEnd > 0 && fadeOutEnd < t) return 0;
			float inEnd = Math.max(fadeInEnd, fadeInStart), outStart = Math.max(fadeOutStart, inEnd), outEnd = Math.max(fadeOutEnd, outStart);
			float f = 1;
			if (inEnd > t && inEnd > fadeInStart) f = Math.min(f, (t - fadeInStart) / (inEnd - fadeInStart));
			if (t > outStart && outEnd > outStart) f = Math.min(f, (outEnd - t) / (outEnd - outStart));
			return Math.max(0, s) * f;
		}
	}

	abstract static class Emitter extends Function {
		float startAge;
		boolean finished;

		Emitter(Map<String, Object> m, Compiler c) {
			super(m, c);
		}

		void start(FxSystem sys) {
			startAge = sys.age;
			finished = false;
		}

		void stop() {
			finished = true;
		}

		abstract void emit(FxSystem sys, float dt, float strength);
	}

	abstract static class Initializer extends Function {
		Initializer(Map<String, Object> m, Compiler c) {
			super(m, c);
		}

		void reset() {
		}

		abstract void init(Particle p, FxSystem sys);
	}

	abstract static class Operator extends Function {
		Operator(Map<String, Object> m, Compiler c) {
			super(m, c);
		}

		void reset() {
		}

		abstract void operate(FxSystem sys, float dt, float strength);
	}

	abstract static class PreEmission extends Function {
		PreEmission(Map<String, Object> m, Compiler c) {
			super(m, c);
		}

		abstract void operate(FxSystem sys, float dt);
	}

	abstract static class Force extends Function {
		Force(Map<String, Object> m, Compiler c) {
			super(m, c);
		}

		abstract void forces(FxSystem sys, float dt, float strength);
	}

	// ---- factories -------------------------------------------------------------------------------

	static Emitter emitter(String cls, Map<String, Object> m, Compiler c) {
		return switch (cls) {
			case "C_OP_InstantaneousEmitter" -> new InstantaneousEmitter(m, c);
			case "C_OP_ContinuousEmitter" -> new ContinuousEmitter(m, c);
			case "C_OP_NoiseEmitter" -> new NoiseEmitter(m, c);
			default -> null;
		};
	}

	static Initializer initializer(String cls, Map<String, Object> m, Compiler c) {
		return switch (cls) {
			case "C_INIT_InitFloat" -> new InitFloat(m, c);
			case "C_INIT_InitVec" -> new InitVec(m, c);
			case "C_INIT_CreateWithinSphereTransform", "C_INIT_CreateWithinSphere" -> new CreateWithinSphere(m, c);
			case "C_INIT_RandomColor" -> new RandomColor(m, c);
			case "C_INIT_RandomSequence" -> new RandomSequence(m, c);
			case "C_INIT_InitialVelocityNoise" -> new InitialVelocityNoise(m, c);
			case "C_INIT_RandomYawFlip" -> new RandomYawFlip(m, c);
			case "C_INIT_PositionOffset" -> new PositionOffset(m, c);
			case "C_INIT_RingWave" -> new RingWave(m, c);
			case "C_INIT_RemapParticleCountToScalar" -> new RemapParticleCountToScalar(m, c);
			case "C_INIT_NormalOffset" -> new NormalOffset(m, c);
			case "C_INIT_NormalAlignToCP" -> new NormalAlignToCP(m, c);
			case "C_INIT_VelocityRandom" -> new VelocityRandom(m, c);
			case "C_INIT_CreateWithinBox" -> new CreateWithinBox(m, c);
			default -> null;
		};
	}

	static Operator operator(String cls, Map<String, Object> m, Compiler c) {
		return switch (cls) {
			case "C_OP_SetFloat" -> new SetFloat(m, c);
			case "C_OP_SetVec" -> new SetVec(m, c);
			case "C_OP_Decay" -> new Decay(m, c);
			case "C_OP_BasicMovement" -> new BasicMovement(m, c);
			case "C_OP_PositionLock" -> new PositionLock(m, c);
			case "C_OP_SpinUpdate" -> new SpinUpdate(m, c);
			case "C_OP_RampScalarLinearSimple" -> new RampScalarLinearSimple(m, c);
			case "C_OP_ColorInterpolate" -> new ColorInterpolate(m, c);
			case "C_OP_InterpolateRadius" -> new InterpolateRadius(m, c);
			case "C_OP_EndCapTimedDecay" -> new EndCapTimedDecay(m, c);
			case "C_OP_FadeAndKill" -> new FadeAndKill(m, c);
			case "C_OP_FadeOutSimple" -> new FadeOutSimple(m, c);
			case "C_OP_FadeInSimple" -> new FadeInSimple(m, c);
			case "C_OP_LerpEndCapScalar" -> new LerpEndCapScalar(m, c);
			case "C_OP_RadiusDecay" -> new RadiusDecay(m, c);
			case "C_OP_ClampScalar" -> new ClampScalar(m, c);
			case "C_OP_EndCapDecay" -> new EndCapDecay(m, c);
			case "C_OP_AlphaDecay" -> new AlphaDecay(m, c);
			case "C_OP_DistanceToTransform" -> new DistanceToTransform(m, c);
			// Orientation bookkeeping with no visible effect on camera-facing sprites: accepted, not run.
			case "C_OP_NormalLock", "C_OP_RemapTransformOrientationToRotations", "C_OP_RemapTransformOrientationToYaw" -> new NoOp(m, c);
			default -> null;
		};
	}

	static PreEmission preEmission(String cls, Map<String, Object> m, Compiler c) {
		return switch (cls) {
			case "C_OP_SetControlPointToVectorExpression" -> new SetControlPointToVectorExpression(m, c);
			case "C_OP_RemapSpeedtoCP" -> new RemapSpeedtoCP(m, c);
			default -> null;
		};
	}

	static Force force(String cls, Map<String, Object> m, Compiler c) {
		return switch (cls) {
			case "C_OP_CurlNoiseForce" -> new CurlNoiseForce(m, c);
			case "C_OP_RandomForce" -> new RandomForce(m, c);
			default -> null;
		};
	}

	/** Accepts classes that have nothing to draw here, so they aren't reported as missing. */
	static boolean ignored(String cls) {
		return switch (cls) {
			case "C_INIT_RemapTransformOrientationToRotations", "C_INIT_InheritFromParentParticles" -> true;
			default -> false;
		};
	}

	// ---- emitters --------------------------------------------------------------------------------

	static final class InstantaneousEmitter extends Emitter {
		private final FloatInput count, startTime;
		private final int perFrame;
		private int remaining = -1;

		InstantaneousEmitter(Map<String, Object> m, Compiler c) {
			super(m, c);
			count = Inputs.floatInput(m.get("m_nParticlesToEmit"), 100, c);
			startTime = Inputs.floatInput(m.get("m_flStartTime"), 0, c);
			perFrame = Kv3.i(m, "m_nMaxEmittedPerFrame", -1);
		}

		@Override
		void start(FxSystem sys) {
			super.start(sys);
			remaining = -1;
		}

		@Override
		void emit(FxSystem sys, float dt, float strength) {
			if (finished) return;
			float elapsed = sys.age - startAge, start = startTime.get(null, sys);
			if (elapsed < start) return;
			if (remaining < 0) remaining = (int) count.get(null, sys);
			int claimed = Math.min(remaining, perFrame >= 0 ? perFrame : sys.def.maxParticles);
			remaining -= claimed;
			int n = (int) (claimed * strength);
			for (int i = 0; i < n; i++) sys.emit(elapsed - start);
			if (remaining <= 0) finished = true;
		}
	}

	static final class ContinuousEmitter extends Emitter {
		private final FloatInput duration, startTime, rate;
		private double charge;

		ContinuousEmitter(Map<String, Object> m, Compiler c) {
			super(m, c);
			duration = Inputs.floatInput(m.get("m_flEmissionDuration"), 0, c);
			startTime = Inputs.floatInput(m.get("m_flStartTime"), 0, c);
			rate = Inputs.floatInput(m.get("m_flEmitRate"), 100, c);
		}

		@Override
		void start(FxSystem sys) {
			super.start(sys);
			charge = 0;
		}

		@Override
		void emit(FxSystem sys, float dt, float strength) {
			if (finished) return;
			float elapsed = sys.age - startAge, frameStart = elapsed - dt;
			float start = startTime.get(null, sys), dur = duration.get(null, sys);
			float windowStart = Math.max(frameStart, start), windowEnd = dur > 0 ? Math.min(elapsed, start + dur) : elapsed;
			if (windowEnd > windowStart) charge = charge(sys, charge, rate.get(null, sys) * strength, windowStart, windowEnd, elapsed);
			if (dur > 0 && elapsed > start + dur) finished = true;
		}
	}

	/** Emits at a rate over a window of the emitter's time, spread through it; returns the left-over charge. */
	static double charge(FxSystem sys, double charge, float rate, float windowStart, float windowEnd, float elapsed) {
		if (rate <= 0) return charge;
		double before = charge;
		charge += rate * (windowEnd - windowStart);
		int n = (int) Math.floor(charge + 0.001);
		charge -= n;
		for (int i = 0; i < n; i++) {
			// Spread over the window: the first is the oldest.
			double at = windowStart + ((i + 1 - before) / rate);
			sys.emit((float) Math.max(0, elapsed - Math.min(windowEnd, at)));
		}
		return charge;
	}

	/** Emits at a rate that wanders with noise between a minimum and a maximum. */
	static final class NoiseEmitter extends Emitter {
		private final FloatInput duration, startTime, noiseScale, outMin, outMax;
		private final float offset;
		private final boolean absVal, absInv;
		private final int scaleCp, scaleField;
		private double charge;

		NoiseEmitter(Map<String, Object> m, Compiler c) {
			super(m, c);
			duration = Inputs.floatInput(m.get("m_flEmissionDuration"), 0, c);
			startTime = Inputs.floatInput(m.get("m_flStartTime"), 0, c);
			noiseScale = Inputs.floatInput(m.get("m_flNoiseScale"), 0.1f, c);
			outMin = Inputs.floatInput(m.get("m_flOutputMin"), 0, c);
			outMax = Inputs.floatInput(m.get("m_flOutputMax"), 100, c);
			offset = Kv3.f(m, "m_flOffset", 0);
			absVal = Kv3.b(m, "m_bAbsVal", false);
			absInv = Kv3.b(m, "m_bAbsValInv", false);
			scaleCp = Kv3.i(m, "m_nScaleControlPoint", -1);
			scaleField = Kv3.i(m, "m_nScaleControlPointField", 0);
		}

		@Override
		void start(FxSystem sys) {
			super.start(sys);
			charge = 1;
		}

		@Override
		void emit(FxSystem sys, float dt, float strength) {
			if (finished) return;
			float elapsed = sys.age - startAge, frameStart = elapsed - dt;
			float start = startTime.get(null, sys), dur = duration.get(null, sys);
			float windowStart = Math.max(frameStart, start), windowEnd = dur > 0 ? Math.min(elapsed, start + dur) : elapsed;
			if (windowEnd > windowStart) {
				float t = (elapsed + offset) * noiseScale.get(null, sys);
				float n = Noise.value3(t, t, t);
				float absScale = absVal ? 1 : 0.5f;
				float norm = absVal ? Math.abs(n) : n;
				if (absInv) norm = 1 - norm;
				float lo = outMin.get(null, sys), span = outMax.get(null, sys) - lo;
				float rate = Math.max(0, lo + (1 - absScale) * span + absScale * span * norm) * strength;
				if (scaleCp >= 0 && scaleField >= 0 && scaleField <= 2) rate *= Math.max(0, sys.cp(scaleCp).pos[scaleField]);
				charge = charge(sys, charge, rate, windowStart, windowEnd, elapsed);
			}
			if (dur > 0 && elapsed > start + dur) finished = true;
		}
	}

	// ---- initializers ----------------------------------------------------------------------------

	static float setMethod(String method, float value, float initial, float current, float dt) {
		return switch (method) {
			case "PARTICLE_SET_SCALE_INITIAL_VALUE" -> value * initial;
			case "PARTICLE_SET_ADD_TO_INITIAL_VALUE" -> value + initial;
			case "PARTICLE_SET_SCALE_CURRENT_VALUE" -> value * current;
			case "PARTICLE_SET_ADD_TO_CURRENT_VALUE" -> value + current;
			case "PARTICLE_SET_RAMP_CURRENT_VALUE" -> current + value * dt;
			default -> value;
		};
	}

	static boolean scales(String method) {
		return method.equals("PARTICLE_SET_SCALE_INITIAL_VALUE") || method.equals("PARTICLE_SET_SCALE_CURRENT_VALUE");
	}

	static final class InitFloat extends Initializer {
		private final int field;
		private final FloatInput value, inputStrength;
		private final String method;

		InitFloat(Map<String, Object> m, Compiler c) {
			super(m, c);
			field = Kv3.i(m, "m_nOutputField", Particle.RADIUS);
			value = Inputs.floatInput(m.get("m_InputValue"), 0, c);
			inputStrength = Inputs.floatInput(m.get("m_InputStrength"), 1, c);
			method = Kv3.s(m, "m_nSetMethod", "PARTICLE_SET_REPLACE_VALUE");
		}

		@Override
		void init(Particle p, FxSystem sys) {
			float v = value.get(p, sys);
			if (Particle.isAngle(field) && !scales(method)) v = (float) Math.toRadians(v);
			float current = p.scalar(field);
			float target = setMethod(method, v, current, current, sys.frameTime);
			float s = inputStrength.get(p, sys);
			p.setScalar(field, current + (target - current) * s);
		}
	}

	static final class InitVec extends Initializer {
		private final int field;
		private final VecInput value;
		private final boolean normalize;
		private final float[] v = new float[3];

		InitVec(Map<String, Object> m, Compiler c) {
			super(m, c);
			field = Kv3.i(m, "m_nOutputField", Particle.COLOR);
			value = Inputs.vecInput(m.get("m_InputValue"), 0, 0, 0, c);
			normalize = Kv3.b(m, "m_bNormalizedOutput", false);
		}

		@Override
		void init(Particle p, FxSystem sys) {
			value.get(p, sys, v);
			if (normalize) normalize(v);
			p.setVector(field, v[0], v[1], v[2]);
		}
	}

	static final class CreateWithinSphere extends Initializer {
		private final Transform transform;
		private final FloatInput radiusMin, radiusMax, speedMin, speedMax;
		private final VecInput localSpeedMin, localSpeedMax, distanceBias;
		private final float speedExp;
		private final float[] biasAbs;
		private final boolean localCoords;
		private final int outputField;
		private final float[] a = new float[3], b = new float[3], bias = new float[3], t = new float[3];

		CreateWithinSphere(Map<String, Object> m, Compiler c) {
			super(m, c);
			transform = Transform.parse(m.get("m_TransformInput"), Kv3.i(m, "m_nControlPointNumber", 0));
			radiusMin = Inputs.floatInput(m.get("m_fRadiusMin"), 0, c);
			radiusMax = Inputs.floatInput(m.get("m_fRadiusMax"), 0, c);
			speedMin = Inputs.floatInput(m.get("m_fSpeedMin"), 0, c);
			speedMax = Inputs.floatInput(m.get("m_fSpeedMax"), 0, c);
			localSpeedMin = Inputs.vecInput(m.get("m_LocalCoordinateSystemSpeedMin"), 0, 0, 0, c);
			localSpeedMax = Inputs.vecInput(m.get("m_LocalCoordinateSystemSpeedMax"), 0, 0, 0, c);
			distanceBias = Inputs.vecInput(m.get("m_vecDistanceBias"), 1, 1, 1, c);
			speedExp = Kv3.f(m, "m_fSpeedRandExp", 1);
			biasAbs = Kv3.vec(m, "m_vecDistanceBiasAbs", 0, 0, 0);
			localCoords = Kv3.b(m, "m_bLocalCoords", false);
			outputField = Kv3.i(m, "m_nFieldOutput", Particle.POSITION);
		}

		@Override
		void init(Particle p, FxSystem sys) {
			ControlPoint cp = transform.get(sys);
			float fraction = sys.unitBall(a);
			for (int k = 0; k < 3; k++) if (biasAbs[k] != 0) a[k] = Math.abs(a[k]);
			distanceBias.get(p, sys, bias);
			for (int k = 0; k < 3; k++) a[k] *= bias[k];
			normalize(a);
			float rMin = radiusMin.get(p, sys), rMax = radiusMax.get(p, sys);
			float distance = (rMax - rMin) * fraction + rMin;
			float speed = sys.rngExp(speedExp, speedMin.get(p, sys), speedMax.get(p, sys));
			localSpeedMin.get(p, sys, b);
			localSpeedMax.get(p, sys, t);
			for (int k = 0; k < 3; k++) b[k] = b[k] + (t[k] - b[k]) * sys.rng();
			if (localCoords) {
				cp.toWorld(a[0], a[1], a[2], a);
			}
			p.setVector(outputField, cp.pos[0] + a[0] * distance, cp.pos[1] + a[1] * distance, cp.pos[2] + a[2] * distance);
			cp.toWorld(b[0], b[1], b[2], b);
			for (int k = 0; k < 3; k++) p.vel[k] = a[k] * speed + b[k];
		}
	}

	static final class CreateWithinBox extends Initializer {
		private final VecInput min, max;
		private final Transform transform;
		private final boolean local;
		private final float[] lo = new float[3], hi = new float[3];

		CreateWithinBox(Map<String, Object> m, Compiler c) {
			super(m, c);
			min = Inputs.vecInput(m.get("m_vecMin"), 0, 0, 0, c);
			max = Inputs.vecInput(m.get("m_vecMax"), 0, 0, 0, c);
			transform = Transform.parse(m.get("m_TransformInput"), Kv3.i(m, "m_nControlPointNumber", 0));
			local = Kv3.b(m, "m_bLocalSpace", false);
		}

		@Override
		void init(Particle p, FxSystem sys) {
			min.get(p, sys, lo);
			max.get(p, sys, hi);
			for (int k = 0; k < 3; k++) lo[k] += (hi[k] - lo[k]) * sys.rng();
			ControlPoint cp = transform.get(sys);
			if (local) cp.toWorld(lo[0], lo[1], lo[2], lo);
			p.setVector(Particle.POSITION, cp.pos[0] + lo[0], cp.pos[1] + lo[1], cp.pos[2] + lo[2]);
		}
	}

	static final class RandomColor extends Initializer {
		private final float[] min, max;
		private final int field;

		RandomColor(Map<String, Object> m, Compiler c) {
			super(m, c);
			min = Kv3.vec(m, "m_ColorMin", 255, 255, 255);
			max = Kv3.vec(m, "m_ColorMax", 255, 255, 255);
			field = Kv3.i(m, "m_nFieldOutput", Particle.COLOR);
		}

		@Override
		void init(Particle p, FxSystem sys) {
			float r = sys.rng();
			p.setVector(field, (min[0] + (max[0] - min[0]) * r) / 255f, (min[1] + (max[1] - min[1]) * r) / 255f,
				(min[2] + (max[2] - min[2]) * r) / 255f);
		}
	}

	static final class RandomSequence extends Initializer {
		private final int min, max;
		private final boolean linear;
		private int next;

		RandomSequence(Map<String, Object> m, Compiler c) {
			super(m, c);
			min = Kv3.i(m, "m_nSequenceMin", 0);
			max = Kv3.i(m, "m_nSequenceMax", 0);
			linear = Kv3.b(m, "m_bLinear", false) || Kv3.b(m, "m_bShuffle", false);
		}

		@Override
		void init(Particle p, FxSystem sys) {
			if (max <= min) {
				p.sequence = min;
			} else if (linear) {
				p.sequence = min + (next++ % (max - min + 1));
			} else {
				p.sequence = Math.min(min + (int) (sys.rng() * (max - min + 1)), max);
			}
		}
	}

	static final class InitialVelocityNoise extends Initializer {
		private final VecInput outMin, outMax, offsetLoc;
		private final FloatInput noiseScale, noiseScaleLoc, offset;
		private final float[] absVal, absInv;
		private final Transform transform;
		private final boolean hasTransform;
		private final float[] lo = new float[3], hi = new float[3], off = new float[3], v = new float[3];

		InitialVelocityNoise(Map<String, Object> m, Compiler c) {
			super(m, c);
			outMin = Inputs.vecInput(m.get("m_vecOutputMin"), 0, 0, 0, c);
			outMax = Inputs.vecInput(m.get("m_vecOutputMax"), 1, 1, 1, c);
			noiseScale = Inputs.floatInput(m.get("m_flNoiseScale"), 0.1f, c);
			noiseScaleLoc = Inputs.floatInput(m.get("m_flNoiseScaleLoc"), 0.01f, c);
			offset = Inputs.floatInput(m.get("m_flOffset"), 0, c);
			offsetLoc = Inputs.vecInput(m.get("m_vecOffsetLoc"), 0, 0, 0, c);
			absVal = Kv3.vec(m, "m_vecAbsVal", 0, 0, 0);
			absInv = Kv3.vec(m, "m_vecAbsValInv", 0, 0, 0);
			hasTransform = m.get("m_TransformInput") instanceof Map<?, ?> t && t.get("m_nType") != null
				&& !"PT_TYPE_INVALID".equals(t.get("m_nType"));
			transform = Transform.parse(m.get("m_TransformInput"), 0);
		}

		@Override
		void init(Particle p, FxSystem sys) {
			float ns = noiseScale.get(p, sys), nsl = noiseScaleLoc.get(p, sys), o = offset.get(p, sys);
			offsetLoc.get(p, sys, off);
			float sx = (p.pos[0] + off[0]) * nsl + (p.creationTime + o) * ns, sy = (p.pos[1] + off[1]) * nsl + (p.creationTime + o) * ns,
				sz = (p.pos[2] + off[2]) * nsl + (p.creationTime + o) * ns;
			float[] n = {Noise.value3(sx, sy, sz), Noise.value3(sx + 100000.5f, sy + 300000.25f, sz + 9000001f),
				Noise.value3(sx + 110000.25f, sy + 310000.75f, sz + 9100000f)};
			outMin.get(p, sys, lo);
			outMax.get(p, sys, hi);
			boolean anyInv = absInv[0] != 0 || absInv[1] != 0 || absInv[2] != 0;
			for (int k = 0; k < 3; k++) {
				boolean abs = absVal[k] != 0;
				float val = abs ? Math.abs(n[k]) : n[k];
				if (anyInv) val = absInv[k] != 0 ? 1 + val : -val;
				float absScale = abs ? 1 : 0.5f, span = hi[k] - lo[k];
				v[k] = val * span * absScale + lo[k] + span * (1 - absScale);
			}
			if (hasTransform) transform.get(sys).toWorld(v[0], v[1], v[2], v);
			for (int k = 0; k < 3; k++) p.vel[k] += v[k];
		}
	}

	static final class VelocityRandom extends Initializer {
		private final FloatInput speedMin, speedMax;
		private final VecInput localMin, localMax;
		private final Transform transform;
		private final float[] a = new float[3], b = new float[3], d = new float[3];

		VelocityRandom(Map<String, Object> m, Compiler c) {
			super(m, c);
			speedMin = Inputs.floatInput(m.get("m_fSpeedMin"), 0, c);
			speedMax = Inputs.floatInput(m.get("m_fSpeedMax"), 0, c);
			localMin = Inputs.vecInput(m.get("m_LocalCoordinateSystemSpeedMin"), 0, 0, 0, c);
			localMax = Inputs.vecInput(m.get("m_LocalCoordinateSystemSpeedMax"), 0, 0, 0, c);
			transform = Transform.parse(null, Kv3.i(m, "m_nControlPointNumber", 0));
		}

		@Override
		void init(Particle p, FxSystem sys) {
			sys.unitBall(d);
			normalize(d);
			float speed = speedMin.get(p, sys) + (speedMax.get(p, sys) - speedMin.get(p, sys)) * sys.rng();
			localMin.get(p, sys, a);
			localMax.get(p, sys, b);
			for (int k = 0; k < 3; k++) a[k] += (b[k] - a[k]) * sys.rng();
			transform.get(sys).toWorld(a[0], a[1], a[2], a);
			for (int k = 0; k < 3; k++) p.vel[k] += d[k] * speed + a[k];
		}
	}

	static final class RandomYawFlip extends Initializer {
		private final float percent;

		RandomYawFlip(Map<String, Object> m, Compiler c) {
			super(m, c);
			percent = Kv3.f(m, "m_flPercent", 0.5f);
		}

		@Override
		void init(Particle p, FxSystem sys) {
			if (sys.rng() < percent) p.yaw += (float) Math.PI;
		}
	}

	static final class PositionOffset extends Initializer {
		private final VecInput min, max;
		private final Transform transform;
		private final boolean local, proportional;
		private final float[] lo = new float[3], hi = new float[3];

		PositionOffset(Map<String, Object> m, Compiler c) {
			super(m, c);
			min = Inputs.vecInput(m.get("m_OffsetMin"), 0, 0, 0, c);
			max = Inputs.vecInput(m.get("m_OffsetMax"), 0, 0, 0, c);
			transform = Transform.parse(m.get("m_TransformInput"), 0);
			local = Kv3.b(m, "m_bLocalCoords", false);
			proportional = Kv3.b(m, "m_bProportional", false);
		}

		@Override
		void init(Particle p, FxSystem sys) {
			min.get(p, sys, lo);
			max.get(p, sys, hi);
			for (int k = 0; k < 3; k++) {
				lo[k] += (hi[k] - lo[k]) * sys.rng();
				if (proportional) lo[k] *= p.radius;
			}
			if (local) transform.get(sys).toWorld(lo[0], lo[1], lo[2], lo);
			for (int k = 0; k < 3; k++) p.pos[k] += lo[k];
		}
	}

	static final class RingWave extends Initializer {
		private final boolean even, xyOnly;
		private final FloatInput radius, thickness, perOrbit, speedMin, speedMax;
		private final Transform transform;
		private final float[] a = new float[3];
		private int orbit;

		RingWave(Map<String, Object> m, Compiler c) {
			super(m, c);
			even = Kv3.b(m, "m_bEvenDistribution", false);
			xyOnly = Kv3.b(m, "m_bXYVelocityOnly", false);
			radius = Inputs.floatInput(m.get("m_flInitialRadius"), 0, c);
			thickness = Inputs.floatInput(m.get("m_flThickness"), 0, c);
			perOrbit = Inputs.floatInput(m.get("m_flParticlesPerOrbit"), -1, c);
			speedMin = Inputs.floatInput(m.get("m_flInitialSpeedMin"), 0, c);
			speedMax = Inputs.floatInput(m.get("m_flInitialSpeedMax"), 0, c);
			transform = Transform.parse(m.get("m_TransformInput"), 0);
		}

		@Override
		void reset() {
			orbit = 0;
		}

		@Override
		void init(Particle p, FxSystem sys) {
			float th = thickness.get(p, sys), per = perOrbit.get(p, sys);
			sys.unitBall(a);
			float angle;
			if (even) {
				float n = per == -1 ? sys.particles.size() : per;
				angle = ++orbit * (float) (2 * Math.PI / Math.max(1, n));
			} else {
				angle = sys.rng() * (float) (2 * Math.PI);
			}
			float r = radius.get(p, sys);
			float lx = r * (float) Math.cos(angle) + a[0] * th, ly = r * (float) Math.sin(angle) + a[1] * th, lz = a[2] * th;
			ControlPoint cp = transform.get(sys);
			cp.toWorld(lx, ly, lz, a);
			p.pos[0] = cp.pos[0] + a[0];
			p.pos[1] = cp.pos[1] + a[1];
			p.pos[2] = cp.pos[2] + a[2];
			if (xyOnly) a[2] = 0;
			normalize(a);
			float speed = speedMin.get(p, sys) + (speedMax.get(p, sys) - speedMin.get(p, sys)) * sys.rng();
			for (int k = 0; k < 3; k++) p.vel[k] += a[k] * speed;
		}
	}

	static final class RemapParticleCountToScalar extends Initializer {
		private final int field;
		private final long inMin, inMax;
		private final float outMin, outMax, bias;
		private final boolean activeRange, invert, wrap;
		private final String method;
		private int count;

		RemapParticleCountToScalar(Map<String, Object> m, Compiler c) {
			super(m, c);
			field = Kv3.i(m, "m_nFieldOutput", Particle.RADIUS);
			inMin = Kv3.i(m, "m_nInputMin", 0);
			inMax = Kv3.i(m, "m_nInputMax", 10);
			outMin = Kv3.f(m, "m_flOutputMin", 0);
			outMax = Kv3.f(m, "m_flOutputMax", 1);
			bias = Kv3.f(m, "m_flRemapBias", 0.5f);
			activeRange = Kv3.b(m, "m_bActiveRange", false);
			invert = Kv3.b(m, "m_bInvert", false);
			wrap = Kv3.b(m, "m_bWrap", false);
			method = Kv3.s(m, "m_nSetMethod", "PARTICLE_SET_REPLACE_VALUE");
		}

		@Override
		void reset() {
			count = 0;
		}

		@Override
		void init(Particle p, FxSystem sys) {
			long min = inMin, max = inMax;
			if (invert) {
				min = sys.particles.size() - inMax - 1;
				max = sys.particles.size() - inMin - 1;
			}
			if (activeRange && count > max) return;
			if (!activeRange || count >= min) {
				float t = max == min ? (count >= max ? 1 : 0) : Inputs.clamp01((count - min) / (float) (max - min));
				float out = outMin + (outMax - outMin) * t;
				if (bias != 0.5f) out = Inputs.biasCurve(out, bias);
				float current = p.scalar(field);
				p.setScalar(field, setMethod(method, out, current, current, sys.frameTime));
			}
			count++;
			if (wrap && count > max) count = 0;
		}
	}

	static final class NormalOffset extends Initializer {
		private final float[] min, max;
		private final int cp;
		private final boolean local, normalize;
		private final float[] v = new float[3];

		NormalOffset(Map<String, Object> m, Compiler c) {
			super(m, c);
			min = Kv3.vec(m, "m_OffsetMin", 0, 0, 0);
			max = Kv3.vec(m, "m_OffsetMax", 0, 0, 0);
			cp = Kv3.i(m, "m_nControlPointNumber", 0);
			local = Kv3.b(m, "m_bLocalCoords", false);
			normalize = Kv3.b(m, "m_bNormalize", false);
		}

		@Override
		void init(Particle p, FxSystem sys) {
			for (int k = 0; k < 3; k++) v[k] = min[k] + (max[k] - min[k]) * sys.rng();
			if (local) sys.cp(cp).toWorld(v[0], v[1], v[2], v);
			for (int k = 0; k < 3; k++) v[k] += p.normal[k];
			if (normalize) normalize(v);
			p.setVector(Particle.NORMAL, v[0], v[1], v[2]);
		}
	}

	static final class NormalAlignToCP extends Initializer {
		private final Transform transform;
		private final String axis;
		private final float[] v = new float[3];

		NormalAlignToCP(Map<String, Object> m, Compiler c) {
			super(m, c);
			transform = Transform.parse(m.get("m_transformInput"), 0);
			axis = Kv3.s(m, "m_nControlPointAxis", "PARTICLE_CP_AXIS_X");
		}

		@Override
		void init(Particle p, FxSystem sys) {
			float x = 0, y = 0, z = 0;
			switch (axis) {
				case "PARTICLE_CP_AXIS_Y" -> y = 1;
				case "PARTICLE_CP_AXIS_Z" -> z = 1;
				case "PARTICLE_CP_AXIS_NEGATIVE_X" -> x = -1;
				case "PARTICLE_CP_AXIS_NEGATIVE_Y" -> y = -1;
				case "PARTICLE_CP_AXIS_NEGATIVE_Z" -> z = -1;
				default -> x = 1;
			}
			transform.get(sys).toWorld(x, y, z, v);
			normalize(v);
			p.setVector(Particle.NORMAL, v[0], v[1], v[2]);
		}
	}

	// ---- operators -------------------------------------------------------------------------------

	static final class NoOp extends Operator {
		NoOp(Map<String, Object> m, Compiler c) {
			super(m, c);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
		}
	}

	static final class SetFloat extends Operator {
		private final int field;
		private final FloatInput value, lerp;
		private final String method;

		SetFloat(Map<String, Object> m, Compiler c) {
			super(m, c);
			field = Kv3.i(m, "m_nOutputField", Particle.RADIUS);
			value = Inputs.floatInput(m.get("m_InputValue"), 0, c);
			lerp = Inputs.floatInput(m.get("m_Lerp"), 1, c);
			method = Kv3.s(m, "m_nSetMethod", "PARTICLE_SET_REPLACE_VALUE");
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			boolean fromInitial = method.equals("PARTICLE_SET_SCALE_INITIAL_VALUE") || method.equals("PARTICLE_SET_ADD_TO_INITIAL_VALUE");
			for (Particle p : sys.particles) {
				float v = value.get(p, sys);
				float l = Inputs.clamp01(lerp.get(p, sys));
				if (Particle.isAngle(field) && !scales(method)) v = (float) Math.toRadians(v);
				float current = p.scalar(field);
				float target = setMethod(method, v, p.initialScalar(field), current, dt);
				float blended = clampField(field, current + (target - current) * l);
				float base = fromInitial ? p.initialScalar(field) : current;
				p.setScalar(field, base + (blended - base) * strength);
			}
		}
	}

	static float clampField(int field, float v) {
		return switch (field) {
			case Particle.ALPHA, Particle.ALPHA2 -> Inputs.clamp01(v);
			case Particle.RADIUS, Particle.TRAIL_LENGTH -> Math.max(0, v);
			default -> v;
		};
	}

	static final class SetVec extends Operator {
		private final int field;
		private final VecInput value;
		private final FloatInput lerp;
		private final String method;
		private final boolean normalize;
		private final float[] v = new float[3];

		SetVec(Map<String, Object> m, Compiler c) {
			super(m, c);
			field = Kv3.i(m, "m_nOutputField", Particle.COLOR);
			value = Inputs.vecInput(m.get("m_InputValue"), 0, 0, 0, c);
			lerp = Inputs.floatInput(m.get("m_Lerp"), 1, c);
			method = Kv3.s(m, "m_nSetMethod", "PARTICLE_SET_REPLACE_VALUE");
			normalize = Kv3.b(m, "m_bNormalizedOutput", false);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) {
				float[] cur = p.vector(field);
				if (cur == null) continue;
				float[] init = p.initialVector(field);
				value.get(p, sys, v);
				float l = Inputs.clamp01(lerp.get(p, sys) * strength);
				for (int k = 0; k < 3; k++) {
					float target = setMethod(method, v[k], init[k], cur[k], dt);
					v[k] = cur[k] + (target - cur[k]) * l;
				}
				if (normalize) normalize(v);
				p.setVector(field, v[0], v[1], v[2]);
			}
		}
	}

	static final class Decay extends Operator {
		Decay(Map<String, Object> m, Compiler c) {
			super(m, c);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) if (p.age > p.lifetime) p.killed = true;
		}
	}

	static final class EndCapDecay extends Operator {
		EndCapDecay(Map<String, Object> m, Compiler c) {
			super(m, c);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			if (sys.inEndCap) for (Particle p : sys.particles) p.killed = true;
		}
	}

	static final class AlphaDecay extends Operator {
		private final float minAlpha;

		AlphaDecay(Map<String, Object> m, Compiler c) {
			super(m, c);
			minAlpha = Kv3.f(m, "m_flMinAlpha", 0);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) if (p.alpha <= minAlpha) p.killed = true;
		}
	}

	static final class BasicMovement extends Operator {
		private final VecInput gravity;
		private final FloatInput drag;
		private final float[] g = new float[3];

		BasicMovement(Map<String, Object> m, Compiler c) {
			super(m, c);
			gravity = Inputs.vecInput(m.get("m_Gravity"), 0, 0, 0, c);
			drag = Inputs.floatInput(m.get("m_fDrag"), 0, c);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			for (Force f : sys.forces) {
				float s = f.strength(sys);
				if (s > 0) f.forces(sys, dt, s);
			}
			float dt2 = dt * dt;
			gravity.get(null, sys, g);
			float d = Math.max(0, Math.min(0.9999999f, drag.get(null, sys)));
			float dragFactor = (float) Math.exp(Math.log(1 - d) / (1 / 30.0) * dt);
			if (sys.previousFrameTime > 0) dragFactor *= dt / sys.previousFrameTime;
			for (Particle p : sys.particles) {
				for (int k = 0; k < 3; k++) {
					float step = p.forceScale * (g[k] * dt2 + p.force[k] * dt2) + dragFactor * (p.pos[k] - p.prev[k]);
					p.vel[k] = step / dt;
					p.force[k] = 0;
					p.prev[k] = p.pos[k];
					p.pos[k] += step;
				}
			}
		}
	}

	static final class PositionLock extends Operator {
		private final Transform transform;
		private final float startMin, startMax, endMin, endMax, range, jump, prevScale;
		private final float[] last = {Float.NaN, 0, 0};

		PositionLock(Map<String, Object> m, Compiler c) {
			super(m, c);
			transform = Transform.parse(m.get("m_TransformInput"), 0);
			startMin = Kv3.f(m, "m_flStartTime_min", 1);
			startMax = Kv3.f(m, "m_flStartTime_max", 1);
			endMin = Kv3.f(m, "m_flEndTime_min", 1);
			endMax = Kv3.f(m, "m_flEndTime_max", 1);
			range = Kv3.f(m, "m_flRange", 0);
			float j = Kv3.f(m, "m_flJumpThreshold", 512);
			jump = j * j;
			prevScale = Kv3.f(m, "m_flPrevPosScale", 1);
		}

		@Override
		void reset() {
			last[0] = Float.NaN;
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			float[] at = transform.get(sys).pos;
			if (Float.isNaN(last[0])) System.arraycopy(at, 0, last, 0, 3);
			float dx = at[0] - last[0], dy = at[1] - last[1], dz = at[2] - last[2];
			System.arraycopy(at, 0, last, 0, 3);
			if (dx == 0 && dy == 0 && dz == 0) return;
			boolean instant = jump != 0 && dx * dx + dy * dy + dz * dz > jump;
			boolean always = startMin >= 1;
			for (Particle p : sys.particles) {
				float fade = 1;
				if (!always && !instant) {
					float start = startMin + (startMax - startMin) * sys.rng(), end = endMin + (endMax - endMin) * sys.rng();
					float t = p.normalizedAge();
					fade = t <= start ? 1 : 1 - Inputs.clamp01(Inputs.remap(t, start, end));
				}
				float s = strength * fade;
				if (s <= 0) continue;
				float born = dt > 0 ? Math.min(p.age, dt) / dt : 1;
				float k = born * s;
				float mx = dx * k, my = dy * k, mz = dz * k;
				if (range > 0) {
					float d = (float) Math.sqrt(sq(at[0] - p.pos[0] - mx) + sq(at[1] - p.pos[1] - my) + sq(at[2] - p.pos[2] - mz));
					float f = 1 - Inputs.biasCurve(Inputs.clamp01(d / range), 0.2f);
					mx *= f;
					my *= f;
					mz *= f;
				}
				p.prev[0] += mx * prevScale;
				p.prev[1] += my * prevScale;
				p.prev[2] += mz * prevScale;
				p.pos[0] += mx;
				p.pos[1] += my;
				p.pos[2] += mz;
			}
		}
	}

	static final class SpinUpdate extends Operator {
		SpinUpdate(Map<String, Object> m, Compiler c) {
			super(m, c);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) p.roll += p.rollSpeed * Math.min(dt, p.age) * strength;
		}
	}

	static final class RampScalarLinearSimple extends Operator {
		private final float rate, start, end;
		private final int field;

		RampScalarLinearSimple(Map<String, Object> m, Compiler c) {
			super(m, c);
			rate = Kv3.f(m, "m_Rate", 0);
			start = Kv3.f(m, "m_flStartTime", 0);
			end = Kv3.f(m, "m_flEndTime", 1);
			field = Kv3.i(m, "m_nField", Particle.RADIUS);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) {
				float t = p.normalizedAge();
				if (t > start && t < end) p.setScalar(field, p.scalar(field) + rate * dt * strength);
			}
		}
	}

	static final class ColorInterpolate extends Operator {
		private final float[] fade;
		private final float start, end;
		private final int field;
		private final boolean ease;

		ColorInterpolate(Map<String, Object> m, Compiler c) {
			super(m, c);
			float[] f = Kv3.vec(m, "m_ColorFade", 255, 255, 255);
			fade = new float[] {f[0] / 255f, f[1] / 255f, f[2] / 255f};
			start = Kv3.f(m, "m_flFadeStartTime", 0);
			end = Kv3.f(m, "m_flFadeEndTime", 1);
			field = Kv3.i(m, "m_nFieldOutput", Particle.COLOR);
			ease = Kv3.b(m, "m_bEaseInOut", true);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) {
				float t = p.normalizedAge();
				if (t < start || t > end) continue;
				float f = Inputs.remap(t, start, end);
				if (ease) f = f * f * (3 - 2 * f);
				float[] init = p.initialVector(Particle.COLOR);
				float s = f * strength;
				p.setVector(field, init[0] + (fade[0] - init[0]) * s, init[1] + (fade[1] - init[1]) * s, init[2] + (fade[2] - init[2]) * s);
			}
		}
	}

	static final class InterpolateRadius extends Operator {
		private final float start, end, bias;
		private final FloatInput startScale, endScale;
		private final boolean ease;

		InterpolateRadius(Map<String, Object> m, Compiler c) {
			super(m, c);
			start = Kv3.f(m, "m_flStartTime", 0);
			end = Kv3.f(m, "m_flEndTime", 1);
			startScale = Inputs.floatInput(m.get("m_flStartScale"), 1, c);
			endScale = Inputs.floatInput(m.get("m_flEndScale"), 1, c);
			ease = Kv3.b(m, "m_bEaseInAndOut", false);
			float b = Kv3.f(m, "m_flBias", 0.5f);
			bias = b == 0 ? 0.5f : b;
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			if (end <= start) return;
			for (Particle p : sys.particles) {
				if (p.lifetime <= 0) continue;
				float t = p.normalizedAge(), frac = dt / p.lifetime;
				float windowEnd = p.lifetime * (end - start) <= dt ? start + frac : end;
				if (t < start || t > frac + windowEnd) continue;
				float a = startScale.get(p, sys), b = endScale.get(p, sys);
				float f = Inputs.remap(t, start, end);
				if (ease) f = f * f * (3 - 2 * f);
				else if (bias != 0.5f) f = Inputs.biasCurve(f, bias);
				p.radius = p.initialScalar(Particle.RADIUS) * Inputs.clampRange(a + (b - a) * f, a, b);
			}
		}
	}

	/** A particle's distance from a control point, remapped into a field (flames thin out away from the wand). */
	static final class DistanceToTransform extends Operator {
		private final int field;
		private final FloatInput inMin, inMax, outMin, outMax;
		private final Transform start;
		private final String method;
		private final boolean activeRange, additive;
		private final VecInput scale;
		private final float[] sc = new float[3];

		DistanceToTransform(Map<String, Object> m, Compiler c) {
			super(m, c);
			field = Kv3.i(m, "m_nFieldOutput", Particle.RADIUS);
			inMin = Inputs.floatInput(m.get("m_flInputMin"), 0, c);
			inMax = Inputs.floatInput(m.get("m_flInputMax"), 128, c);
			outMin = Inputs.floatInput(m.get("m_flOutputMin"), 0, c);
			outMax = Inputs.floatInput(m.get("m_flOutputMax"), 1, c);
			start = Transform.parse(m.get("m_TransformStart"), Kv3.i(m, "m_nStartCP", 0));
			method = Kv3.s(m, "m_nSetMethod", "PARTICLE_SET_REPLACE_VALUE");
			activeRange = Kv3.b(m, "m_bActiveRange", false);
			additive = Kv3.b(m, "m_bAdditive", false);
			scale = Inputs.vecInput(m.get("m_vecComponentScale"), 1, 1, 1, c);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			float[] o = start.get(sys).pos;
			boolean alpha = field == Particle.ALPHA || field == Particle.ALPHA2;
			for (Particle p : sys.particles) {
				float a = inMin.get(p, sys), b = inMax.get(p, sys), lo = outMin.get(p, sys), hi = outMax.get(p, sys);
				scale.get(p, sys, sc);
				float dx = (p.pos[0] - o[0]) * sc[0], dy = (p.pos[1] - o[1]) * sc[1], dz = (p.pos[2] - o[2]) * sc[2];
				float d2 = dx * dx + dy * dy + dz * dz, a2 = a * a, b2 = b * b;
				boolean pass = !activeRange || (d2 <= b2 && a2 <= d2);
				if (!pass) continue;
				if (alpha) {
					lo = Inputs.clamp01(lo);
					hi = Inputs.clamp01(hi);
				}
				float t = Inputs.clamp01((d2 - a2) / (b2 == a2 ? 1 : b2 - a2));
				float value = lo + (hi - lo) * t;
				float current = p.scalar(field);
				float target = setMethod(method, value, p.initialScalar(field), current, dt);
				float delta = (target - current) * strength;
				p.setScalar(field, additive ? current + (current + delta) : current + delta);
			}
		}
	}

	static final class EndCapTimedDecay extends Operator {
		private final FloatInput time;

		EndCapTimedDecay(Map<String, Object> m, Compiler c) {
			super(m, c);
			time = Inputs.floatInput(m.get("m_flDecayTime"), 0, c);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			if (!sys.inEndCap || sys.endCapAge() <= time.get(null, sys)) return;
			for (Particle p : sys.particles) p.killed = true;
		}
	}

	static final class FadeAndKill extends Operator {
		private final float inStart, inEnd, outStart, outEnd, startAlpha, endAlpha;

		FadeAndKill(Map<String, Object> m, Compiler c) {
			super(m, c);
			inStart = Kv3.f(m, "m_flStartFadeInTime", 0);
			inEnd = Kv3.f(m, "m_flEndFadeInTime", 0.5f);
			outStart = Kv3.f(m, "m_flStartFadeOutTime", 0.5f);
			outEnd = Kv3.f(m, "m_flEndFadeOutTime", 1);
			startAlpha = Kv3.f(m, "m_flStartAlpha", 1);
			endAlpha = Kv3.f(m, "m_flEndAlpha", 0);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) {
				if (p.lifetime < p.age) {
					p.killed = true;
					continue;
				}
				float t = p.normalizedAge(), init = p.initialScalar(Particle.ALPHA), a = p.alpha;
				if (t >= inStart && t < inEnd) a = init * (startAlpha + (1 - startAlpha) * Inputs.smoothstep(inStart, inEnd, t));
				if (t >= outStart && t < outEnd) a = init * (1 + (endAlpha - 1) * Inputs.smoothstep(outStart, outEnd, t));
				p.alpha = p.alpha + (a - p.alpha) * strength;
			}
		}
	}

	static final class FadeOutSimple extends Operator {
		private final float time;
		private final int field;

		FadeOutSimple(Map<String, Object> m, Compiler c) {
			super(m, c);
			time = Kv3.f(m, "m_flFadeOutTime", 0.25f);
			field = Kv3.i(m, "m_nFieldOutput", Particle.ALPHA);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) {
				float left = 1 - p.normalizedAge();
				if (left <= time) p.setScalar(field, left / time * p.initialScalar(Particle.ALPHA));
			}
		}
	}

	static final class FadeInSimple extends Operator {
		private final float time;
		private final int field;

		FadeInSimple(Map<String, Object> m, Compiler c) {
			super(m, c);
			time = Kv3.f(m, "m_flFadeInTime", 0.25f);
			field = Kv3.i(m, "m_nFieldOutput", Particle.ALPHA);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) {
				float t = p.normalizedAge();
				if (t <= time) p.setScalar(field, t / time * p.initialScalar(Particle.ALPHA));
			}
		}
	}

	static final class LerpEndCapScalar extends Operator {
		private final int field;
		private final float output, time;
		private float startAge = -1;

		LerpEndCapScalar(Map<String, Object> m, Compiler c) {
			super(m, c);
			field = Kv3.i(m, "m_nFieldOutput", Particle.RADIUS);
			output = Kv3.f(m, "m_flOutput", 1);
			time = Kv3.f(m, "m_flLerpTime", 1);
		}

		@Override
		void reset() {
			startAge = -1;
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			if (!sys.inEndCap) return;
			if (startAge < 0) {
				startAge = sys.age;
				for (Particle p : sys.particles) if (p.initial != null) p.initial.setScalar(field, p.scalar(field));
			}
			float t = Inputs.clamp01((sys.age - startAge) / (time + 1e-6f));
			for (Particle p : sys.particles) {
				float from = p.initialScalar(field), target = from + (output - from) * t;
				p.setScalar(field, p.scalar(field) + (target - p.scalar(field)) * strength);
			}
		}
	}

	static final class RadiusDecay extends Operator {
		private final float min;

		RadiusDecay(Map<String, Object> m, Compiler c) {
			super(m, c);
			min = Kv3.f(m, "m_flMinRadius", 1);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) if (p.radius <= min) p.killed = true;
		}
	}

	static final class ClampScalar extends Operator {
		private final FloatInput min, max;
		private final int field;

		ClampScalar(Map<String, Object> m, Compiler c) {
			super(m, c);
			field = Kv3.i(m, "m_nFieldOutput", Particle.RADIUS);
			min = Inputs.floatInput(m.get("m_flOutputMin"), 0, c);
			max = Inputs.floatInput(m.get("m_flOutputMax"), 1, c);
		}

		@Override
		void operate(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) {
				float v = p.scalar(field), cl = Math.min(max.get(p, sys), Math.max(v, min.get(p, sys)));
				p.setScalar(field, v + (cl - v) * strength);
			}
		}
	}

	// ---- pre-emission and forces -----------------------------------------------------------------

	static final class SetControlPointToVectorExpression extends PreEmission {
		private final int out;
		private final VecInput in1, in2;
		private final FloatInput lerp;
		private final String expression;
		private final float[] a = new float[3], b = new float[3];

		SetControlPointToVectorExpression(Map<String, Object> m, Compiler c) {
			super(m, c);
			out = Kv3.i(m, "m_nOutputCP", 2);
			in1 = Inputs.vecInput(m.get("m_vInput1"), 0, 0, 0, c);
			in2 = Inputs.vecInput(m.get("m_vInput2"), 0, 0, 0, c);
			lerp = Inputs.floatInput(m.get("m_flLerp"), 0, c);
			expression = Kv3.s(m, "m_nExpression", "VECTOR_EXPRESSION_ADD");
		}

		@Override
		void operate(FxSystem sys, float dt) {
			in1.get(null, sys, a);
			in2.get(null, sys, b);
			float l = lerp.get(null, sys);
			float[] r = new float[3];
			for (int k = 0; k < 3; k++) {
				r[k] = switch (expression) {
					case "VECTOR_EXPRESSION_SUBTRACT" -> a[k] - b[k];
					case "VECTOR_EXPRESSION_MUL" -> a[k] * b[k];
					case "VECTOR_EXPRESSION_DIVIDE" -> b[k] == 0 ? 0 : a[k] / b[k];
					case "VECTOR_EXPRESSION_INPUT_1" -> a[k];
					case "VECTOR_EXPRESSION_MIN" -> Math.min(a[k], b[k]);
					case "VECTOR_EXPRESSION_MAX" -> Math.max(a[k], b[k]);
					case "VECTOR_EXPRESSION_LERP" -> a[k] + (b[k] - a[k]) * l;
					default -> a[k] + b[k];
				};
			}
			if (expression.equals("VECTOR_EXPRESSION_CROSSPRODUCT")) {
				r[0] = a[1] * b[2] - a[2] * b[1];
				r[1] = a[2] * b[0] - a[0] * b[2];
				r[2] = a[0] * b[1] - a[1] * b[0];
			}
			sys.setCpValue(out, r);
		}
	}

	/** A control point's speed, remapped into one component of another (the wand's flame streams as it moves). */
	static final class RemapSpeedtoCP extends PreEmission {
		private final int in, out, field;
		private final float inMin, inMax, outMin, outMax;
		private final boolean deltaV;
		private final float[] previous = new float[3];

		RemapSpeedtoCP(Map<String, Object> m, Compiler c) {
			super(m, c);
			in = Kv3.i(m, "m_nInControlPointNumber", 0);
			out = Kv3.i(m, "m_nOutControlPointNumber", -1);
			field = Kv3.i(m, "m_nField", 0);
			inMin = Kv3.f(m, "m_flInputMin", 0);
			inMax = Kv3.f(m, "m_flInputMax", 1);
			outMin = Kv3.f(m, "m_flOutputMin", 0);
			outMax = Kv3.f(m, "m_flOutputMax", 1);
			deltaV = Kv3.b(m, "m_bUseDeltaV", false);
		}

		@Override
		void operate(FxSystem sys, float dt) {
			if (out < 0 || field < 0 || field > 2 || dt <= 0) return;
			float[] v = sys.cp(in).vel;
			float speed;
			if (deltaV) {
				speed = (float) Math.sqrt(sq(v[0] - previous[0]) + sq(v[1] - previous[1]) + sq(v[2] - previous[2]));
				System.arraycopy(v, 0, previous, 0, 3);
			} else {
				speed = Inputs.len(v);
			}
			float t = Inputs.clamp01(Inputs.remap(speed, inMin, inMax));
			ControlPoint cp = sys.cp(out);
			cp.pos[field] = outMin + (outMax - outMin) * t;
			cp.set = true;
		}
	}

	static final class CurlNoiseForce extends Force {
		private final VecInput freq, scale, offset, offsetRate;
		private final float[] f = new float[3], s = new float[3], o = new float[3], r = new float[3], n = new float[3];

		CurlNoiseForce(Map<String, Object> m, Compiler c) {
			super(m, c);
			freq = Inputs.vecInput(m.get("m_vecNoiseFreq"), 0.02f, 0.02f, 0.02f, c);
			scale = Inputs.vecInput(m.get("m_vecNoiseScale"), 1000, 1000, 1000, c);
			offset = Inputs.vecInput(m.get("m_vecOffset"), 0, 0, 0, c);
			offsetRate = Inputs.vecInput(m.get("m_vecOffsetRate"), 0, 0, 0, c);
		}

		@Override
		void forces(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) {
				freq.get(p, sys, f);
				scale.get(p, sys, s);
				offset.get(p, sys, o);
				offsetRate.get(p, sys, r);
				Noise.curl3(o[0] + sys.age * r[0] + p.pos[0] * f[0], o[1] + sys.age * r[1] + p.pos[1] * f[1], o[2] + sys.age * r[2] + p.pos[2] * f[2], n);
				for (int k = 0; k < 3; k++) p.force[k] += n[k] * s[k] * strength;
			}
		}
	}

	static final class RandomForce extends Force {
		private final float[] min, max;

		RandomForce(Map<String, Object> m, Compiler c) {
			super(m, c);
			min = Kv3.vec(m, "m_MinForce", 0, 0, 0);
			max = Kv3.vec(m, "m_MaxForce", 0, 0, 0);
		}

		@Override
		void forces(FxSystem sys, float dt, float strength) {
			for (Particle p : sys.particles) for (int k = 0; k < 3; k++) p.force[k] += (min[k] + (max[k] - min[k]) * sys.rng()) * strength;
		}
	}

	// ---- helpers ---------------------------------------------------------------------------------

	static float sq(float v) {
		return v * v;
	}

	static void normalize(float[] v) {
		float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		if (l > 1e-8f) {
			v[0] /= l;
			v[1] /= l;
			v[2] /= l;
		}
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> functions(Map<String, Object> def, String key) {
		return (List<Map<String, Object>>) (List<?>) Kv3.list(def, key).stream().filter(o -> o instanceof Map<?, ?>).toList();
	}
}
