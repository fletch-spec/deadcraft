package dev.deadcraft.client.fx;

/**
 * A control point an effect is placed by: a position in the effect's frame (Source units) and an orientation
 * (forward, left, up), as Deadlock attaches effects to a muzzle, a bone or a hit.
 */
public final class ControlPoint {
	final float[] pos = new float[3], vel = new float[3], prevPos = new float[3];
	final float[] fwd = {1, 0, 0}, left = {0, 1, 0}, up = {0, 0, 1};
	boolean set;

	public void position(float x, float y, float z) {
		pos[0] = x;
		pos[1] = y;
		pos[2] = z;
		set = true;
	}

	/** The orientation from a forward direction and a rough up (normalised and made perpendicular here). */
	public void orient(float fx, float fy, float fz, float ux, float uy, float uz) {
		float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
		if (fl < 1e-6f) return;
		fx /= fl;
		fy /= fl;
		fz /= fl;
		// left = up x forward
		float lx = uy * fz - uz * fy, ly = uz * fx - ux * fz, lz = ux * fy - uy * fx;
		float ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
		if (ll < 1e-6f) {
			// Forward is along the up given: any perpendicular will do.
			lx = -fy;
			ly = fx;
			lz = 0;
			ll = (float) Math.sqrt(lx * lx + ly * ly);
			if (ll < 1e-6f) {
				lx = 0;
				ly = 1;
				ll = 1;
			}
		}
		lx /= ll;
		ly /= ll;
		lz /= ll;
		fwd[0] = fx;
		fwd[1] = fy;
		fwd[2] = fz;
		left[0] = lx;
		left[1] = ly;
		left[2] = lz;
		// up = forward x left
		up[0] = fy * lz - fz * ly;
		up[1] = fz * lx - fx * lz;
		up[2] = fx * ly - fy * lx;
	}

	/** A direction in the point's own frame (x forward, y left, z up) to the effect's frame. */
	void toWorld(float x, float y, float z, float[] out) {
		float ox = fwd[0] * x + left[0] * y + up[0] * z, oy = fwd[1] * x + left[1] * y + up[1] * z, oz = fwd[2] * x + left[2] * y + up[2] * z;
		out[0] = ox;
		out[1] = oy;
		out[2] = oz;
	}

	void copyFrom(ControlPoint o) {
		System.arraycopy(o.pos, 0, pos, 0, 3);
		System.arraycopy(o.vel, 0, vel, 0, 3);
		System.arraycopy(o.prevPos, 0, prevPos, 0, 3);
		System.arraycopy(o.fwd, 0, fwd, 0, 3);
		System.arraycopy(o.left, 0, left, 0, 3);
		System.arraycopy(o.up, 0, up, 0, 3);
		set = o.set;
	}
}
