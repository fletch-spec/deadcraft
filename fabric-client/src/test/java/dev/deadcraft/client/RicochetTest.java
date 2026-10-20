package dev.deadcraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RicochetTest {
	/** A bullet glancing down onto the ground leaves upward at the same angle; through a mob it goes straight on. */
	@Test
	void glancesOffLikeAMirror() {
		Vec3 in = new Vec3(1, -1, 0).normalize();
		Vec3 out = Follow.bounce(in, new Vec3(0, 1, 0));
		assertEquals(in.x, out.x, 1e-9);
		assertEquals(-in.y, out.y, 1e-9);
		assertEquals(in, Follow.bounce(in, null));
	}

	/** Mobs within 30 degrees of the bounce and 15 blocks count; behind, wide or far ones don't. */
	@Test
	void picksMobsInTheCone() {
		Vec3 out = new Vec3(1, 0, 0);
		assertTrue(Follow.inRicochetCone(out, new Vec3(8, 0, 3)));   // 20.6 deg
		assertFalse(Follow.inRicochetCone(out, new Vec3(8, 0, 6)));  // 36.9 deg
		assertFalse(Follow.inRicochetCone(out, new Vec3(-5, 0, 0)));
		assertFalse(Follow.inRicochetCone(out, new Vec3(20, 0, 0)));
	}
}
