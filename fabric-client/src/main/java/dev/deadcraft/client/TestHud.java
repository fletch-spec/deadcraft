package dev.deadcraft.client;

import dev.deadcraft.client.hero.HeroRenderer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Test readout in the top-left corner while linked ({@code /deadcraft hud}): frame rate and frame
 * times, how stale Deadlock's newest sample is, the raw-mouse camera's corrections, the animation
 * state and speed, and the hero model's cost. Refreshed once a second; each line covers that second.
 */
final class TestHud {
	private static boolean enabled = true;
	private static final List<String> lines = new ArrayList<>();
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
		lines.add(String.format("%.0f fps  frame %.2f ms avg, %.1f ms worst", frames / (frameSum / 1000), frameSum / Math.max(1, frames), frameMax));
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
		lastFrameTime = 0;
		windowStart = 0;
	}

	static void draw(GuiGraphicsExtractor g) {
		if (!enabled || !Follow.linked() || lines.isEmpty()) return;
		var font = Minecraft.getInstance().font;
		int y = 4, width = 0;
		for (String line : lines) width = Math.max(width, font.width(line));
		g.fill(2, 2, 8 + width, 6 + lines.size() * (font.lineHeight + 1), 0x90000000);
		for (String line : lines) {
			g.text(font, line, 5, y, 0xFFFFFFFF, false);
			y += font.lineHeight + 1;
		}
	}
}
