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

	private static String hero = "unicorn";
	private static boolean enabled = true;
	private static HeroModel model;
	private static HeroAnimator animator;
	private static RenderType[] renderTypes;
	private static String loadedHero;
	private static String problem = "";
	private static float[] skinned, skinnedNormals;
	private static long lastNanos;
	private static HeroAnimator.Input input = new HeroAnimator.Input(0, 0, 0, true, 0);

	private HeroRenderer() {}

	public static Path heroDirectory() {
		String local = System.getenv("LOCALAPPDATA");
		return Path.of(local == null ? System.getProperty("user.home") : local, "Deadcraft", "heroes");
	}

	/** Called by Follow every frame while linked. Speeds in blocks per second, relative to facing. */
	public static void setInput(HeroAnimator.Input in) {
		input = in;
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
			skinned = new float[m.positions.length];
			skinnedNormals = new float[m.normals.length];
			problem = "";
			int tris = m.materials.stream().mapToInt(x -> x.indices().length / 3).sum();
			LOG.info("Deadcraft: hero model {} loaded: {} vertices, {} triangles, {} joints, {} clips, in {} ms", hero,
				m.vertexCount(), tris, m.jointCount, m.clips.size(), (System.nanoTime() - t0) / 1_000_000);
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
		float[] matrices = animator.update(input, dt);
		skin(matrices);
		skinNanos += System.nanoTime() - t0;

		poseStack.pushPose();
		// The model faces glTF +Z; Minecraft's yaw 0 faces +Z (south) and turns clockwise seen from above.
		poseStack.rotateDegrees(Axis.YP, -state.bodyRot);
		poseStack.scale(BLOCKS_PER_METRE, BLOCKS_PER_METRE, BLOCKS_PER_METRE);
		int light = state.lightCoords;
		for (int i = 0; i < renderTypes.length; i++) {
			int[] indices = model.materials.get(i).indices();
			if (indices.length == 0) continue;
			collector.submitCustomGeometry(poseStack, renderTypes[i], (pose, consumer) -> emit(pose, consumer, indices, light));
		}
		poseStack.popPose();
		frames++;
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
	}

	// ---- stats (Follow logs them every 10 s) ----
	private static long skinNanos, emitNanos;
	private static int frames;

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
