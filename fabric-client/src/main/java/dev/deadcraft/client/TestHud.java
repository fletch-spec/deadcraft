package dev.deadcraft.client;

import dev.deadcraft.client.hero.HeroRenderer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Test readout while linked ({@code /deadcraft hud}): frame rate and frame time in the top-left
 * corner. Each second it also gathers how stale Deadlock's newest sample is, the raw-mouse camera's
 * corrections, the animation state and the hero model's cost, which the log carries every 10 s.
 */
final class TestHud {
	private static boolean enabled = true;
	private static final List<String> lines = new ArrayList<>();
	private static String shown = "";
	private static long windowStart;
	private static int frames;
	private static double frameSum, frameMax, ageSum, ageMax;
	private static int ageSamples;
	private static double lastFrameTime;

	private TestHud() {}

	static String toggle() {
		enabled = !enabled;
		return enabled ? "Test HUD on." : "Test HUD off.";
	}

	/** Every linked frame. {@code sampleAgeMs}: how long ago Deadlock's newest sample arrived. */
	static void frame(double now, double sampleAgeMs) {
		if (lastFrameTime > 0) {
			double dt = (now - lastFrameTime) * 1000;
			frames++;
			frameSum += dt;
			frameMax = Math.max(frameMax, dt);
		}
		lastFrameTime = now;
		ageSum += sampleAgeMs;
		ageMax = Math.max(ageMax, sampleAgeMs);
		ageSamples++;
		long nowMs = System.currentTimeMillis();
		if (windowStart == 0) windowStart = nowMs;
		if (nowMs - windowStart < 1000) return;
		windowStart = nowMs;
		lines.clear();
		shown = String.format("%.0f fps  %.2f ms", frames / (frameSum / 1000), frameSum / Math.max(1, frames));
		var level = Minecraft.getInstance().level;
		lines.add(String.format("%.0f fps  frame %.2f ms avg, %.1f ms worst  weather %s", frames / (frameSum / 1000), frameSum / Math.max(1, frames), frameMax,
			level == null ? "?" : level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear"));
		lines.add(String.format("deadlock sample age %.1f ms avg, %.1f max", ageSum / Math.max(1, ageSamples), ageMax));
		lines.add(Follow.timelineHudLine());
		lines.add(Follow.lookHudLine());
		lines.add(HeroRenderer.hudLine());
		frames = ageSamples = 0;
		frameSum = frameMax = ageSum = ageMax = 0;
	}

	/** The last refresh's lines, for the log. */
	static String lastLines() {
		return String.join(" | ", lines);
	}

	static void unlinked() {
		lines.clear();
		shown = "";
		lastFrameTime = 0;
		windowStart = 0;
	}

	/**
	 * A crosshair at the screen's centre while linked: Minecraft draws its own only in first person, and
	 * the Deadlock camera is third person. Deadlock aims along its camera's ray through the same point.
	 */
	static void crosshair(GuiGraphicsExtractor g) {
		if (!Follow.linked() || !DeadlockCamera.enabled() || Minecraft.getInstance().gui.screen() != null) return;
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
		g.fill(cx - 5, cy - 1, cx + 6, cy + 2, 0x90000000);
		g.fill(cx - 1, cy - 5, cx + 2, cy + 6, 0x90000000);
		g.fill(cx - 4, cy, cx + 5, cy + 1, 0xFFFFFFFF);
		g.fill(cx, cy - 4, cx + 1, cy + 5, 0xFFFFFFFF);
	}

	/** On screen just frame rate and frame time; the full readout goes to the log every 10 s. */
	static void draw(GuiGraphicsExtractor g) {
		if (!enabled || !Follow.linked() || shown.isEmpty()) return;
		var font = Minecraft.getInstance().font;
		g.fill(2, 2, 8 + font.width(shown), 6 + font.lineHeight, 0x90000000);
		g.text(font, shown, 5, 4, 0xFFFFFFFF, false);
	}
}
