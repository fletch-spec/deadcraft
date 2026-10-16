package dev.deadcraft.client.fx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * The player's effect settings ({@code /deadcraft fx}), saved in {@code config/deadcraft-fx.properties}: bloom
 * strength (the glow spread around bright effects; 0 is off), brightness and size multipliers on every effect.
 */
public final class FxSettings {
	public static final float DEFAULT_BLOOM = 0.5f, DEFAULT_BRIGHTNESS = 1, DEFAULT_SIZE = 1;
	public static final float MAX_BLOOM = 8, MIN_SCALE = 0.25f, MAX_SCALE = 4;

	static float bloom = DEFAULT_BLOOM, brightness = DEFAULT_BRIGHTNESS, size = DEFAULT_SIZE;
	private static Path file;

	private FxSettings() {
	}

	public static float bloom() {
		return bloom;
	}

	public static float brightness() {
		return brightness;
	}

	public static float size() {
		return size;
	}

	/** Reads the saved settings (defaults when there are none). */
	static void load(Path from) {
		file = from;
		Properties p = new Properties();
		try (var in = Files.newBufferedReader(from)) {
			p.load(in);
			bloom = clamp(Float.parseFloat(p.getProperty("bloom", "" + DEFAULT_BLOOM)), 0, MAX_BLOOM);
			brightness = clamp(Float.parseFloat(p.getProperty("brightness", "" + DEFAULT_BRIGHTNESS)), MIN_SCALE, MAX_SCALE);
			size = clamp(Float.parseFloat(p.getProperty("size", "" + DEFAULT_SIZE)), MIN_SCALE, MAX_SCALE);
		} catch (IOException | NumberFormatException e) {
			// none saved yet
		}
	}

	private static void save() {
		if (file == null) return;
		Properties p = new Properties();
		p.setProperty("bloom", Float.toString(bloom));
		p.setProperty("brightness", Float.toString(brightness));
		p.setProperty("size", Float.toString(size));
		try (var out = Files.newBufferedWriter(file)) {
			p.store(out, "Deadcraft: visual effect settings (/deadcraft fx)");
		} catch (IOException e) {
			FxPack.LOG.warn("Deadcraft: couldn't save {}: {}", file, e.toString());
		}
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	public static String setBloom(float v) {
		bloom = clamp(v, 0, MAX_BLOOM);
		save();
		return status();
	}

	public static String setBrightness(float v) {
		brightness = clamp(v, MIN_SCALE, MAX_SCALE);
		save();
		return status();
	}

	public static String setSize(float v) {
		size = clamp(v, MIN_SCALE, MAX_SCALE);
		save();
		return status();
	}

	public static String reset() {
		bloom = DEFAULT_BLOOM;
		brightness = DEFAULT_BRIGHTNESS;
		size = DEFAULT_SIZE;
		save();
		return status();
	}

	public static String status() {
		return String.format(Locale.ROOT, "Deadcraft effects: bloom %.2f%s, brightness %.2f, size %.2f (defaults %.1f, %.0f, %.0f; /deadcraft fx bloom|brightness|size <n>, fx reset)%s",
			bloom, bloom == 0 ? " (off)" : "", brightness, size, DEFAULT_BLOOM, DEFAULT_BRIGHTNESS, DEFAULT_SIZE, Bloom.problem().isEmpty() ? "" : "; bloom: " + Bloom.problem());
	}
}
