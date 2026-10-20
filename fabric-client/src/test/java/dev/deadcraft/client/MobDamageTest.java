package dev.deadcraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MobDamageTest {
	/** Deadlock's falloff (Celeste's gun): full to 866 units (13.5 blocks), a tenth from 2362 (36.9), straight between. */
	@Test
	void fallsOffWithDistance() {
		assertEquals(1, MobDamage.falloff(5), 1e-6);
		assertEquals(1, MobDamage.falloff(MobDamage.FALLOFF_START), 1e-6);
		assertEquals(0.55, MobDamage.falloff((MobDamage.FALLOFF_START + MobDamage.FALLOFF_END) / 2), 1e-4);
		assertEquals(0.1, MobDamage.falloff(60), 1e-6);
	}

	/** At the default scale a close bullet takes 4.5 health: a zombie (20) in five shots. */
	@Test
	void scalesForMinecraftHealth() {
		assertEquals(4.5, MobDamage.health(MobDamage.GUN_DAMAGE, 3), 1e-4);
		assertEquals(5, (int) Math.ceil(20 / MobDamage.health(MobDamage.GUN_DAMAGE, 3)));
	}
}
