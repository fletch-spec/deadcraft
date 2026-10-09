package dev.deadcraft.client.hero;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.deadcraft.protocol.Proto;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the linked player as their Deadlock hero instead of Minecraft's player model: the packed
 * mesh from {@code tools/hero-export}, skinned on the CPU every frame from {@link HeroAnimator}, one
 * draw per material. The pack lives outside the mod (exported from the player's own Deadlock install,
 * never distributed); without it the vanilla model stays.
 */
public final class HeroRenderer {
	static final Logger LOG = LoggerFactory.getLogger("deadcraft");
	/** glTF metres to Minecraft blocks: the export is Source units * 0.0254. */
	private static final float BLOCKS_PER_METRE = 1f / 0.0254f / Proto.UNITS_PER_BLOCK;
	/** Extra size on top of Deadlock's scale (1 = the hero's true size against Minecraft's blocks). */
	private static float scale = 1.2f;  // chosen by eye 2026-10-10
	/**
	 * Moving, the drawn body's yaw follows the camera's on a critically damped spring: it eases in
	 * instead of jumping to full speed, and settles without a long tail. Settling time, seconds.
	 */
	private static final float BODY_SETTLE_MOVING_S = 0.12f;
	/** Fastest the body turns while moving, degrees/s: a fast mouse swing turns the camera, the body catches up. */
	private static final float BODY_MAX_SPEED_MOVING = 720f;
	private static float bodyYaw = Float.NaN, bodyYawSpeed;

	private static String hero = "unicorn";
	private static boolean enabled = true;
	private static HeroModel model;
	private static HeroAnimator animator;
	private static RenderType[] renderTypes;
	private static String loadedHero;
	private static String problem = "";
	private static float[] skinned, skinnedNormals;
	private static long lastNanos;
	private static HeroAnimator.Input input = new HeroAnimator.Input(0, 0, 0, true, 0, 0, HeroAnimator.Wall.NONE);
	/**
	 * Standing still, the body stays put until the camera is this far round (degrees), then steps to
	 * face it, overshooting by STEP_LEAD, in STEP_S seconds.
	 */
	private static final float IDLE_TURN_START = 55f, STEP_LEAD = 10f, STEP_S = 0.3f;
	private static float stepTime = -1, stepFrom, stepBy;

	private HeroRenderer() {}

	public static Path heroDirectory() {
		String local = System.getenv("LOCALAPPDATA");
		return Path.of(local == null ? System.getProperty("user.home") : local, "Deadcraft", "heroes");
	}

	/** Called by Follow every frame while linked. Speeds in blocks per second, relative to facing. */
	public static void setInput(HeroAnimator.Input in) {
		input = in;
	}

	public static boolean sliding() {
		return animator != null && animator.sliding();
	}

	/** A Deadlock ability was used: movement abilities start animation moves. */
	public static void ability(String name, double ledgeBlocks) {
		if (animator != null) animator.ability(name, input, ledgeBlocks);
	}

	/** True when this player should be drawn as the hero (the pack loaded). */
	public static boolean ready() {
		if (!enabled) return false;
		if (!hero.equals(loadedHero)) load();
		return model != null;
	}

	private static void load() {
		loadedHero = hero;
		model = null;
		Path file = heroDirectory().resolve(hero + ".dchero");
		if (!Files.exists(file)) {
			problem = "no " + file + " (run: dotnet run --project tools/hero-export " + hero + ")";
			LOG.warn("Deadcraft: hero model: {}", problem);
			return;
		}
		try {
			long t0 = System.nanoTime();
			HeroModel m = HeroModel.load(file);
			RenderType[] types = new RenderType[m.materials.size()];
			var textures = Minecraft.getInstance().getTextureManager();
			for (int i = 0; i < types.length; i++) {
				var mat = m.materials.get(i);
				Identifier id = Identifier.fromNamespaceAndPath("deadcraft", "hero/" + hero + "/" + i);
				if (!mat.texture().isEmpty() && Files.exists(m.directory.resolve(mat.texture()))) {
					try (InputStream s = Files.newInputStream(m.directory.resolve(mat.texture()))) {
						NativeImage image = NativeImage.read(s);
						textures.register(id, new DynamicTexture(() -> "deadcraft hero " + mat.name(), image));
					}
				} else {
					NativeImage white = new NativeImage(1, 1, false);
					white.setPixel(0, 0, 0xFFFFFFFF);
					textures.register(id, new DynamicTexture(() -> "deadcraft hero white", white));
				}
				// Cutout without culling: hair cards and cloth are single-sided planes.
				types[i] = RenderTypes.entityCutout(id);
			}
			model = m;
			renderTypes = types;
			animator = new HeroAnimator(m);
			pelvis = m.node("pelvis");
			skinned = new float[m.positions.length];
			skinnedNormals = new float[m.normals.length];
			problem = "";
			int tris = m.materials.stream().mapToInt(x -> x.indices().length / 3).sum();
			LOG.info("Deadcraft: hero model {} loaded: {} vertices, {} triangles, {} joints, {} clips, in {} ms", hero,
				m.vertexCount(), tris, m.skinnedJointCount(), m.clips.size(), (System.nanoTime() - t0) / 1_000_000);
		} catch (Exception e) {
			problem = "can't load " + file + ": " + e;
			LOG.warn("Deadcraft: hero model: {}", problem, e);
		}
	}

	/** Instead of the player model: pose at the entity's feet, facing its body yaw. */
	public static void submit(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		long t0 = now;
		float target = state.bodyRot;
		if (Float.isNaN(bodyYaw) || dt <= 0) bodyYaw = target;
		float diff = ((target - bodyYaw) % 360 + 540) % 360 - 180;
		boolean still = Math.hypot(input.forward(), input.right()) < 0.3 && input.grounded();
		if (still && dt > 0) {
			// Standing: the head turns alone until it reaches its limit, then the feet take one quick step
			// round to face the camera (a little past it, in the turn's direction) and stop, as in
			// Deadlock: head, step, head, step. A turn that keeps going takes another step.
			if (stepTime < 0 && Math.abs(diff) > IDLE_TURN_START) {
				stepFrom = bodyYaw;
				stepBy = diff + Math.signum(diff) * STEP_LEAD;
				stepTime = 0;
			}
			float before = bodyYaw;
			if (stepTime >= 0) {
				stepTime = Math.min(STEP_S, stepTime + dt);
				float p = stepTime / STEP_S;
				bodyYaw = stepFrom + stepBy * p * p * (3 - 2 * p);
				if (stepTime >= STEP_S) stepTime = -1;
			}
			bodyYawSpeed = (((bodyYaw - before) % 360 + 540) % 360 - 180) / dt;
		} else if (dt > 0) {
			stepTime = -1;
			float omega = 2f / BODY_SETTLE_MOVING_S * 2.2f;  // ~settled after the settling time
			// Semi-implicit Euler, sub-stepped so long frames stay stable.
			int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
			float h = dt / steps;
			for (int i = 0; i < steps; i++) {
				float d = ((target - bodyYaw) % 360 + 540) % 360 - 180;
				bodyYawSpeed += (omega * omega * d - 2 * omega * bodyYawSpeed) * h;
				bodyYawSpeed = Math.max(-BODY_MAX_SPEED_MOVING, Math.min(BODY_MAX_SPEED_MOVING, bodyYawSpeed));
				bodyYaw += bodyYawSpeed * h;
			}
		} else {
			bodyYawSpeed = 0;
		}
		animator.setTurnRate(bodyYawSpeed);
		// The upper body turns towards the camera ahead of the feet.
		animator.setLook(((target - bodyYaw) % 360 + 540) % 360 - 180);
		float[] matrices = animator.update(input, dt);
		skin(matrices);
		reportSpikes(now);
		skinNanos += System.nanoTime() - t0;
		hudSkinNanos += System.nanoTime() - t0;

		poseStack.pushPose();
		// The model faces glTF +Z; Minecraft's yaw 0 faces +Z (south) and turns clockwise seen from above.
		poseStack.rotateDegrees(Axis.YP, -bodyYaw);
		poseStack.scale(BLOCKS_PER_METRE * scale, BLOCKS_PER_METRE * scale, BLOCKS_PER_METRE * scale);
		int light = state.lightCoords;
		for (int i = 0; i < renderTypes.length; i++) {
			int[] indices = model.materials.get(i).indices();
			if (indices.length == 0) continue;
			collector.submitCustomGeometry(poseStack, renderTypes[i], (pose, consumer) -> emit(pose, consumer, indices, light));
		}
		poseStack.popPose();
		frames++;
		hudFrames++;
	}

	/** A vertex this far from the pelvis is a broken pose (the body itself spans about 1.5 m). */
	private static final double SPIKE_METRES = 2.0;
	private static long lastSpikeReport;
	private static int pelvis = -1;

	/**
	 * Logs (at most once a second) when part of the posed model is far from the pelvis, with the
	 * animator's state and the joints that vertex hangs from: spikes seen in game that the tests on the
	 * same export don't reproduce.
	 */
	private static void reportSpikes(long now) {
		if (pelvis < 0 || now - lastSpikeReport < 1_000_000_000L) return;
		float[] world = animator.lastWorld();
		double hx = world[pelvis * 12 + 3], hy = world[pelvis * 12 + 7], hz = world[pelvis * 12 + 11], far = 0;
		int farV = -1;
		for (int v = 0, nv = model.vertexCount(); v < nv; v++) {
			double dx = skinned[v * 3] - hx, dy = skinned[v * 3 + 1] - hy, dz = skinned[v * 3 + 2] - hz;
			double d = dx * dx + dy * dy + dz * dz;
			if (d > far) {
				far = d;
				farV = v;
			}
		}
		far = Math.sqrt(far);
		if (far < SPIKE_METRES) return;
		lastSpikeReport = now;
		StringBuilder joints = new StringBuilder();
		for (int k = 0; k < 4; k++) {
			float w = model.weights[farV * 4 + k];
			if (w > 0) joints.append(String.format(" %s %.2f", model.nodeNames[model.skinNodes[model.joints[farV * 4 + k]]], w));
		}
		LOG.warn("Deadcraft: hero spike: vertex {} is {} m from the pelvis ({}); {}", farV, String.format("%.2f", far), joints.toString().trim(),
			animator.debugLine());
	}

	private static void skin(float[] m) {
		float[] p = model.positions, n = model.normals, w = model.weights;
		int[] j = model.joints;
		for (int v = 0, nv = model.vertexCount(); v < nv; v++) {
			float x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2];
			float nx = n[v * 3], ny = n[v * 3 + 1], nz = n[v * 3 + 2];
			float px = 0, py = 0, pz = 0, qx = 0, qy = 0, qz = 0;
			for (int k = 0; k < 4; k++) {
				float wt = w[v * 4 + k];
				if (wt <= 0) continue;
				int b = j[v * 4 + k] * 12;
				px += wt * (m[b] * x + m[b + 1] * y + m[b + 2] * z + m[b + 3]);
				py += wt * (m[b + 4] * x + m[b + 5] * y + m[b + 6] * z + m[b + 7]);
				pz += wt * (m[b + 8] * x + m[b + 9] * y + m[b + 10] * z + m[b + 11]);
				qx += wt * (m[b] * nx + m[b + 1] * ny + m[b + 2] * nz);
				qy += wt * (m[b + 4] * nx + m[b + 5] * ny + m[b + 6] * nz);
				qz += wt * (m[b + 8] * nx + m[b + 9] * ny + m[b + 10] * nz);
			}
			skinned[v * 3] = px;
			skinned[v * 3 + 1] = py;
			skinned[v * 3 + 2] = pz;
			float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
			if (len > 1e-6f) {
				qx /= len;
				qy /= len;
				qz /= len;
			}
			skinnedNormals[v * 3] = qx;
			skinnedNormals[v * 3 + 1] = qy;
			skinnedNormals[v * 3 + 2] = qz;
		}
	}

	/** Triangles as quads with the last corner repeated: entity render types draw quads. */
	private static void emit(PoseStack.Pose pose, VertexConsumer consumer, int[] indices, int light) {
		long t0 = System.nanoTime();
		Matrix4f mp = pose.pose();
		Matrix3f mn = pose.normal();
		float[] uv = model.uvs;
		int[] colours = model.colours;
		for (int t = 0; t < indices.length; t += 3) {
			for (int k = 0; k < 4; k++) {
				int v = indices[t + Math.min(k, 2)];
				float x = skinned[v * 3], y = skinned[v * 3 + 1], z = skinned[v * 3 + 2];
				float wx = mp.m00() * x + mp.m10() * y + mp.m20() * z + mp.m30();
				float wy = mp.m01() * x + mp.m11() * y + mp.m21() * z + mp.m31();
				float wz = mp.m02() * x + mp.m12() * y + mp.m22() * z + mp.m32();
				float nx = skinnedNormals[v * 3], ny = skinnedNormals[v * 3 + 1], nz = skinnedNormals[v * 3 + 2];
				float tx = mn.m00() * nx + mn.m10() * ny + mn.m20() * nz;
				float ty = mn.m01() * nx + mn.m11() * ny + mn.m21() * nz;
				float tz = mn.m02() * nx + mn.m12() * ny + mn.m22() * nz;
				int rgba = colours[v];
				int argb = (rgba & 0xFF) << 24 | rgba >>> 8;
				consumer.addVertex(wx, wy, wz, argb, uv[v * 2], uv[v * 2 + 1], OverlayTexture.NO_OVERLAY, light, tx, ty, tz);
			}
		}
		emitNanos += System.nanoTime() - t0;
		hudEmitNanos += System.nanoTime() - t0;
	}

	// ---- stats (Follow logs them every 10 s; the HUD every second) ----
	private static long skinNanos, emitNanos, hudSkinNanos, hudEmitNanos;
	private static int frames, hudFrames;

	public static String hudLine() {
		if (model == null) return "hero: " + (problem.isEmpty() ? "off" : problem);
		String s = animator.hudLine() + String.format("  |  hero %.2f ms skin + %.2f ms draw",
			hudFrames == 0 ? 0 : hudSkinNanos / 1e6 / hudFrames, hudFrames == 0 ? 0 : hudEmitNanos / 1e6 / hudFrames);
		hudSkinNanos = hudEmitNanos = 0;
		hudFrames = 0;
		return s;
	}

	public static String setScale(float s) {
		scale = s;
		return String.format("Hero scale %.2f (1 = true size against the blocks).", s);
	}

	public static String stats() {
		if (model == null) return "hero model: " + (problem.isEmpty() ? "off" : problem);
		String s = String.format("hero %s: %s; skin %.2f ms, draw %.2f ms per frame", hero, animator.describe(),
			frames == 0 ? 0 : skinNanos / 1e6 / frames, frames == 0 ? 0 : emitNanos / 1e6 / frames);
		skinNanos = emitNanos = 0;
		frames = 0;
		return s;
	}

	public static String command(String name) {
		if (name == null) return ready() ? "Hero model: " + hero + " (" + animator.describe() + ")" : "Hero model off: " + problem;
		if (name.equals("off")) {
			enabled = false;
			return "Hero model off: Minecraft's player model.";
		}
		enabled = true;
		if (!name.equals("on")) hero = name;
		loadedHero = null;
		return ready() ? "Hero model: " + hero : "Hero model unavailable: " + problem;
	}
}
