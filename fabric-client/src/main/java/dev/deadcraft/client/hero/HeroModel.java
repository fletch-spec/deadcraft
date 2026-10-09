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
 * A hero packed by {@code tools/hero-export} ({@code <hero>.dchero}, format in its HeroPack.cs):
 * one skinned mesh in glTF axes and metres, split by material, and animation clips baked to
 * per-joint 3x4 skinning matrices at a fixed rate.
 */
public final class HeroModel {
	public static final int VERSION = 1;

	public record Material(String name, String texture, int[] indices) {}

	public record Clip(String name, float fps, int frames, boolean loop, float[] matrices) {
		public float duration() {
			return loop ? frames / fps : (frames - 1) / fps;
		}
	}

	public final List<Material> materials;
	public final float[] positions, normals, uvs, weights;  // 3, 3, 2, 4 per vertex
	public final int[] colours;  // RGBA8
	public final int[] joints;   // 4 per vertex
	public final int jointCount;
	public final Map<String, Clip> clips;
	/** Where the pack was read from; textures sit beside it. */
	public final Path directory;

	private HeroModel(Path directory, List<Material> materials, float[] positions, float[] normals, float[] uvs, int[] colours,
		int[] joints, float[] weights, int jointCount, Map<String, Clip> clips) {
		this.directory = directory;
		this.materials = materials;
		this.positions = positions;
		this.normals = normals;
		this.uvs = uvs;
		this.colours = colours;
		this.joints = joints;
		this.weights = weights;
		this.jointCount = jointCount;
		this.clips = clips;
	}

	public int vertexCount() {
		return positions.length / 3;
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
		int nj = b.getInt();
		int nc = b.getInt();
		Map<String, Clip> clips = new LinkedHashMap<>();
		for (int i = 0; i < nc; i++) {
			String name = string(b);
			float fps = b.getFloat();
			int frames = b.getInt();
			boolean loop = b.get() != 0;
			float[] m = new float[frames * nj * 12];
			b.asFloatBuffer().get(m);
			b.position(b.position() + m.length * 4);
			clips.put(name, new Clip(name, fps, frames, loop, m));
		}
		return new HeroModel(directory, materials, p, n, uv, c, j, w, nj, clips);
	}

	private static String string(ByteBuffer b) {
		byte[] s = new byte[b.getInt()];
		b.get(s);
		return new String(s, StandardCharsets.UTF_8);
	}

	/**
	 * Adds {@code weight} times clip {@code clip}'s matrices at {@code time} seconds (looping or held
	 * at the end) into {@code out} (jointCount * 12 floats), interpolating between frames.
	 */
	public void accumulate(Clip clip, float time, float weight, float[] out) {
		if (weight <= 0) return;
		float f = time * clip.fps();
		int frames = clip.frames();
		int f0, f1;
		float a;
		if (clip.loop()) {
			f = ((f % frames) + frames) % frames;
			f0 = (int) f;
			f1 = (f0 + 1) % frames;
		} else {
			f = Math.max(0, Math.min(f, frames - 1));
			f0 = (int) f;
			f1 = Math.min(f0 + 1, frames - 1);
		}
		a = f - f0;
		float[] m = clip.matrices();
		int stride = jointCount * 12, o0 = f0 * stride, o1 = f1 * stride;
		float w0 = weight * (1 - a), w1 = weight * a;
		for (int i = 0; i < stride; i++) out[i] += m[o0 + i] * w0 + m[o1 + i] * w1;
	}
}
