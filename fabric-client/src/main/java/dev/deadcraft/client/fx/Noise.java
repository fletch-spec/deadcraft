package dev.deadcraft.client.fx;

/** Smooth value noise for the effects' noise inputs and forces (not bit-exact with Source 2's). */
final class Noise {
	private Noise() {
	}

	/** Smooth noise in -1..1. */
	static float value3(float x, float y, float z) {
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
		float fx = fade(x - x0), fy = fade(y - y0), fz = fade(z - z0);
		float c000 = hash(x0, y0, z0), c100 = hash(x0 + 1, y0, z0), c010 = hash(x0, y0 + 1, z0), c110 = hash(x0 + 1, y0 + 1, z0);
		float c001 = hash(x0, y0, z0 + 1), c101 = hash(x0 + 1, y0, z0 + 1), c011 = hash(x0, y0 + 1, z0 + 1), c111 = hash(x0 + 1, y0 + 1, z0 + 1);
		float a = lerp(lerp(c000, c100, fx), lerp(c010, c110, fx), fy);
		float b = lerp(lerp(c001, c101, fx), lerp(c011, c111, fx), fy);
		return lerp(a, b, fz);
	}

	/** A divergence-free swirl: the curl of three offset noise fields. */
	static void curl3(float x, float y, float z, float[] out) {
		float e = 0.1f;
		float dzdy = (n(2, x, y + e, z) - n(2, x, y - e, z)), dydz = (n(1, x, y, z + e) - n(1, x, y, z - e));
		float dxdz = (n(0, x, y, z + e) - n(0, x, y, z - e)), dzdx = (n(2, x + e, y, z) - n(2, x - e, y, z));
		float dydx = (n(1, x + e, y, z) - n(1, x - e, y, z)), dxdy = (n(0, x, y + e, z) - n(0, x, y - e, z));
		float s = 1 / (2 * e);
		out[0] = (dzdy - dydz) * s;
		out[1] = (dxdz - dzdx) * s;
		out[2] = (dydx - dxdy) * s;
	}

	private static float n(int field, float x, float y, float z) {
		return value3(x + field * 31.7f, y + field * 17.3f, z + field * 11.1f);
	}

	private static float fade(float t) {
		return t * t * (3 - 2 * t);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static float hash(int x, int y, int z) {
		int h = x * 374761393 + y * 668265263 + z * 1274126177;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= h >>> 16;
		return (h & 0xFFFFFF) / (float) 0x800000 - 1;
	}
}
