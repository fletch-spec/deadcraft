package dev.deadcraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LookPredictorTest {
	private static final double TICK = 1 / 64.0;
	private static final double GAIN = -0.044;  // sensitivity 2 * m_yaw 0.022, mouse right turns yaw down
	private static final double LAG = 0.048;

	/** A mouse sweeping back and forth (and a little up and down), reported at 1000 Hz. */
	private static long[] mouse(double t) {
		return new long[] {Math.round(4000 * Math.sin(t * 2.1) + 1500 * Math.sin(t * 5.3)), Math.round(600 * Math.sin(t * 1.7))};
	}

	private static final LookPredictor.Counts COUNTS = nanos -> mouse(Math.floor(nanos / 1e6) / 1000.0);

	/** Deadlock's angle in a sample that reaches us at {@code t}: the mouse as it was LAG earlier. */
	private static double deadlockYaw(double t) {
		return LookPredictor.wrap(30 + GAIN * mouse(t - LAG)[0]);
	}

	private static double deadlockPitch(double t) {
		return -GAIN * mouse(t - LAG)[1];
	}

	@Test
	void learnsGainAndLagThenTracksTheMouse() {
		LookPredictor p = new LookPredictor(COUNTS);
		double t = 100;
		assertNull(p.predict((long) (t * 1e9)));
		for (int i = 0; i < 64 * 10; i++, t += TICK) p.addSample(t, (float) deadlockYaw(t), (float) deadlockPitch(t));
		LookPredictor.Look look = p.predict((long) (t * 1e9));
		assertNotNull(look, p.describe());
		// Drawn where Deadlock will be once it catches up: the mouse now, not LAG ago.
		double expectedYaw = LookPredictor.wrap(30 + GAIN * mouse(t)[0]);
		assertEquals(0, LookPredictor.wrap(look.yaw() - expectedYaw), 0.3, p.describe());
		assertEquals(-GAIN * mouse(t)[1], look.pitch(), 0.3, p.describe());
	}

	@Test
	void pullsBackToDeadlockWhenTheMouseDoesNotTurnIt() {
		LookPredictor p = new LookPredictor(COUNTS);
		double t = 100;
		for (int i = 0; i < 64 * 10; i++, t += TICK) p.addSample(t, (float) deadlockYaw(t), (float) deadlockPitch(t));
		// Deadlock's menu opens: the mouse moves but the hero holds its angle.
		float held = (float) deadlockYaw(t);
		for (int i = 0; i < 32; i++, t += TICK) p.addSample(t, held, 0);
		double drawn = p.predict((long) (t * 1e9)).yaw();
		// Off by the mouse motion since the last sample's lagged counts, plus what the bias filter hasn't
		// absorbed yet (under its snap threshold, at most about threshold / filter step).
		double recent = Math.abs(GAIN * (mouse(t)[0] - mouse(t - TICK - LAG)[0]));
		assertEquals(0, LookPredictor.wrap(drawn - held), recent + 10);
	}

	@Test
	void staysOffWithoutMouseMotion() {
		LookPredictor p = new LookPredictor(nanos -> new long[] {0, 0});
		double t = 100;
		for (int i = 0; i < 640; i++, t += TICK) p.addSample(t, (float) (i * 0.5 % 360 - 180), 0);
		assertNull(p.predict((long) (t * 1e9)));
	}
}
