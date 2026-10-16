package dev.deadcraft.client.fx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Bloom for the effects, as Deadlock's renderer spreads very bright light into a glow around it: most of what
 * reads as "the flame" around Celeste's wand. Minecraft has no bloom, and its daylight leaves additive cards
 * little to add to, so without this only the cores show.
 *
 * <p>Each frame the additive cards are drawn again (camera-relative, as {@link Effects} built them) into a float
 * layer the size of the screen, depth-tested against the world's depth so walls hide them; the layer is halved
 * {@value #LEVELS} times with a 13-tap filter, added back up level by level with a tent filter, and the result is
 * added over the frame, rolled off keeping the hue. Runs at the end of the level's main pass (Fabric's
 * {@code END_MAIN}), before the hand and the HUD. If anything here fails, bloom switches off and the effects
 * keep drawing.
 */
public final class Bloom {
	/** Halvings: 1/32 of the screen at the smallest, a halo about a tenth of the screen across. */
	static final int LEVELS = 5;
	/** Position (camera-relative blocks), texture coordinate, colour, brightness. */
	private static final VertexFormat FORMAT = VertexFormat.builder(0)
		.addAttribute("Position", GpuFormat.RGB32_FLOAT)
		.addAttribute("UV0", GpuFormat.RG32_FLOAT)
		.addAttribute("Color", GpuFormat.RGBA8_UNORM)
		.addAttribute("Brightness", GpuFormat.R32_FLOAT)
		.build();
	private static final int VERTEX_BYTES = 28;
	private static final BlendFunction ADD = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE);
	private static final Identifier SCREEN_QUAD = Identifier.withDefaultNamespace("core/screenquad");

	private static RenderPipeline source, down, up, composite;
	private static final GpuTexture[] textures = new GpuTexture[LEVELS + 1];
	private static final GpuTextureView[] views = new GpuTextureView[LEVELS + 1];
	private static int width, height;
	private static GpuBuffer vertexBuffer, viewUniform, configUniform;
	private static ByteBuffer vertices = ByteBuffer.allocateDirect(VERTEX_BYTES * 6 * 256).order(ByteOrder.nativeOrder());
	private static int vertexCount;
	private static final List<Batch> batches = new ArrayList<>();
	private static boolean broken;
	private static String problem = "";
	private static long frames, nanos;

	private record Batch(Identifier texture, int first, int count) {
	}

	private Bloom() {
	}

	/** Why bloom is off, or empty. */
	public static String problem() {
		return problem;
	}

	/** Average cost per bloomed frame, for the log. */
	static String stats() {
		if (frames == 0) return "bloom idle";
		String s = String.format(java.util.Locale.ROOT, "bloom %.2f ms/frame", nanos / 1e6 / frames);
		frames = 0;
		nanos = 0;
		return s;
	}

	/** The level's pose when the cards were collected: the main path draws them through it too. */
	private static final Matrix4f pose = new Matrix4f();

	static void pose(Matrix4f m) {
		pose.set(m);
	}

	/** Forgets last frame's cards ({@link Effects} adds this frame's as it draws). */
	static void begin() {
		vertexCount = 0;
		vertices.clear();
		batches.clear();
	}

	/**
	 * One texture's cards: 4 vertices a card, {@code x y z u v} each (camera-relative blocks) in {@code data},
	 * with its colour (linear ARGB) and brightness. Additive cards bloom with all their light, blended ones with
	 * what is brighter than white.
	 */
	static void add(Identifier texture, boolean additive, float[] data, int[] colors, float[] bright, int count) {
		if (broken || count < 4) return;
		int cards = count / 4;
		int need = (vertexCount + cards * 6) * VERTEX_BYTES;
		if (need > vertices.capacity()) {
			ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(need, vertices.capacity() * 2)).order(ByteOrder.nativeOrder());
			vertices.flip();
			bigger.put(vertices);
			vertices = bigger;
		}
		int first = vertexCount;
		for (int q = 0; q < cards; q++) {
			int b = q * 4;
			// Two triangles a card: 0 1 2, 0 2 3.
			for (int k : new int[] {0, 1, 2, 0, 2, 3}) {
				int i = b + k, o = i * 5, c = colors[i];
				vertices.putFloat(data[o]).putFloat(data[o + 1]).putFloat(data[o + 2]).putFloat(data[o + 3]).putFloat(data[o + 4]);
				vertices.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
				// The shader takes a negative brightness as a blended card.
				vertices.putFloat(additive ? bright[i] : -bright[i]);
			}
		}
		vertexCount += cards * 6;
		batches.add(new Batch(texture, first, cards * 6));
	}

	/** Fabric's {@code END_MAIN}: blurs this frame's cards and adds the glow over the frame. */
	static void render(LevelRenderContext ctx) {
		if (broken || vertexCount == 0 || FxSettings.bloom() <= 0) return;
		long t0 = System.nanoTime();
		try {
			renderUnsafe(ctx);
			frames++;
			nanos += System.nanoTime() - t0;
		} catch (Throwable t) {
			broken = true;
			problem = "switched off: " + t;
			FxPack.LOG.error("Deadcraft: effects bloom switched off (the effects still draw)", t);
		} finally {
			begin();
		}
	}

	private static void renderUnsafe(LevelRenderContext ctx) {
		if (FORMAT.getVertexSize() != VERTEX_BYTES) throw new IllegalStateException("bloom vertex size " + FORMAT.getVertexSize() + ", expected " + VERTEX_BYTES);
		RenderTarget main = ctx.gameRenderer().mainRenderTarget();
		if (main.getDepthTextureView() == null) throw new IllegalStateException("the main target has no depth");
		GpuDevice device = RenderSystem.getDevice();
		ensureTargets(device, main.width, main.height);
		pipelines();
		CommandEncoder encoder = device.createCommandEncoder();

		int bytes = vertexCount * VERTEX_BYTES;
		if (vertexBuffer == null || vertexBuffer.size() < bytes) {
			if (vertexBuffer != null) vertexBuffer.close();
			vertexBuffer = device.createBuffer(() -> "deadcraft bloom cards", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, Math.max(bytes, 64 * 1024));
		}
		ByteBuffer upload = vertices.duplicate().order(ByteOrder.nativeOrder());
		upload.flip();
		encoder.writeToBuffer(vertexBuffer.slice(0, bytes), upload);

		var camera = ctx.levelState().cameraRenderState;
		Matrix4f viewProj = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix).mul(pose);
		ByteBuffer view = ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
		viewProj.get(0, view);
		view.putFloat(64, 1).putFloat(68, 0).putFloat(72, 0).putFloat(76, 0);
		encoder.writeToBuffer(viewUniform.slice(), view);
		ByteBuffer config = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
		// The up passes add every level onto the next: divide by their count so strength 1 is one level's light.
		config.putFloat(0, FxSettings.bloom() / LEVELS).putFloat(4, 0).putFloat(8, 0).putFloat(12, 0);
		encoder.writeToBuffer(configUniform.slice(), config);

		GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		GpuSampler repeat = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
		var textureManager = Minecraft.getInstance().getTextureManager();
		Vector4f clear = new Vector4f(0, 0, 0, 0);

		try (RenderPass pass = encoder.createRenderPass(() -> "deadcraft bloom source", views[0], Optional.of(clear), main.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(source));
			pass.setUniform("BloomView", viewUniform);
			pass.setVertexBuffer(0, vertexBuffer.slice(0, bytes));
			for (Batch b : batches) {
				var texture = textureManager.getTexture(b.texture());
				pass.setUniform("Sampler0", texture.getTextureView(), repeat);
				pass.draw(b.count(), 1, b.first(), 0);
			}
		}
		for (int i = 1; i <= LEVELS; i++) {
			final int level = i;
			try (RenderPass pass = encoder.createRenderPass(() -> "deadcraft bloom down " + level, views[i], Optional.of(clear))) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(down));
				pass.setUniform("InSampler", views[i - 1], linear);
				pass.draw(3, 1, 0, 0);
			}
		}
		for (int i = LEVELS - 1; i >= 1; i--) {
			final int level = i;
			try (RenderPass pass = encoder.createRenderPass(() -> "deadcraft bloom up " + level, views[i], Optional.empty())) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(up));
				pass.setUniform("InSampler", views[i + 1], linear);
				pass.draw(3, 1, 0, 0);
			}
		}
		try (RenderPass pass = encoder.createRenderPass(() -> "deadcraft bloom composite", main.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(composite));
			pass.setUniform("InSampler", views[1], linear);
			pass.setUniform("BloomConfig", configUniform);
			pass.draw(3, 1, 0, 0);
		}
	}

	/** The float layers: the screen's size, then halved each level. Remade when the window resizes. */
	private static void ensureTargets(GpuDevice device, int w, int h) {
		if (viewUniform == null) {
			viewUniform = device.createBuffer(() -> "deadcraft bloom view", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 80);
			configUniform = device.createBuffer(() -> "deadcraft bloom config", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
		}
		if (w == width && h == height && textures[0] != null) return;
		for (int i = 0; i <= LEVELS; i++) {
			if (views[i] != null) views[i].close();
			if (textures[i] != null) textures[i].close();
			final int level = i;
			textures[i] = device.createTexture(() -> "deadcraft bloom " + level, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
				GpuFormat.RGBA16_FLOAT, Math.max(1, w >> i), Math.max(1, h >> i), 1, 1);
			views[i] = device.createTextureView(textures[i]);
		}
		width = w;
		height = h;
	}

	private static void pipelines() {
		if (source != null) return;
		ColorTargetState addFloat = new ColorTargetState(Optional.of(ADD), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL);
		source = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("deadcraft", "pipeline/fx_bloom_source"))
			.withVertexShader(Identifier.fromNamespaceAndPath("deadcraft", "core/fx_bloom"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("deadcraft", "core/fx_bloom"))
			.withBindGroupLayout(BindGroupLayout.builder().withUniform("BloomView", UniformType.UNIFORM_BUFFER)
				.withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build())
			.withColorTargetState(addFloat)
			.withVertexBinding(0, FORMAT)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			// Behind blocks, hidden; the world's depth is left as it is.
			.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
			.withCull(false)
			.build();
		down = post("fx_bloom_down", new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL), false);
		up = post("fx_bloom_up", addFloat, false);
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		composite = post("fx_bloom_composite", new ColorTargetState(Optional.of(ADD), main.getColorTexture().getFormat(), ColorTargetState.WRITE_COLOR), true);
	}

	private static RenderPipeline post(String name, ColorTargetState target, boolean config) {
		BindGroupLayout.Builder layout = BindGroupLayout.builder().withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER);
		if (config) layout.withUniform("BloomConfig", UniformType.UNIFORM_BUFFER);
		return RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("deadcraft", "pipeline/" + name))
			.withVertexShader(SCREEN_QUAD)
			.withFragmentShader(Identifier.fromNamespaceAndPath("deadcraft", "post/" + name))
			.withBindGroupLayout(layout.build())
			.withColorTargetState(target)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withDepthStencilState(Optional.empty())
			.withCull(false)
			.build();
	}
}
