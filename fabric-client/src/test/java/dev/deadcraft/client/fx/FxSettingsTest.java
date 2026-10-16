package dev.deadcraft.client.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FxSettingsTest {
	/** Settings are clamped to their ranges, saved, and read back. */
	@Test
	void settingsClampSaveAndLoad(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("deadcraft-fx.properties");
		FxSettings.load(file);
		FxSettings.reset();
		assertEquals(FxSettings.DEFAULT_BLOOM, FxSettings.bloom());
		FxSettings.setBloom(99);
		FxSettings.setBrightness(1.5f);
		FxSettings.setSize(0.01f);
		assertEquals(FxSettings.MAX_BLOOM, FxSettings.bloom());
		assertEquals(FxSettings.MIN_SCALE, FxSettings.size());
		FxSettings.reset();
		assertEquals(1, FxSettings.brightness());
		FxSettings.setBrightness(1.5f);
		FxSettings.setBloom(0);
		FxSettings.bloom = FxSettings.brightness = FxSettings.size = -1;
		FxSettings.load(file);
		assertEquals(0, FxSettings.bloom());
		assertEquals(1.5f, FxSettings.brightness());
		assertEquals(FxSettings.DEFAULT_SIZE, FxSettings.size());
		Files.writeString(file, "bloom=banana\n");
		FxSettings.reset();
	}
}
