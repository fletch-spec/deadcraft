package dev.deadcraft.client.fx;

import dev.deadcraft.client.fx.Inputs.FloatInput;
import dev.deadcraft.client.fx.Inputs.VecInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns particles into textured quads, as Deadlock's sprite, trail and rope renderers do (camera-facing
 * cards, streaks along the motion, ribbons through the particles in order). Models, lights and projected
 * decals aren't drawn. Colours follow Deadlock's spritecard shader: colour times colour scale, times
 * overbright, self-illumination and add-self, in linear light; Minecraft has no HDR, so the result is
 * rolled off into 0..1 and back to sRGB.
 */
public final class Renderers {
	private Renderers() {
	}

	/** Where the quads go: Minecraft's buffer in game, a counter in tests. Coordinates are the effect's frame. */
	public interface Sink {
		/**
		 * One quad: four corners (x, y, z each, in order round the quad), their texture coordinates (u, v each),
		 * their colours (ARGB, the colour in linear light) and the card's brightness (Deadlock's overbright,
		 * self-illumination and add-self, applied per pixel after the texture, as Deadlock does).
		 */
		void quad(String texture, boolean additive, float[] xyz, float[] uv, int[] argb, float brightness);
	}

	/** The camera, in the effect's frame: its position and its right and up directions. */
	public record View(float[] eye, float[] right, float[] up) {
	}

	abstract static class Renderer {
		final String texture;
		final boolean additive;
		final FloatInput radiusScale, alphaScale, overbright, addSelf, selfIllum, diffuse;
		final VecInput colorScale;
		final boolean gamma;
		final float animationRate;
		final boolean animateInFps;
		final String animationType;
		final float[] tmp = new float[3];

		Renderer(Map<String, Object> m, Compiler c) {
			String tex = null;
			for (Object o : Kv3.list(m, "m_vecTexturesInput")) {
				if (!(o instanceof Map<?, ?> layer)) continue;
				@SuppressWarnings("unchecked") Map<String, Object> l = (Map<String, Object>) layer;
				String type = Kv3.s(l, "m_nTextureType", "SPRITECARD_TEXTURE_DIFFUSE");
				if (type.equals("SPRITECARD_TEXTURE_DIFFUSE") && Kv3.s(l, "m_hTexture", null) != null) {
					tex = Kv3.s(l, "m_hTexture", null);
					break;
				}
			}
			if (tex == null) tex = Kv3.s(m, "m_hTexture", null);
			// No colour texture: Deadlock draws a soft round card (a dark square here otherwise).
			texture = tex == null ? SOFT_DOT : tex;
			String blend = Kv3.s(m, "m_nOutputBlendMode", "PARTICLE_OUTPUT_BLEND_MODE_ALPHA");
			additive = blend.contains("ADD") || Kv3.b(m, "m_bAdditive", false);
			radiusScale = Inputs.floatInput(m.get("m_flRadiusScale"), 1, c);
			alphaScale = Inputs.floatInput(m.get("m_flAlphaScale"), 1, c);
			overbright = Inputs.floatInput(m.get("m_flOverbrightFactor"), 1, c);
			addSelf = Inputs.floatInput(m.get("m_flAddSelfAmount"), 0, c);
			selfIllum = Inputs.floatInput(m.get("m_flSelfIllumAmount"), 0, c);
			diffuse = Inputs.floatInput(m.get("m_flDiffuseAmount"), 1, c);
			colorScale = Inputs.vecInput(m.get("m_vecColorScale"), 1, 1, 1, c);
			gamma = Kv3.b(m, "m_bGammaCorrectVertexColors", true);
			animationRate = Kv3.f(m, "m_flAnimationRate", 0.1f);
			animateInFps = Kv3.b(m, "m_bAnimateInFPS", false);
			animationType = Kv3.s(m, "m_nAnimationType", "ANIMATION_TYPE_FIXED_RATE");
		}

		abstract void draw(FxSystem sys, FxPack pack, View view, Sink sink);

		/** A particle's colour (ARGB: the colour in linear light, and its alpha), before brightening. */
		int color(Particle p, FxSystem sys, float alphaMul) {
			colorScale.get(p, sys, tmp);
			float a = Inputs.clamp01(p.alpha * alphaScale.get(p, sys) * alphaMul);
			int argb = (int) (a * 255 + 0.5f) << 24;
			for (int k = 0; k < 3; k++) {
				float c = p.color[k] * tmp[k];
				float lin = gamma ? toLinear(Inputs.clamp01(c)) : Inputs.clamp01(c);
				argb |= (int) (lin * 255 + 0.5f) << (16 - 8 * k);
			}
			return argb;
		}

		/** How much brighter than its colour the card draws (overbright x (1 + add-self) x lighting). */
		float brightness(Particle p, FxSystem sys) {
			return overbright.get(p, sys) * (1 + addSelf.get(p, sys)) * (selfIllum.get(p, sys) + diffuse.get(p, sys) * AMBIENT);
		}

		/** The sprite sheet frame for a particle: u0, v0, u1, v1. */
		void frame(Particle p, FxPack.Sheet sheet, float[] rect) {
			rect[0] = 0;
			rect[1] = 0;
			rect[2] = 1;
			rect[3] = 1;
			if (sheet == null || sheet.sequences().isEmpty()) return;
			int seq = Math.max(0, Math.min(sheet.sequences().size() - 1, (int) p.sequence));
			FxPack.Sequence s = sheet.sequences().get(seq);
			int n = s.frames().size();
			if (n == 0) return;
			int f = 0;
			if (n > 1) {
				float passes;
				if (animationType.equals("ANIMATION_TYPE_MANUAL_FRAMES")) {
					passes = p.manualFrame;
				} else {
					float t = animationType.equals("ANIMATION_TYPE_FIT_LIFETIME") ? p.normalizedAge() : p.age;
					passes = animateInFps ? t * animationRate / Math.max(1e-6f, s.totalTime()) : t * animationRate;
				}
				float pos = s.clamp() ? Inputs.clamp01(passes) : passes - (float) Math.floor(passes);
				f = Math.min(n - 1, (int) (pos * n));
			}
			float[] fr = s.frames().get(f);
			System.arraycopy(fr, 0, rect, 0, 4);
		}
	}

	static final float AMBIENT = 0.8f, KNEE = 0.6f;
	/** The texture name for cards with none: a soft round dot (Effects and previews make it). */
	public static final String SOFT_DOT = "deadcraft:soft_dot";

	/**
	 * Linear HDR colour to an sRGB RGB int: the brightest channel is rolled off above a knee and the others
	 * scaled with it (the hue survives; clamping each channel turned every overbright glow white), then very
	 * bright colours whiten a little, as Deadlock's tone mapping and bloom do.
	 */
	static int tonemap(float[] c) {
		float m = Math.max(c[0], Math.max(c[1], c[2]));
		float r = c[0], g = c[1], b = c[2];
		if (m > KNEE) {
			float rolled = KNEE + (1 - KNEE) * (1 - (float) Math.exp(-(m - KNEE) / (1 - KNEE)));
			float k = rolled / m;
			float white = Inputs.clamp01((m - 1) / (m + 4)) * 0.25f;
			r = r * k + (rolled - r * k) * white;
			g = g * k + (rolled - g * k) * white;
			b = b * k + (rolled - b * k) * white;
		}
		int ri = (int) (toSrgb(r) * 255 + 0.5f), gi = (int) (toSrgb(g) * 255 + 0.5f), bi = (int) (toSrgb(b) * 255 + 0.5f);
		return (ri << 16) | (gi << 8) | bi;
	}

	/** The soft dot, as ARGB pixels of a size x size image. */
	public static int[] softDot(int size) {
		int[] px = new int[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				float dx = (x + 0.5f) / size * 2 - 1, dy = (y + 0.5f) / size * 2 - 1;
				float d = (float) Math.sqrt(dx * dx + dy * dy);
				float a = Inputs.clamp01(1 - d);
				a = a * a * (3 - 2 * a);
				px[y * size + x] = ((int) (a * 255) << 24) | 0xFFFFFF;
			}
		}
		return px;
	}

	static Renderer create(Map<String, Object> m, Compiler c) {
		String cls = Kv3.s(m, "_class", "?");
		return switch (cls) {
			case "C_OP_RenderSprites" -> new Sprites(m, c);
			case "C_OP_RenderTrails" -> new Trails(m, c);
			case "C_OP_RenderRopes" -> new Ropes(m, c);
			// Lights, models, decals and sounds: nothing drawn yet.
			case "C_OP_RenderStandardLight", "C_OP_RenderModels", "C_OP_RenderProjected", "C_OP_RenderSound",
				"C_OP_RenderDeferredLight", "C_OP_RenderOmni2Light", "C_OP_RenderLightBeam", "C_OP_RenderStatusEffect",
				"C_OP_RenderStatusEffectCitadel" -> null;
			default -> {
				c.unsupported("renderer " + cls);
				yield null;
			}
		};
	}

	// ---- sprites ---------------------------------------------------------------------------------

	static final class Sprites extends Renderer {
		private final String orientation;
		private final float[] xyz = new float[12], uv = new float[8], rect = new float[4], a = new float[3], b = new float[3];
		private final int[] argb = new int[4];

		Sprites(Map<String, Object> m, Compiler c) {
			super(m, c);
			orientation = Kv3.s(m, "m_nOrientationType", "PARTICLE_ORIENTATION_SCREEN_ALIGNED");
		}

		@Override
		void draw(FxSystem sys, FxPack pack, View view, Sink sink) {
			FxPack.Sheet sheet = pack.sheet(texture);
			for (Particle p : sys.particles) {
				float r = p.radius * radiusScale.get(p, sys);
				if (r <= 0) continue;
				int col = color(p, sys, 1);
				if ((col >>> 24) == 0) continue;
				// The card's axes.
				switch (orientation) {
					case "PARTICLE_ORIENTATION_WORLD_Z_ALIGNED" -> {
						set(a, 1, 0, 0);
						set(b, 0, 1, 0);
					}
					case "PARTICLE_ORIENTATION_ALIGN_TO_PARTICLE_NORMAL", "PARTICLE_ORIENTATION_SCREENALIGN_TO_PARTICLE_NORMAL" -> {
						basis(p.normal, a, b);
					}
					case "PARTICLE_ORIENTATION_SCREEN_Z_ALIGNED" -> {
						// Upright: the camera's right, and world up.
						set(a, view.right[0], view.right[1], 0);
						Functions.normalize(a);
						set(b, 0, 0, 1);
					}
					default -> {
						set(a, view.right[0], view.right[1], view.right[2]);
						set(b, view.up[0], view.up[1], view.up[2]);
					}
				}
				float cos = (float) Math.cos(p.roll), sin = (float) Math.sin(p.roll);
				float rx = (a[0] * cos + b[0] * sin) * r, ry = (a[1] * cos + b[1] * sin) * r, rz = (a[2] * cos + b[2] * sin) * r;
				float ux = (-a[0] * sin + b[0] * cos) * r, uy = (-a[1] * sin + b[1] * cos) * r, uz = (-a[2] * sin + b[2] * cos) * r;
				float[] o = p.pos;
				corner(0, o, -rx - ux, -ry - uy, -rz - uz);
				corner(1, o, rx - ux, ry - uy, rz - uz);
				corner(2, o, rx + ux, ry + uy, rz + uz);
				corner(3, o, -rx + ux, -ry + uy, -rz + uz);
				frame(p, sheet, rect);
				uv[0] = rect[0];
				uv[1] = rect[3];
				uv[2] = rect[2];
				uv[3] = rect[3];
				uv[4] = rect[2];
				uv[5] = rect[1];
				uv[6] = rect[0];
				uv[7] = rect[1];
				argb[0] = argb[1] = argb[2] = argb[3] = col;
				sink.quad(texture, additive, xyz, uv, argb, brightness(p, sys));
			}
		}

		private void corner(int i, float[] o, float x, float y, float z) {
			xyz[i * 3] = o[0] + x;
			xyz[i * 3 + 1] = o[1] + y;
			xyz[i * 3 + 2] = o[2] + z;
		}
	}

	// ---- trails ----------------------------------------------------------------------------------

	static final class Trails extends Renderer {
		private final float maxLength, minLength, lengthScale, lengthFadeIn;
		private final FloatInput headTaper, tailTaper, headAlpha, tailAlpha;
		private final float[] xyz = new float[12], uv = new float[8], rect = new float[4], side = new float[3];
		private final int[] argb = new int[4];

		Trails(Map<String, Object> m, Compiler c) {
			super(m, c);
			maxLength = Kv3.f(m, "m_flMaxLength", 2000);
			minLength = Kv3.f(m, "m_flMinLength", 0);
			lengthScale = Kv3.f(m, "m_flLengthScale", 1);
			lengthFadeIn = Kv3.f(m, "m_flLengthFadeInTime", 0);
			headTaper = Inputs.floatInput(m.get("m_flRadiusHeadTaper"), 1, c);
			tailTaper = Inputs.floatInput(m.get("m_flRadiusTaper"), 1, c);
			headAlpha = Inputs.floatInput(m.get("m_flHeadAlphaScale"), 1, c);
			tailAlpha = Inputs.floatInput(m.get("m_flTailAlphaScale"), 1, c);
		}

		@Override
		void draw(FxSystem sys, FxPack pack, View view, Sink sink) {
			FxPack.Sheet sheet = pack.sheet(texture);
			float oneOverDt = 1 / Math.max(1e-4f, sys.previousFrameTime > 0 ? sys.previousFrameTime : sys.frameTime);
			for (Particle p : sys.particles) {
				float dx = p.prev[0] - p.pos[0], dy = p.prev[1] - p.pos[1], dz = p.prev[2] - p.pos[2];
				float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (d == 0) continue;
				dx /= d;
				dy /= d;
				dz /= d;
				float length = lengthScale * p.trailLength * d * oneOverDt;
				length *= Math.min(1, p.age / Math.max(lengthFadeIn, 1e-9f));
				length = Math.max(minLength, Math.min(maxLength, length));
				if (length == 0) continue;
				float r = p.radius * radiusScale.get(p, sys);
				// Across the streak, facing the camera.
				float tx = view.eye[0] - p.pos[0], ty = view.eye[1] - p.pos[1], tz = view.eye[2] - p.pos[2];
				side[0] = dy * tz - dz * ty;
				side[1] = dz * tx - dx * tz;
				side[2] = dx * ty - dy * tx;
				Functions.normalize(side);
				float hr = r * headTaper.get(p, sys), tr = r * tailTaper.get(p, sys);
				float[] o = p.pos;
				float ex = o[0] + dx * length, ey = o[1] + dy * length, ez = o[2] + dz * length;
				put(0, o[0] - side[0] * hr, o[1] - side[1] * hr, o[2] - side[2] * hr);
				put(1, o[0] + side[0] * hr, o[1] + side[1] * hr, o[2] + side[2] * hr);
				put(2, ex + side[0] * tr, ey + side[1] * tr, ez + side[2] * tr);
				put(3, ex - side[0] * tr, ey - side[1] * tr, ez - side[2] * tr);
				frame(p, sheet, rect);
				uv[0] = rect[0];
				uv[1] = rect[1];
				uv[2] = rect[2];
				uv[3] = rect[1];
				uv[4] = rect[2];
				uv[5] = rect[3];
				uv[6] = rect[0];
				uv[7] = rect[3];
				int head = color(p, sys, headAlpha.get(p, sys)), tail = color(p, sys, tailAlpha.get(p, sys));
				argb[0] = argb[1] = head;
				argb[2] = argb[3] = tail;
				sink.quad(texture, additive, xyz, uv, argb, brightness(p, sys));
			}
		}

		private void put(int i, float x, float y, float z) {
			xyz[i * 3] = x;
			xyz[i * 3 + 1] = y;
			xyz[i * 3 + 2] = z;
		}
	}

	// ---- ropes -----------------------------------------------------------------------------------

	static final class Ropes extends Renderer {
		private final FloatInput vWorldSize, vScrollRate, vOffset;
		private final boolean reverse, closed;
		private final float[] xyz = new float[12], uv = new float[8], side = new float[3], prevSide = new float[3];
		private final int[] argb = new int[4];
		private final List<Particle> ordered = new ArrayList<>();

		Ropes(Map<String, Object> m, Compiler c) {
			super(m, c);
			vWorldSize = Inputs.floatInput(m.get("m_flTextureVWorldSize"), 10, c);
			vScrollRate = Inputs.floatInput(m.get("m_flTextureVScrollRate"), 0, c);
			vOffset = Inputs.floatInput(m.get("m_flTextureVOffset"), 0, c);
			reverse = Kv3.b(m, "m_bReverseOrder", false);
			closed = Kv3.b(m, "m_bClosedLoop", false);
		}

		@Override
		void draw(FxSystem sys, FxPack pack, View view, Sink sink) {
			ordered.clear();
			ordered.addAll(sys.particles);
			if (ordered.size() < 2) return;
			ordered.sort(Comparator.comparingInt(p -> p.uniqueId));
			if (reverse) java.util.Collections.reverse(ordered);
			if (closed) ordered.add(ordered.get(0));
			float size = vWorldSize.get(null, sys);
			float inv = size == 0 ? 0 : 1 / size;
			float v = vOffset.get(null, sys) + vScrollRate.get(null, sys) * sys.age * inv;
			int n = ordered.size();
			sideAt(0, view, prevSide);
			for (int i = 0; i < n - 1; i++) {
				Particle p0 = ordered.get(i), p1 = ordered.get(i + 1);
				sideAt(i + 1, view, side);
				float r0 = p0.radius * radiusScale.get(p0, sys), r1 = p1.radius * radiusScale.get(p1, sys);
				float seg = Inputs.dist(p0.pos, p1.pos);
				float v1 = v + seg * inv;
				put(0, p0.pos, prevSide, -r0);
				put(1, p0.pos, prevSide, r0);
				put(2, p1.pos, side, r1);
				put(3, p1.pos, side, -r1);
				uv[0] = 0;
				uv[1] = v;
				uv[2] = 1;
				uv[3] = v;
				uv[4] = 1;
				uv[5] = v1;
				uv[6] = 0;
				uv[7] = v1;
				argb[0] = argb[1] = color(p0, sys, 1);
				argb[2] = argb[3] = color(p1, sys, 1);
				if (((argb[0] | argb[2]) >>> 24) != 0) sink.quad(texture, additive, xyz, uv, argb, (brightness(p0, sys) + brightness(p1, sys)) / 2);
				v = v1;
				System.arraycopy(side, 0, prevSide, 0, 3);
			}
		}

		/** The ribbon's sideways direction at a node: across its run, facing the camera. */
		private void sideAt(int i, View view, float[] out) {
			int n = ordered.size();
			Particle a = ordered.get(Math.max(0, i - 1)), b = ordered.get(Math.min(n - 1, i + 1));
			float dx = b.pos[0] - a.pos[0], dy = b.pos[1] - a.pos[1], dz = b.pos[2] - a.pos[2];
			float[] at = ordered.get(i).pos;
			float tx = view.eye[0] - at[0], ty = view.eye[1] - at[1], tz = view.eye[2] - at[2];
			out[0] = dy * tz - dz * ty;
			out[1] = dz * tx - dx * tz;
			out[2] = dx * ty - dy * tx;
			Functions.normalize(out);
		}

		private void put(int i, float[] o, float[] s, float r) {
			xyz[i * 3] = o[0] + s[0] * r;
			xyz[i * 3 + 1] = o[1] + s[1] * r;
			xyz[i * 3 + 2] = o[2] + s[2] * r;
		}
	}

	// ---- helpers ---------------------------------------------------------------------------------

	static void set(float[] v, float x, float y, float z) {
		v[0] = x;
		v[1] = y;
		v[2] = z;
	}

	/** Two axes perpendicular to n. */
	static void basis(float[] n, float[] a, float[] b) {
		float nx = n[0], ny = n[1], nz = n[2];
		if (Math.abs(nz) < 0.9f) set(a, -ny, nx, 0);
		else set(a, 0, -nz, ny);
		Functions.normalize(a);
		set(b, ny * a[2] - nz * a[1], nz * a[0] - nx * a[2], nx * a[1] - ny * a[0]);
		Functions.normalize(b);
	}

	static float toLinear(float c) {
		return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
	}

	static float toSrgb(float c) {
		c = Inputs.clamp01(c);
		return c <= 0.0031308f ? c * 12.92f : 1.055f * (float) Math.pow(c, 1 / 2.4f) - 0.055f;
	}

	/** Draws a whole effect tree. */
	public static void draw(FxSystem root, FxPack pack, View view, Sink sink) {
		root.forEach(sys -> {
			for (Renderer r : sys.renderers) r.draw(sys, pack, view, sink);
		});
	}
}
