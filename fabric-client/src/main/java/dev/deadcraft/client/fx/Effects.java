package dev.deadcraft.client.fx;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.deadcraft.client.hero.HeroRenderer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Plays the hero's Deadlock effects in Minecraft's world: each one a {@link FxSystem} in its own frame
 * (Source units, Z up, placed at an origin in the world), driven frame by frame (a tracer's control point
 * rides its bullet), and drawn as additive or blended quads through {@code LevelRenderEvents.COLLECT_SUBMITS}.
 */
public final class Effects {
	private Effects() {
	}

	static final float UNITS_PER_BLOCK = 64;
	/** No effect lives longer than this, seconds (a safety net for ones that never end). */
	private static final float MAX_LIFE_S = 8;
	private static final int MAX_ACTIVE = 96;

	private static FxPack pack;
	private static String packHero;
	private static String problem = "";
	private static final List<Active> active = new ArrayList<>();
	/** Effects started while the active ones are being stepped (a tracer starting its impact) join after. */
	private static final List<Active> starting = new ArrayList<>();
	private static boolean stepping;
	private static long lastNanos;
	private static long seed = 1;
	private static RenderPipeline additive, blended;
	private static final Map<String, RenderType> types = new HashMap<>();
	private static Method createType;
	private static int drawnQuads, framesDrawn;
	/** Set when drawing failed: effects are off for the session and Minecraft particles stand in. */
	private static boolean broken;
	private static double drawNanos;

	/** Sets an effect's control points each frame from its own clock; returns false once it should stop. */
	public interface Driver {
		boolean drive(Active a, float time);
	}

	/** A playing effect. */
	public static final class Active {
		public final FxSystem system;
		final double ox, oy, oz;
		final Driver driver;
		float time;
		boolean stopped;
		/** Lives as long as its driver keeps it (the wand's flames), not just {@link #MAX_LIFE_S}. */
		boolean persistent;

		Active(FxSystem system, double ox, double oy, double oz, Driver driver) {
			this.system = system;
			this.ox = ox;
			this.oy = oy;
			this.oz = oz;
			this.driver = driver;
		}

		/** Places a control point at a Minecraft world position, facing a Minecraft direction. */
		public void place(int cp, Vec3 at, Vec3 forward) {
			ControlPoint p = system.cp(cp);
			p.position((float) ((at.x - ox) * UNITS_PER_BLOCK), (float) (-(at.z - oz) * UNITS_PER_BLOCK), (float) ((at.y - oy) * UNITS_PER_BLOCK));
			if (forward != null) p.orient((float) forward.x, (float) -forward.z, (float) forward.y, 0, 0, 1);
		}
	}

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(Effects::render);
	}

	/** The current hero's effects pack, loaded on first use (null when there's none: see {@link #problem()}). */
	public static FxPack pack() {
		String hero = HeroRenderer.heroName();
		if (pack != null && hero.equals(packHero)) return pack;
		if (hero.equals(packHero)) return null;  // tried already
		packHero = hero;
		close();
		Path file = HeroRenderer.heroDirectory().resolve(hero + ".dcfx");
		if (!Files.exists(file)) {
			problem = "no " + file + " (run: dotnet run --project tools/hero-export " + hero + " --fx)";
			FxPack.LOG.warn("Deadcraft: effects: {}", problem);
			return null;
		}
		try {
			pack = FxPack.load(file);
			problem = "";
			FxPack.LOG.info("Deadcraft: effects for {} loaded: {} named effects, {} attachments, particle scale {}", hero, pack.effects.size(),
				pack.attachments.size(), pack.scale);
		} catch (Exception e) {
			problem = "can't load " + file + ": " + e;
			FxPack.LOG.warn("Deadcraft: effects: {}", problem, e);
		}
		return pack;
	}

	public static String problem() {
		return problem;
	}

	private static void close() {
		active.clear();
		if (pack != null) {
			try {
				pack.close();
			} catch (Exception ignored) {
				// nothing to do
			}
		}
		pack = null;
	}

	/** Starts an effect at a world position (null if the pack doesn't have it). */
	public static Active play(String path, Vec3 at, Vec3 forward, Driver driver) {
		FxPack p = pack();
		if (p == null || path == null || !p.has(path)) return null;
		if (active.size() >= MAX_ACTIVE) active.remove(0).system.stop();
		Active a = new Active(new FxSystem(p, path, seed++ * 7919), at.x, at.y, at.z, driver);
		a.place(0, at, forward);
		(stepping ? starting : active).add(a);
		return a;
	}

	// ---- the hero's weapon and attachments --------------------------------------------------------

	/** The hero's gun (the ability whose weapon has a bullet tracer, named citadel_weapon_*), or null. */
	public static String weaponName() {
		return tracerAbility(true);
	}

	/** The hero's ability that fires bullets (Celeste's Radiant Blast), or null. */
	public static String bulletAbilityName() {
		return tracerAbility(false);
	}

	private static String tracerAbility(boolean weapon) {
		FxPack p = pack();
		if (p == null) return null;
		for (String key : p.effects.keySet()) {
			if (!key.endsWith(" " + GUN_TRACER)) continue;
			String ability = key.substring(0, key.indexOf(' '));
			if (ability.startsWith("citadel_weapon_") == weapon) return ability;
		}
		return null;
	}

	/** Where a model attachment (muzzle_fx, horn_tip_fx, ...) is on the drawn hero, or null. */
	public static Vec3 attachmentWorld(String name) {
		FxPack p = pack();
		FxPack.Attachment at = p == null ? null : p.attachments.get(name);
		if (at != null) at = p.onModel(at, HeroRenderer::hasBone);
		return at == null ? null : HeroRenderer.boneWorld(at.bone(), at.offset());
	}

	// ---- the wand's flames ------------------------------------------------------------------------

	private static final String BATON_FLAME = "m_BatonFlameParticle";
	/** The wand's ends, where its flames burn (Deadlock's weapon_top_fx and weapon_bot_fx). */
	private static final String[] WAND_ENDS = {"weapon_top_fx", "weapon_bot_fx"};
	private static final Active[] wandFlames = new Active[WAND_ENDS.length];

	/**
	 * Keeps the gun's always-on flame burning at each end of the drawn wand while the hero is drawn. Its
	 * control point 3 is where it burns; it works out 5 and 6 itself from how fast that point moves.
	 */
	private static void maintainWand() {
		FxPack p = HeroRenderer.drawnRecently() ? pack() : null;
		String weapon = p == null ? null : weaponName();
		String flame = weapon == null ? null : p.effect(weapon, BATON_FLAME);
		for (int i = 0; i < WAND_ENDS.length; i++) {
			Active a = wandFlames[i];
			boolean alive = a != null && (active.contains(a) || starting.contains(a)) && !a.stopped;
			if (flame == null) {
				if (alive) {
					a.stopped = true;
					a.system.stop();
				}
				wandFlames[i] = null;
				continue;
			}
			if (alive) continue;
			Vec3 at = attachmentWorld(WAND_ENDS[i]);
			if (at == null) continue;
			String end = WAND_ENDS[i];
			Active started = play(flame, at, null, (fx, t) -> {
				Vec3 now = HeroRenderer.drawnRecently() ? attachmentWorld(end) : null;
				if (now == null) return false;
				fx.place(0, now, null);
				fx.place(3, now, null);
				return true;
			});
			if (started != null) {
				started.persistent = true;
				wandFlames[i] = started;
			}
		}
	}

	// ---- abilities -------------------------------------------------------------------------------

	/** A control point of a cast: a model attachment, or a point in the caster's aim frame (Source units). */
	private record Point(int cp, String attachment, float x, float y, float z) {
		static Point at(int cp, String attachment) {
			return new Point(cp, attachment, 0, 0, 0);
		}

		static Point local(int cp, float x, float y, float z) {
			return new Point(cp, null, x, y, z);
		}
	}

	/**
	 * Where each ability's effect puts its control points, from the effect's own preview setup in Deadlock
	 * (the caster at the origin facing +x): the game sets them in code, which we can't read. Keyed by
	 * "ability effect-key".
	 */
	private static final Map<String, Point[]> CASTS = Map.of(
		// Radiant Blast: a cone 779 units long, its edges at +-551; the head, and the cast point at the chest.
		"ability_unicorn_radiantblast m_CastParticle", new Point[] {Point.local(0, 0, 0, 0), Point.local(1, 779, 551, 0),
			Point.local(2, 779, -551, 0), Point.at(3, "head_fx"), Point.local(4, 793, 0, 137), Point.local(5, 30, 0, 76.5f),
			Point.local(10, 779, 0, 0), Point.local(15, 779, 0, -100)},
		// Prismatic Guard: from the horn towards a point 500 units ahead.
		"ability_unicorn_prismaticguard m_CastParticle", new Point[] {Point.at(0, "horn_tip_fx"), Point.local(1, 500, 0, 0),
			Point.local(10, 15.6f, 0, 100.5f), Point.at(4, "horn_base_fx"), Point.local(6, 0, 0, 0)},
		// Luminous Strike: from the horn to the sky above where it lands.
		"ability_unicorn_luminousstrike m_FluxStrikeCast", new Point[] {Point.local(0, 0, 0, 0), Point.at(2, "horn_base_fx"),
			Point.local(5, 531, -174, 916), Point.local(6, 0, 0, 0)});
	/** The orb's charge, held while channelling: the horn tip and both ends of the wand. */
	private static final Point[] ORB_CHARGE = {Point.local(0, 0, 0, 0), Point.at(1, "horn_tip_fx"), Point.at(2, "weapon_top_fx"),
		Point.at(3, "weapon_bot_fx"), Point.local(6, 0, 0, 0)};
	private static final String[][] CAST_KEYS = {{"ability_unicorn_radiantblast", "m_CastParticle"},
		{"ability_unicorn_prismaticguard", "m_CastParticle"}, {"ability_unicorn_luminousstrike", "m_FluxStrikeCast"}};
	private static Active orbCharge;
	private static int abilityLogs;

	/** An ability's event (Deadlock's cast): plays its cast effect from the hero, facing where they aim. */
	public static void ability(String name) {
		FxPack p = broken ? null : pack();
		if (p == null) return;
		for (String[] k : CAST_KEYS) {
			if (!k[0].equals(name)) continue;
			String path = p.effect(k[0], k[1]);
			Point[] points = CASTS.get(k[0] + " " + k[1]);
			Active a = playAt(path, points, null);
			if (abilityLogs++ < 10) FxPack.LOG.info("Deadcraft: ability {}: cast effect {} {}", name, path, a != null ? "played" : "unavailable");
		}
	}

	/** Holds the orb's charge effect while the hero channels (Dazzling Orb), and lets it go when they stop. */
	public static void channeling(boolean on) {
		if (broken) return;
		boolean alive = orbCharge != null && (active.contains(orbCharge) || starting.contains(orbCharge)) && !orbCharge.stopped;
		if (on && !alive) {
			FxPack p = pack();
			if (p == null) return;
			orbCharge = playAt(p.effect("ability_unicorn_dazzlingorb", "m_ChargeParticle"), ORB_CHARGE, () -> orbChanneling);
			if (orbCharge != null) orbCharge.persistent = true;
		}
		orbChanneling = on;
	}

	private static boolean orbChanneling;

	/** Plays an effect with its control points on the hero (following the model each frame) and in their aim frame. */
	private static Active playAt(String path, Point[] points, java.util.function.BooleanSupplier keep) {
		Vec3 feet = heroFeet();
		if (path == null || points == null || feet == null) return null;
		Vec3[] frame = aimFrame();
		return play(path, feet, frame[0], (a, t) -> {
			Vec3 f = heroFeet();
			Vec3[] fr = aimFrame();
			if (f == null) return false;
			for (Point pt : points) {
				Vec3 at = pt.attachment() != null ? attachmentWorld(pt.attachment()) : null;
				if (at == null) at = f.add(fr[0].scale(pt.x() / UNITS_PER_BLOCK)).add(fr[1].scale(pt.y() / UNITS_PER_BLOCK)).add(fr[2].scale(pt.z() / UNITS_PER_BLOCK));
				a.place(pt.cp(), at, fr[0]);
			}
			return keep == null ? t < 3 : keep.getAsBoolean();
		});
	}

	private static Vec3 heroFeet() {
		var player = Minecraft.getInstance().player;
		return player == null ? null : player.position();
	}

	/** Forward (the camera's horizontal look), left and up, in Minecraft's world. */
	private static Vec3[] aimFrame() {
		var f = Minecraft.getInstance().gameRenderer.mainCamera().forwardVector();
		Vec3 forward = new Vec3(f.x(), 0, f.z());
		forward = forward.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : forward.normalize();
		Vec3 up = new Vec3(0, 1, 0);
		Vec3 left = up.cross(forward);
		return new Vec3[] {forward, left, up};
	}

	// ---- the gun ---------------------------------------------------------------------------------

	private static final String GUN_TRACER = "m_mapWeaponInfos.primary.m_szBulletTravelTracerParticle",
		GUN_MUZZLE = "m_mapWeaponInfos.primary.m_szMuzzleFlashEffectName", GUN_IMPACT = "m_mapWeaponInfos.primary.m_strWeaponImpactEffect";
	/** The tracer starts at the drawn wand and joins the bullet's real path over this, seconds. */
	private static final float MUZZLE_BLEND_S = 0.12f;

	/**
	 * A gun shot: the muzzle flash at the drawn wand, the tracer riding the bullet's flight ({@code path}: points
	 * {@code step} seconds apart, from Deadlock's muzzle to where it hit or gave out) and the impact where it
	 * hit, facing out of the surface. False when there's no effect to play (Minecraft particles stand in).
	 */
	public static boolean gunShot(String weapon, Vec3 drawnMuzzle, List<Vec3> path, double step, Vec3 hitNormal) {
		FxPack p = broken ? null : pack();
		if (p == null || path.size() < 2) return false;
		String tracer = p.effect(weapon, GUN_TRACER), muzzle = p.effect(weapon, GUN_MUZZLE), impact = p.effect(weapon, GUN_IMPACT);
		if (tracer == null) return false;
		Vec3 from = path.get(0), dir = path.get(1).subtract(from).normalize();
		Vec3 wand = drawnMuzzle != null ? drawnMuzzle : from;
		play(muzzle, wand, dir, null);
		Vec3 offset = wand.subtract(from);
		double total = step * (path.size() - 1);
		boolean[] hitPlayed = {false};
		play(tracer, wand, dir, (a, t) -> {
			double f = Math.min(t, total) / step;
			int i = Math.min(path.size() - 2, (int) f);
			Vec3 at = path.get(i).lerp(path.get(i + 1), f - i);
			double blend = Math.max(0, 1 - t / MUZZLE_BLEND_S);
			at = at.add(offset.scale(blend * blend));
			Vec3 heading = path.get(i + 1).subtract(path.get(i));
			a.place(0, at, heading.lengthSqr() > 1e-12 ? heading.normalize() : dir);
			if (t >= total) {
				if (hitNormal != null && !hitPlayed[0]) {
					hitPlayed[0] = true;
					play(impact, path.get(path.size() - 1), hitNormal, null);
				}
				return false;
			}
			return true;
		});
		return true;
	}

	// ---- per frame -------------------------------------------------------------------------------

	private static void render(LevelRenderContext ctx) {
		if (broken) return;
		try {
			renderUnsafe(ctx);
		} catch (Throwable t) {
			broken = true;
			active.clear();
			problem = "drawing failed: " + t;
			FxPack.LOG.error("Deadcraft: effects switched off, drawing failed", t);
		}
	}

	private static void renderUnsafe(LevelRenderContext ctx) {
		maintainWand();
		if (active.isEmpty()) {
			lastNanos = 0;
			return;
		}
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		var camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		var left = camera.leftVector();
		var up = camera.upVector();

		stepping = true;
		for (Iterator<Active> it = active.iterator(); it.hasNext();) {
			Active a = it.next();
			a.time += dt;
			if (!a.stopped && a.driver != null && !a.driver.drive(a, a.time)) {
				a.stopped = true;
				a.system.stop();
			}
			a.system.camera((float) ((eye.x - a.ox) * UNITS_PER_BLOCK), (float) (-(eye.z - a.oz) * UNITS_PER_BLOCK), (float) ((eye.y - a.oy) * UNITS_PER_BLOCK));
			a.system.update(dt);
			if ((a.time > MAX_LIFE_S && !a.persistent) || (a.time > 0.05f && a.system.finished())) it.remove();
		}
		stepping = false;
		active.addAll(starting);
		starting.clear();
		if (active.isEmpty() || pack == null) return;

		Map<String, Bucket> buckets = new LinkedHashMap<>();
		float[] right = {-left.x(), left.z(), -left.y()}, upSrc = {up.x(), -up.z(), up.y()};
		for (Active a : active) {
			float[] eyeSrc = {(float) ((eye.x - a.ox) * UNITS_PER_BLOCK), (float) (-(eye.z - a.oz) * UNITS_PER_BLOCK), (float) ((eye.y - a.oy) * UNITS_PER_BLOCK)};
			float bx = (float) (a.ox - eye.x), by = (float) (a.oy - eye.y), bz = (float) (a.oz - eye.z);
			Renderers.draw(a.system, pack, new Renderers.View(eyeSrc, right, upSrc), (texture, add, xyz, uv, argb, bright) -> {
				String key = (add ? "+" : "~") + texture;
				Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(texture, add));
				for (int i = 0; i < 4; i++) {
					// Source (x, y, z) to Minecraft (x, z, -y), in blocks, relative to the camera.
					b.add(bx + xyz[i * 3] / UNITS_PER_BLOCK, by + xyz[i * 3 + 2] / UNITS_PER_BLOCK, bz - xyz[i * 3 + 1] / UNITS_PER_BLOCK,
						uv[i * 2], uv[i * 2 + 1], argb[i], bright);
				}
			});
		}
		var collector = ctx.submitNodeCollector();
		var poseStack = ctx.poseStack();
		for (Bucket b : buckets.values()) {
			RenderType type = type(b.texture, b.additive);
			if (type == null) continue;
			drawnQuads += b.count / 4;
			collector.submitCustomGeometry(poseStack, type, (pose, consumer) -> {
				Matrix4f m = pose.pose();
				float[] d = b.data;
				int[] c = b.colors;
				for (int i = 0; i < b.count; i++) {
					int o = i * 5;
					float x = d[o], y = d[o + 1], z = d[o + 2];
					consumer.addVertex(m.m00() * x + m.m10() * y + m.m20() * z + m.m30(), m.m01() * x + m.m11() * y + m.m21() * z + m.m31(),
						m.m02() * x + m.m12() * y + m.m22() * z + m.m32()).setUv(d[o + 3], d[o + 4]).setColor(c[i])
						.setNormal(Math.min(1, b.bright[i] / MAX_BRIGHTNESS), 0, 0);
				}
			});
		}
		framesDrawn++;
		drawNanos += System.nanoTime() - now;
	}

	/** Quads for one texture and blend: x, y, z, u, v per vertex, and its colour. */
	private static final class Bucket {
		final String texture;
		final boolean additive;
		float[] data = new float[5 * 64];
		int[] colors = new int[64];
		float[] bright = new float[64];
		int count;

		Bucket(String texture, boolean additive) {
			this.texture = texture;
			this.additive = additive;
		}

		void add(float x, float y, float z, float u, float v, int argb, float brightness) {
			if (count + 1 > colors.length) {
				data = java.util.Arrays.copyOf(data, data.length * 2);
				colors = java.util.Arrays.copyOf(colors, colors.length * 2);
				bright = java.util.Arrays.copyOf(bright, bright.length * 2);
			}
			int o = count * 5;
			data[o] = x;
			data[o + 1] = y;
			data[o + 2] = z;
			data[o + 3] = u;
			data[o + 4] = v;
			colors[count] = argb;
			bright[count] = brightness;
			count++;
		}
	}

	// ---- render types ----------------------------------------------------------------------------

	/** Brightness travels to the shader in the normal's x as a fraction of this (fx.vsh). */
	private static final float MAX_BRIGHTNESS = 32;

	private static RenderPipeline pipeline(boolean add) {
		if (add && additive != null) return additive;
		if (!add && blended != null) return blended;
		RenderPipeline.Builder builder = RenderPipeline.builder();
		if (add) builder = builder.withShaderDefine("ADDITIVE");
		RenderPipeline p = builder
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withLocation(Identifier.fromNamespaceAndPath("deadcraft", add ? "pipeline/fx_additive" : "pipeline/fx_blended"))
			// Deadlock's spritecard colour per pixel: brightness after the texture, rolled off keeping the hue.
			.withVertexShader(Identifier.fromNamespaceAndPath("deadcraft", "core/fx"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("deadcraft", "core/fx"))
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
			// Additive: source times its alpha, added (Deadlock's additive cards); else ordinary alpha blending.
			.withColorTargetState(new ColorTargetState(add ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT))
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			// Hidden behind blocks, but not hiding what's behind it.
			.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
			.withCull(false)
			.build();
		if (add) additive = p;
		else blended = p;
		return p;
	}

	private static RenderType type(String texture, boolean add) {
		String key = (add ? "+" : "~") + texture;
		if (types.containsKey(key)) return types.get(key);
		RenderType type = null;
		try {
			Identifier id = textureId(texture);
			RenderSetup setup = RenderSetup.builder(pipeline(add)).withTexture("Sampler0", id).createRenderSetup();
			if (createType == null) {
				// RenderType.create isn't public; Minecraft 26 isn't obfuscated, so it's found by name.
				createType = RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
				createType.setAccessible(true);
			}
			type = (RenderType) createType.invoke(null, "deadcraft_fx" + key.replace('/', '_'), setup);
		} catch (Exception e) {
			FxPack.LOG.warn("Deadcraft: effects: can't make a render type for {}: {}", texture, e.toString());
		}
		types.put(key, type);
		return type;
	}

	private static final Map<String, Identifier> textures = new HashMap<>();

	private static Identifier textureId(String texture) {
		Identifier id = textures.get(texture);
		if (id != null) return id;
		String safe = texture == null ? "white" : texture.toLowerCase().replaceAll("[^a-z0-9/._-]", "_");
		id = Identifier.fromNamespaceAndPath("deadcraft", "fx/" + safe);
		NativeImage image = null;
		if (Renderers.SOFT_DOT.equals(texture)) {
			int size = 64;
			int[] px = Renderers.softDot(size);
			image = new NativeImage(size, size, false);
			for (int i = 0; i < px.length; i++) image.setPixel(i % size, i / size, px[i]);
		}
		byte[] png = pack == null || image != null ? null : pack.texturePng(texture);
		if (png != null) {
			try {
				image = NativeImage.read(png);
			} catch (Exception e) {
				FxPack.LOG.warn("Deadcraft: effects: texture {} unreadable: {}", texture, e.toString());
			}
		}
		if (image == null) {
			image = new NativeImage(1, 1, false);
			image.setPixel(0, 0, 0xFFFFFFFF);
		}
		final String name = safe;
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "deadcraft fx " + name, image));
		textures.put(texture, id);
		return id;
	}

	/** For the 10 s readout: playing effects, quads drawn per frame, time per frame. */
	public static String stats() {
		if (framesDrawn == 0) return "fx idle";
		String s = String.format("fx %d playing, %.0f quads/frame, %.2f ms/frame", active.size(), drawnQuads / (double) framesDrawn,
			drawNanos / framesDrawn / 1e6);
		drawnQuads = 0;
		framesDrawn = 0;
		drawNanos = 0;
		return s;
	}
}
