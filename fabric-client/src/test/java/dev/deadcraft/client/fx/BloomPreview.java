package dev.deadcraft.client.fx;

/**
 * Bloom.java's blur on the CPU, for previews: halve {@value #LEVELS} times with the 13-tap filter
 * (fx_bloom_down.fsh), add back up with the tent (fx_bloom_up.fsh), and return the half-size level stretched to
 * full size as the composite samples it. Bilinear sampling, clamped at the edges, as the GPU's.
 */
final class BloomPreview {
	static final int LEVELS = Bloom.LEVELS;

	private BloomPreview() {
	}

	private record Layer(float[] c, int w, int h) {
		/** Bilinear at a texture coordinate (0..1), clamped to the edge. */
		void sample(float u, float v, float[] out) {
			float x = u * w - 0.5f, y = v * h - 0.5f;
			int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
			float fx = x - x0, fy = y - y0;
			for (int k = 0; k < 3; k++) out[k] = 0;
			for (int j = 0; j < 2; j++) {
				for (int i = 0; i < 2; i++) {
					int px = Math.max(0, Math.min(w - 1, x0 + i)), py = Math.max(0, Math.min(h - 1, y0 + j));
					float wt = (i == 0 ? 1 - fx : fx) * (j == 0 ? 1 - fy : fy);
					int o = (py * w + px) * 3;
					for (int k = 0; k < 3; k++) out[k] += c[o + k] * wt;
				}
			}
		}
	}

	static float[] blur(float[] full, int width, int height, int levels) {
		Layer[] l = new Layer[levels + 1];
		l[0] = new Layer(full, width, height);
		float[] s = new float[3];
		final float[][] down = {{0, 0, 0.125f}, {-2, 2, 0.03125f}, {2, 2, 0.03125f}, {-2, -2, 0.03125f}, {2, -2, 0.03125f}, {0, 2, 0.0625f},
			{-2, 0, 0.0625f}, {2, 0, 0.0625f}, {0, -2, 0.0625f}, {-1, 1, 0.125f}, {1, 1, 0.125f}, {-1, -1, 0.125f}, {1, -1, 0.125f}};
		for (int i = 1; i <= levels; i++) {
			Layer src = l[i - 1];
			int w = Math.max(1, width >> i), h = Math.max(1, height >> i);
			float[] c = new float[w * h * 3];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					float u = (x + 0.5f) / w, v = (y + 0.5f) / h;
					for (float[] t : down) {
						src.sample(u + t[0] / src.w(), v + t[1] / src.h(), s);
						for (int k = 0; k < 3; k++) c[(y * w + x) * 3 + k] += s[k] * t[2];
					}
				}
			}
			l[i] = new Layer(c, w, h);
		}
		final float[][] tent = {{0, 0, 4}, {0, 1, 2}, {-1, 0, 2}, {1, 0, 2}, {0, -1, 2}, {-1, 1, 1}, {1, 1, 1}, {-1, -1, 1}, {1, -1, 1}};
		for (int i = levels - 1; i >= 1; i--) {
			Layer src = l[i + 1], dst = l[i];
			for (int y = 0; y < dst.h(); y++) {
				for (int x = 0; x < dst.w(); x++) {
					float u = (x + 0.5f) / dst.w(), v = (y + 0.5f) / dst.h();
					for (float[] t : tent) {
						src.sample(u + t[0] / src.w(), v + t[1] / src.h(), s);
						for (int k = 0; k < 3; k++) dst.c()[(y * dst.w() + x) * 3 + k] += s[k] * t[2] / 16;
					}
				}
			}
		}
		float[] out = new float[width * height * 3];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				l[1].sample((x + 0.5f) / width, (y + 0.5f) / height, s);
				System.arraycopy(s, 0, out, (y * width + x) * 3, 3);
			}
		}
		return out;
	}
}
