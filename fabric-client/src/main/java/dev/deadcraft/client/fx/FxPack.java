package dev.deadcraft.client.fx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A hero's visual effects, exported from the player's own Deadlock install by {@code hero-export <hero> --fx}
 * (format in tools/hero-export/FxPack.cs; never committed): Deadlock's particle systems as KV3 text, the
 * textures they draw with, which effect each ability names, and the model's attachment points.
 */
public final class FxPack implements AutoCloseable {
	static final Logger LOG = LoggerFactory.getLogger("deadcraft");

	public static final int VERSION = 1;

	private final ZipFile zip;
	/** The model's particle scale (Celeste's 0.78). */
	public final float scale;
	/** "ability key" -> effect path, e.g. "citadel_weapon_unicorn_set m_mapWeaponInfos.primary.m_szMuzzleFlashEffectName". */
	public final Map<String, String> effects = new LinkedHashMap<>();
	/** The model's own effects (Celeste's horn sparkle). */
	public final List<String> ambient = new ArrayList<>();
	public final Map<String, Attachment> attachments = new HashMap<>();
	/** Rest pose of the attachments' bones and their ancestors: name -> parent, position (3), rotation (xyzw). */
	private final Map<String, Bone> bones = new HashMap<>();
	private final Map<String, FxSystem.Definition> definitions = new HashMap<>();
	private final Map<String, Sheet> sheets = new HashMap<>();
	private final Set<String> reported = Collections.synchronizedSet(new HashSet<>());

	/** A point on the model: its bone, and its offset and rotation in the bone's frame (Source units). */
	public record Attachment(String name, String bone, float[] offset, float[] rotation) {
	}

	private record Bone(String parent, float[] position, float[] rotation) {
	}

	/**
	 * An attachment's offset on the nearest of its bone and that bone's ancestors that {@code has} (the drawn
	 * model leaves out leaf bones such as head_end): each missing bone's rest offset from its parent is folded
	 * in. Null when no bone in the chain is there.
	 */
	public Attachment onModel(Attachment a, java.util.function.Predicate<String> has) {
		String bone = a.bone();
		float[] off = a.offset().clone();
		for (int guard = 0; guard < 64 && bone != null; guard++) {
			if (has.test(bone)) return new Attachment(a.name(), bone, off, a.rotation());
			Bone b = bones.get(bone);
			if (b == null) return null;
			float[] q = b.rotation();
			float[] r = rotate(q, off);
			off = new float[] {b.position()[0] + r[0], b.position()[1] + r[1], b.position()[2] + r[2]};
			bone = b.parent().equals("-") ? null : b.parent();
		}
		return null;
	}

	/** Rotates v by the unit quaternion q (x, y, z, w). */
	static float[] rotate(float[] q, float[] v) {
		float qx = q[0], qy = q[1], qz = q[2], qw = q[3];
		// t = 2 * cross(q.xyz, v); v' = v + w * t + cross(q.xyz, t)
		float tx = 2 * (qy * v[2] - qz * v[1]), ty = 2 * (qz * v[0] - qx * v[2]), tz = 2 * (qx * v[1] - qy * v[0]);
		return new float[] {v[0] + qw * tx + (qy * tz - qz * ty), v[1] + qw * ty + (qz * tx - qx * tz), v[2] + qw * tz + (qx * ty - qy * tx)};
	}

	/** A texture's sprite sheet: its sequences of frames (each u0, v0, u1, v1). */
	public record Sheet(List<Sequence> sequences) {
	}

	public record Sequence(boolean clamp, float totalTime, List<float[]> frames) {
	}

	private FxPack(ZipFile zip) throws IOException {
		this.zip = zip;
		float s = 1;
		for (String line : text("manifest.txt").split("\n")) {
			String[] w = line.trim().split(" ");
			switch (w[0]) {
				case "version" -> {
					if (Integer.parseInt(w[1]) != VERSION) throw new IOException("effects pack version " + w[1] + ", expected " + VERSION + ": re-run hero-export --fx");
				}
				case "scale" -> s = Float.parseFloat(w[1]);
				case "effect" -> {
					if (w.length >= 4) effects.put(w[1] + " " + w[2], w[3]);
				}
				case "ambient" -> {
					if (w.length >= 2) ambient.add(w[1]);
				}
				default -> { }
			}
		}
		scale = s;
		for (String line : text("attachments.txt").split("\n")) {
			String[] w = line.trim().split(" ");
			if (w.length < 9 || attachments.containsKey(w[0])) continue;
			attachments.put(w[0], new Attachment(w[0], w[1],
				new float[] {Float.parseFloat(w[2]), Float.parseFloat(w[3]), Float.parseFloat(w[4])},
				new float[] {Float.parseFloat(w[5]), Float.parseFloat(w[6]), Float.parseFloat(w[7]), Float.parseFloat(w[8])}));
		}
		for (String line : text("bones.txt").split("\n")) {
			String[] w = line.trim().split(" ");
			if (w.length < 9) continue;
			bones.put(w[0], new Bone(w[1], new float[] {Float.parseFloat(w[2]), Float.parseFloat(w[3]), Float.parseFloat(w[4])},
				new float[] {Float.parseFloat(w[5]), Float.parseFloat(w[6]), Float.parseFloat(w[7]), Float.parseFloat(w[8])}));
		}
	}

	public static FxPack load(Path file) throws IOException {
		return new FxPack(new ZipFile(file.toFile()));
	}

	private String text(String name) throws IOException {
		ZipEntry e = zip.getEntry(name);
		if (e == null) return "";
		try (InputStream in = zip.getInputStream(e)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** An effect named by an ability (null if this hero has none there). */
	public String effect(String ability, String key) {
		return effects.get(ability + " " + key);
	}

	public synchronized boolean has(String path) {
		return path != null && (definitions.containsKey(path) || zip.getEntry("effects/" + path + ".kv3") != null);
	}

	/** A particle system's definition, parsed once (null if the pack doesn't hold it). */
	public synchronized FxSystem.Definition definition(String path) {
		if (path == null) return null;
		if (definitions.containsKey(path)) return definitions.get(path);
		FxSystem.Definition def = null;
		try {
			String t = text("effects/" + path + ".kv3");
			if (!t.isEmpty()) def = new FxSystem.Definition(path, Kv3.parse(t));
		} catch (IOException | RuntimeException e) {
			LOG.warn("Deadcraft: effect {} unreadable: {}", path, e.toString());
		}
		definitions.put(path, def);
		return def;
	}

	/** A texture's PNG bytes (null if absent). */
	public byte[] texturePng(String path) {
		if (path == null) return null;
		try {
			ZipEntry e = zip.getEntry("textures/" + path + ".png");
			if (e == null) return null;
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		} catch (IOException e) {
			return null;
		}
	}

	/** A texture's sprite sheet, or null when it's a single image. */
	public synchronized Sheet sheet(String texture) {
		if (texture == null) return null;
		if (sheets.containsKey(texture)) return sheets.get(texture);
		Sheet sheet = null;
		try {
			String t = text("textures/" + texture + ".sheet");
			if (!t.isEmpty()) {
				List<Sequence> seqs = new ArrayList<>();
				List<float[]> frames = null;
				for (String line : t.split("\n")) {
					String[] w = line.trim().split(" ");
					if (w[0].equals("sequence") && w.length >= 4) {
						frames = new ArrayList<>();
						seqs.add(new Sequence(w[2].equals("1"), Float.parseFloat(w[3]), frames));
					} else if (w[0].equals("frame") && w.length >= 6 && frames != null) {
						frames.add(new float[] {Float.parseFloat(w[2]), Float.parseFloat(w[3]), Float.parseFloat(w[4]), Float.parseFloat(w[5])});
					}
				}
				sheet = new Sheet(seqs);
			}
		} catch (IOException | RuntimeException e) {
			LOG.warn("Deadcraft: sprite sheet {} unreadable: {}", texture, e.toString());
		}
		sheets.put(texture, sheet);
		return sheet;
	}

	/** Logs, once per system, what parts of it aren't played. */
	void reportUnsupported(String path, Set<String> what) {
		if (what.isEmpty() || !reported.add(path)) return;
		LOG.info("Deadcraft: effect {} plays without: {}", path, String.join(", ", what));
	}

	@Override
	public void close() throws IOException {
		zip.close();
	}
}
