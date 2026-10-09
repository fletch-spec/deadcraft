package dev.deadcraft.client.hero;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A hero packed by {@code tools/hero-export} ({@code <hero>.dchero}, format in its HeroPack.cs): one
 * skinned mesh in glTF axes and metres split by material, the skeleton (parents first) with its rest
 * pose, the skinned joints' inverse binds, and animation clips as every node's local transform per
 * frame. Transforms are 10 floats: translation xyz, rotation quaternion xyzw, scale xyz.
 *
 * <p>Clips are sampled and blended per node into a {@link Pose}; {@link #skin} then builds the
 * skeleton and the skinning matrices, with an optional turn of the upper body (to the camera).
 */
public final class HeroModel {
	public static final int VERSION = 2;
	static final int TRS = 10;

	public record Material(String name, String texture, int[] indices) {}

	public record Clip(String name, float fps, int frames, boolean loop, float[] locals) {
		public float duration() {
			return loop ? frames / fps : (frames - 1) / fps;
		}
	}

	public final List<Material> materials;
	public final float[] positions, normals, uvs, weights;  // 3, 3, 2, 4 per vertex
	public final int[] colours;  // RGBA8
	public final int[] joints;   // 4 per vertex, into the skinned joints
	public final String[] nodeNames;
	public final int[] parents;
	public final float[] rest;   // TRS per node
	public final int[] skinNodes;
	public final float[] inverseBinds;  // 12 per skinned joint, 3x4 rows
	public final Map<String, Clip> clips;
	/** Where the pack was read from; textures sit beside it. */
	public final Path directory;

	private HeroModel(Path directory, List<Material> materials, float[] positions, float[] normals, float[] uvs, int[] colours,
		int[] joints, float[] weights, String[] nodeNames, int[] parents, float[] rest, int[] skinNodes, float[] inverseBinds,
		Map<String, Clip> clips) {
		this.directory = directory;
		this.materials = materials;
		this.positions = positions;
		this.normals = normals;
		this.uvs = uvs;
		this.colours = colours;
		this.joints = joints;
		this.weights = weights;
		this.nodeNames = nodeNames;
		this.parents = parents;
		this.rest = rest;
		this.skinNodes = skinNodes;
		this.inverseBinds = inverseBinds;
		this.clips = clips;
	}

	public int vertexCount() {
		return positions.length / 3;
	}

	public int nodeCount() {
		return parents.length;
	}

	public int skinnedJointCount() {
		return skinNodes.length;
	}

	public int node(String name) {
		for (int i = 0; i < nodeNames.length; i++) if (nodeNames[i].equals(name)) return i;
		return -1;
	}

	public static HeroModel load(Path file) throws IOException {
		return read(ByteBuffer.wrap(Files.readAllBytes(file)), file.toAbsolutePath().getParent());
	}

	static HeroModel read(ByteBuffer b, Path directory) throws IOException {
		b.order(ByteOrder.LITTLE_ENDIAN);
		byte[] magic = new byte[4];
		b.get(magic);
		if (!"DCHM".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IOException("not a hero pack");
		int version = b.getInt();
		if (version != VERSION) throw new IOException("hero pack version " + version + ", expected " + VERSION + ": rerun hero-export");
		int nm = b.getInt();
		String[] names = new String[nm], textures = new String[nm];
		for (int i = 0; i < nm; i++) {
			names[i] = string(b);
			textures[i] = string(b);
		}
		int nv = b.getInt();
		float[] p = new float[nv * 3], n = new float[nv * 3], uv = new float[nv * 2], w = new float[nv * 4];
		int[] c = new int[nv], j = new int[nv * 4];
		for (int v = 0; v < nv; v++) {
			for (int k = 0; k < 3; k++) p[v * 3 + k] = b.getFloat();
			for (int k = 0; k < 3; k++) n[v * 3 + k] = b.getFloat();
			uv[v * 2] = b.getFloat();
			uv[v * 2 + 1] = b.getFloat();
			c[v] = b.getInt();
			for (int k = 0; k < 4; k++) j[v * 4 + k] = Short.toUnsignedInt(b.getShort());
			for (int k = 0; k < 4; k++) w[v * 4 + k] = b.getFloat();
		}
		List<Material> materials = new ArrayList<>(nm);
		for (int i = 0; i < nm; i++) {
			int[] idx = new int[b.getInt() * 3];
			for (int k = 0; k < idx.length; k++) idx[k] = b.getInt();
			materials.add(new Material(names[i], textures[i], idx));
		}
		int nodes = b.getInt();
		String[] nodeNames = new String[nodes];
		int[] parents = new int[nodes];
		float[] rest = new float[nodes * TRS];
		for (int i = 0; i < nodes; i++) {
			nodeNames[i] = string(b);
			parents[i] = b.getInt();
			if (parents[i] >= i) throw new IOException("hero pack nodes out of order");
			for (int k = 0; k < TRS; k++) rest[i * TRS + k] = b.getFloat();
		}
		int skinned = b.getInt();
		int[] skinNodes = new int[skinned];
		float[] inverseBinds = new float[skinned * 12];
		for (int i = 0; i < skinned; i++) {
			skinNodes[i] = b.getInt();
			for (int k = 0; k < 12; k++) inverseBinds[i * 12 + k] = b.getFloat();
		}
		int nc = b.getInt();
		Map<String, Clip> clips = new LinkedHashMap<>();
		for (int i = 0; i < nc; i++) {
			String name = string(b);
			float fps = b.getFloat();
			int frames = b.getInt();
			boolean loop = b.get() != 0;
			float[] locals = new float[frames * nodes * TRS];
			b.asFloatBuffer().get(locals);
			b.position(b.position() + locals.length * 4);
			clips.put(name, new Clip(name, fps, frames, loop, locals));
		}
		return new HeroModel(directory, materials, p, n, uv, c, j, w, nodeNames, parents, rest, skinNodes, inverseBinds, clips);
	}

	private static String string(ByteBuffer b) {
		byte[] s = new byte[b.getInt()];
		b.get(s);
		return new String(s, StandardCharsets.UTF_8);
	}

	/** Every node's local transform, blended from clips. */
	public final class Pose {
		final float[] trs = new float[nodeCount() * TRS];
		private float total;

		public void clear() {
			java.util.Arrays.fill(trs, 0);
			total = 0;
		}

		/**
		 * Adds {@code weight} of clip {@code clip} at {@code time} seconds (looping, or held at the end),
		 * interpolating between frames. Rotations are summed (see {@link #addRotation}) and normalised
		 * in {@link #finish}.
		 */
		public void add(Clip clip, float time, float weight) {
			if (weight <= 0) return;
			int frames = clip.frames();
			float f = time * clip.fps();
			int f0, f1;
			if (clip.loop()) {
				f = ((f % frames) + frames) % frames;
				f0 = (int) f;
				f1 = (f0 + 1) % frames;
			} else {
				f = Math.max(0, Math.min(f, frames - 1));
				f0 = (int) f;
				f1 = Math.min(f0 + 1, frames - 1);
			}
			float a = f - f0;
			float[] l = clip.locals();
			int stride = nodeCount() * TRS, o0 = f0 * stride, o1 = f1 * stride;
			for (int n = 0, nodes = nodeCount(); n < nodes; n++) {
				int i = n * TRS, i0 = o0 + i, i1 = o1 + i;
				for (int k = 0; k < 3; k++) trs[i + k] += weight * (l[i0 + k] + (l[i1 + k] - l[i0 + k]) * a);
				for (int k = 7; k < 10; k++) trs[i + k] += weight * (l[i0 + k] + (l[i1 + k] - l[i0 + k]) * a);
				addRotation(i, l, i0, i1, a, weight);
			}
			total += weight;
		}

		/**
		 * q and -q are the same rotation, so every pair being mixed is first put on the same side: the
		 * next frame on the side of this one, and the result on the side of what this joint has gathered
		 * so far (the rest rotation for the first clip). Choosing each frame's side against the rest pose
		 * instead flips joints that swing far from rest (skirt panels, landings) between frames, and the
		 * mix then passes through no rotation at all: the joint whips round.
		 */
		private void addRotation(int i, float[] l, int i0, int i1, float a, float weight) {
			float d01 = 0;
			for (int k = 3; k < 7; k++) d01 += l[i0 + k] * l[i1 + k];
			float s1 = d01 < 0 ? -1 : 1;
			float q0 = l[i0 + 3] + (s1 * l[i1 + 3] - l[i0 + 3]) * a;
			float q1 = l[i0 + 4] + (s1 * l[i1 + 4] - l[i0 + 4]) * a;
			float q2 = l[i0 + 5] + (s1 * l[i1 + 5] - l[i0 + 5]) * a;
			float q3 = l[i0 + 6] + (s1 * l[i1 + 6] - l[i0 + 6]) * a;
			float[] ref = total > 0 ? trs : rest;
			float d = ref[i + 3] * q0 + ref[i + 4] * q1 + ref[i + 5] * q2 + ref[i + 6] * q3;
			float s = (d < 0 ? -1 : 1) * weight;
			trs[i + 3] += s * q0;
			trs[i + 4] += s * q1;
			trs[i + 5] += s * q2;
			trs[i + 6] += s * q3;
		}

		/** Divides out the summed weights and normalises rotations. With nothing added, the rest pose. */
		public void finish() {
			if (total <= 0) {
				System.arraycopy(rest, 0, trs, 0, trs.length);
				return;
			}
			float inv = 1 / total;
			for (int n = 0, nodes = nodeCount(); n < nodes; n++) {
				int i = n * TRS;
				for (int k = 0; k < 3; k++) trs[i + k] *= inv;
				for (int k = 7; k < 10; k++) trs[i + k] *= inv;
				normalise(trs, i + 3);
			}
			total = 1;
		}

		/** This pose blended towards {@code other} by {@code a} (0 = this, 1 = other), into this. */
		public void blendTowards(Pose other, float a) {
			for (int n = 0, nodes = nodeCount(); n < nodes; n++) {
				int i = n * TRS;
				for (int k = 0; k < 3; k++) trs[i + k] += (other.trs[i + k] - trs[i + k]) * a;
				for (int k = 7; k < 10; k++) trs[i + k] += (other.trs[i + k] - trs[i + k]) * a;
				float d = 0;
				for (int k = 3; k < 7; k++) d += trs[i + k] * other.trs[i + k];
				float s = d < 0 ? -1 : 1;
				for (int k = 3; k < 7; k++) trs[i + k] += (s * other.trs[i + k] - trs[i + k]) * a;
				normalise(trs, i + 3);
			}
		}

		public void copyFrom(Pose other) {
			System.arraycopy(other.trs, 0, trs, 0, trs.length);
			total = other.total;
		}
	}

	public Pose newPose() {
		return new Pose();
	}

	private static void normalise(float[] q, int at) {
		float len = (float) Math.sqrt(q[at] * q[at] + q[at + 1] * q[at + 1] + q[at + 2] * q[at + 2] + q[at + 3] * q[at + 3]);
		if (len < 1e-8f) {
			q[at] = q[at + 1] = q[at + 2] = 0;
			q[at + 3] = 1;
			return;
		}
		for (int k = 0; k < 4; k++) q[at + k] /= len;
	}

	/**
	 * Builds the skeleton from {@code pose} and writes each skinned joint's 3x4 matrix into
	 * {@code out}. {@code twist} adds a turn about the model's up axis (glTF +Y), in radians, to the
	 * nodes listed with their share of it ({@code twistNodes}/{@code twistShares}), each about its own
	 * position: children inherit it, so the shares add up along a chain such as spine to head.
	 */
	public void skin(Pose pose, float[] world, float twist, int[] twistNodes, float[] twistShares, float[] out) {
		float[] t = pose.trs;
		for (int n = 0, nodes = nodeCount(); n < nodes; n++) {
			int i = n * TRS, o = n * 12;
			float x = t[i + 3], y = t[i + 4], z = t[i + 5], w = t[i + 6];
			float sx = t[i + 7], sy = t[i + 8], sz = t[i + 9];
			// Local = translation * rotation * scale, as 3x4 rows.
			float r00 = 1 - 2 * (y * y + z * z), r01 = 2 * (x * y - z * w), r02 = 2 * (x * z + y * w);
			float r10 = 2 * (x * y + z * w), r11 = 1 - 2 * (x * x + z * z), r12 = 2 * (y * z - x * w);
			float r20 = 2 * (x * z - y * w), r21 = 2 * (y * z + x * w), r22 = 1 - 2 * (x * x + y * y);
			float l00 = r00 * sx, l01 = r01 * sy, l02 = r02 * sz, l03 = t[i];
			float l10 = r10 * sx, l11 = r11 * sy, l12 = r12 * sz, l13 = t[i + 1];
			float l20 = r20 * sx, l21 = r21 * sy, l22 = r22 * sz, l23 = t[i + 2];
			int p = parents[n];
			if (p < 0) {
				world[o] = l00; world[o + 1] = l01; world[o + 2] = l02; world[o + 3] = l03;
				world[o + 4] = l10; world[o + 5] = l11; world[o + 6] = l12; world[o + 7] = l13;
				world[o + 8] = l20; world[o + 9] = l21; world[o + 10] = l22; world[o + 11] = l23;
			} else {
				mul(world, p * 12, l00, l01, l02, l03, l10, l11, l12, l13, l20, l21, l22, l23, world, o);
			}
			if (twist != 0) {
				for (int k = 0; k < twistNodes.length; k++) {
					if (twistNodes[k] == n) turnAboutUp(world, o, twist * twistShares[k]);
				}
			}
		}
		for (int s = 0, joints = skinnedJointCount(); s < joints; s++) {
			int ib = s * 12;
			float[] m = inverseBinds;
			mul(world, skinNodes[s] * 12, m[ib], m[ib + 1], m[ib + 2], m[ib + 3], m[ib + 4], m[ib + 5], m[ib + 6], m[ib + 7],
				m[ib + 8], m[ib + 9], m[ib + 10], m[ib + 11], out, s * 12);
		}
	}

	/** out[o..] = a[ao..] * b, both 3x4 with an implied (0, 0, 0, 1) last row. */
	private static void mul(float[] a, int ao, float b00, float b01, float b02, float b03, float b10, float b11, float b12, float b13,
		float b20, float b21, float b22, float b23, float[] out, int o) {
		float a00 = a[ao], a01 = a[ao + 1], a02 = a[ao + 2], a03 = a[ao + 3];
		float a10 = a[ao + 4], a11 = a[ao + 5], a12 = a[ao + 6], a13 = a[ao + 7];
		float a20 = a[ao + 8], a21 = a[ao + 9], a22 = a[ao + 10], a23 = a[ao + 11];
		out[o] = a00 * b00 + a01 * b10 + a02 * b20;
		out[o + 1] = a00 * b01 + a01 * b11 + a02 * b21;
		out[o + 2] = a00 * b02 + a01 * b12 + a02 * b22;
		out[o + 3] = a00 * b03 + a01 * b13 + a02 * b23 + a03;
		out[o + 4] = a10 * b00 + a11 * b10 + a12 * b20;
		out[o + 5] = a10 * b01 + a11 * b11 + a12 * b21;
		out[o + 6] = a10 * b02 + a11 * b12 + a12 * b22;
		out[o + 7] = a10 * b03 + a11 * b13 + a12 * b23 + a13;
		out[o + 8] = a20 * b00 + a21 * b10 + a22 * b20;
		out[o + 9] = a20 * b01 + a21 * b11 + a22 * b21;
		out[o + 10] = a20 * b02 + a21 * b12 + a22 * b22;
		out[o + 11] = a20 * b03 + a21 * b13 + a22 * b23 + a23;
	}

	/** Pre-multiplies the 3x4 at o by a turn of {@code angle} about +Y through its own origin. */
	private static void turnAboutUp(float[] m, int o, float angle) {
		float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
		float px = m[o + 3], pz = m[o + 11];
		for (int col = 0; col < 3; col++) {
			float x = m[o + col], z = m[o + 8 + col];
			m[o + col] = c * x + s * z;
			m[o + 8 + col] = -s * x + c * z;
		}
		// The origin stays put: the rotation is about it.
		m[o + 3] = px;
		m[o + 11] = pz;
	}
}
