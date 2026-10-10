package dev.deadcraft.client.fx;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Draws an effect's quads into an image, as Minecraft would (the pack's textures, additive or blended over
 * black, orthographic), so an effect can be looked at without the game: a strip of frames per effect.
 */
final class FxPreview implements Renderers.Sink {
	final int width, height;
	final float[] rgb;
	private final FxPack pack;
	private final Renderers.View view;
	private final float[] centre;
	private final float pixelsPerUnit;
	private final Map<String, BufferedImage> textures = new HashMap<>();
	int quads;

	FxPreview(FxPack pack, Renderers.View view, float[] centre, float pixelsPerUnit, int width, int height) {
		this.pack = pack;
		this.view = view;
		this.centre = centre;
		this.pixelsPerUnit = pixelsPerUnit;
		this.width = width;
		this.height = height;
		this.rgb = new float[width * height * 3];
	}

	private BufferedImage texture(String path) {
		return textures.computeIfAbsent(path == null ? "" : path, p -> {
			if (Renderers.SOFT_DOT.equals(p)) {
				BufferedImage dot = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
				dot.setRGB(0, 0, 64, 64, Renderers.softDot(64), 0, 64);
				return dot;
			}
			byte[] png = pack.texturePng(path);
			try {
				return png == null ? null : ImageIO.read(new ByteArrayInputStream(png));
			} catch (IOException e) {
				return null;
			}
		});
	}

	@Override
	public void quad(String texture, boolean additive, float[] xyz, float[] uv, int[] argb) {
		quads++;
		float[] sx = new float[4], sy = new float[4];
		for (int i = 0; i < 4; i++) {
			float dx = xyz[i * 3] - centre[0], dy = xyz[i * 3 + 1] - centre[1], dz = xyz[i * 3 + 2] - centre[2];
			sx[i] = width / 2f + (dx * view.right()[0] + dy * view.right()[1] + dz * view.right()[2]) * pixelsPerUnit;
			sy[i] = height / 2f - (dx * view.up()[0] + dy * view.up()[1] + dz * view.up()[2]) * pixelsPerUnit;
		}
		BufferedImage tex = texture(texture);
		triangle(tex, additive, sx, sy, uv, argb, 0, 1, 2);
		triangle(tex, additive, sx, sy, uv, argb, 0, 2, 3);
	}

	private void triangle(BufferedImage tex, boolean additive, float[] sx, float[] sy, float[] uv, int[] argb, int a, int b, int c) {
		int minX = (int) Math.max(0, Math.floor(Math.min(sx[a], Math.min(sx[b], sx[c]))));
		int maxX = (int) Math.min(width - 1, Math.ceil(Math.max(sx[a], Math.max(sx[b], sx[c]))));
		int minY = (int) Math.max(0, Math.floor(Math.min(sy[a], Math.min(sy[b], sy[c]))));
		int maxY = (int) Math.min(height - 1, Math.ceil(Math.max(sy[a], Math.max(sy[b], sy[c]))));
		float area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (sy[b] - sy[a]);
		if (Math.abs(area) < 1e-6f) return;
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				float px = x + 0.5f, py = y + 0.5f;
				float w0 = ((sx[b] - px) * (sy[c] - py) - (sx[c] - px) * (sy[b] - py)) / area;
				float w1 = ((sx[c] - px) * (sy[a] - py) - (sx[a] - px) * (sy[c] - py)) / area;
				float w2 = 1 - w0 - w1;
				if (w0 < 0 || w1 < 0 || w2 < 0) continue;
				float u = w0 * uv[a * 2] + w1 * uv[b * 2] + w2 * uv[c * 2], v = w0 * uv[a * 2 + 1] + w1 * uv[b * 2 + 1] + w2 * uv[c * 2 + 1];
				float tr = 1, tg = 1, tb = 1, ta = 1;
				if (tex != null) {
					int tx = Math.floorMod((int) (u * tex.getWidth()), tex.getWidth()), ty = Math.floorMod((int) (v * tex.getHeight()), tex.getHeight());
					int t = tex.getRGB(tx, ty);
					ta = (t >>> 24) / 255f;
					tr = (t >> 16 & 255) / 255f;
					tg = (t >> 8 & 255) / 255f;
					tb = (t & 255) / 255f;
				}
				float cr = 0, cg = 0, cb = 0, ca = 0;
				float[] w = {w0, w1, w2};
				int[] idx = {a, b, c};
				for (int k = 0; k < 3; k++) {
					int col = argb[idx[k]];
					ca += w[k] * (col >>> 24) / 255f;
					cr += w[k] * (col >> 16 & 255) / 255f;
					cg += w[k] * (col >> 8 & 255) / 255f;
					cb += w[k] * (col & 255) / 255f;
				}
				float alpha = ta * ca;
				int o = (y * width + x) * 3;
				if (additive) {
					rgb[o] += tr * cr * alpha;
					rgb[o + 1] += tg * cg * alpha;
					rgb[o + 2] += tb * cb * alpha;
				} else {
					rgb[o] += (tr * cr - rgb[o]) * alpha;
					rgb[o + 1] += (tg * cg - rgb[o + 1]) * alpha;
					rgb[o + 2] += (tb * cb - rgb[o + 2]) * alpha;
				}
			}
		}
	}

	/** Lays frames side by side and writes a PNG. */
	static void writeStrip(FxPreview[] frames, Path file) throws IOException {
		int w = frames[0].width, h = frames[0].height;
		BufferedImage out = new BufferedImage(w * frames.length, h, BufferedImage.TYPE_INT_RGB);
		for (int f = 0; f < frames.length; f++) {
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int o = (y * w + x) * 3;
					int r = (int) (Math.min(1, frames[f].rgb[o]) * 255), g = (int) (Math.min(1, frames[f].rgb[o + 1]) * 255),
						b = (int) (Math.min(1, frames[f].rgb[o + 2]) * 255);
					// A faint grid every 16 units (a quarter block) for scale.
					out.setRGB(f * w + x, y, (r << 16) | (g << 8) | b);
				}
			}
			for (int y = 0; y < h; y++) out.setRGB(f * w, y, 0x404040);
		}
		Files.createDirectories(file.getParent());
		ImageIO.write(out, "png", file.toFile());
	}
}
