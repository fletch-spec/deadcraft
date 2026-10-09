package dev.deadcraft.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtocolTest {
	private static byte[] goldenBytes() throws IOException {
		try (InputStream in = ProtocolTest.class.getResourceAsStream("/golden.bin")) {
			return in.readAllBytes();
		}
	}

	@Test
	void encodesTheGoldenFixture() throws IOException {
		ByteBuffer b = ByteBuffer.allocate(Golden.EXTENT).order(ByteOrder.LITTLE_ENDIAN);
		for (Golden.At at : Golden.structs()) {
			switch (at.value()) {
				case Header h -> h.write(b, at.offset());
				case HeroState s -> s.write(b, at.offset());
				case AbilityEvent e -> e.write(b, at.offset());
				case Shot sh -> sh.write(b, at.offset());
				case McState m -> m.write(b, at.offset());
				case Cube c -> c.write(b, at.offset());
				default -> throw new IllegalStateException(at.value().getClass().getName());
			}
		}
		assertArrayEquals(goldenBytes(), b.array());
	}

	@Test
	void decodesTheGoldenFixture() throws IOException {
		ByteBuffer b = ByteBuffer.wrap(goldenBytes()).order(ByteOrder.LITTLE_ENDIAN);
		for (Golden.At at : Golden.structs()) {
			Object actual = switch (at.value()) {
				case Header h -> Header.read(b, at.offset());
				case HeroState s -> HeroState.read(b, at.offset());
				case AbilityEvent e -> AbilityEvent.read(b, at.offset());
				case Shot sh -> Shot.read(b, at.offset());
				case McState m -> McState.read(b, at.offset());
				case Cube c -> Cube.read(b, at.offset());
				default -> throw new IllegalStateException(at.value().getClass().getName());
			};
			assertEquals(at.value(), actual);
		}
	}

	@Test
	void convertsUnitsAndAxes() {
		// One block east, two up, three south.
		assertEquals(new Vec3(64f, -192f, 128f), Proto.toSource(new Vec3(1f, 2f, 3f)));
		assertEquals(new Vec3(1f, 2f, 3f), Proto.toMinecraft(new Vec3(64f, -192f, 128f)));
	}

	@Test
	void roundTripsTheCubeSet() {
		String name = "Local\\DeadcraftTest_" + UUID.randomUUID().toString().replace("-", "");
		try (Mapping m = Mapping.openOrCreate(name)) {
			Cube a = new Cube();
			a.edge = 16;
			Cube b = new Cube();
			b.x = -3;
			b.y = 9;
			b.z = 31;
			b.edge = 1;
			McState s = new McState();
			s.flags = McFlags.LINKED;
			s.generation = 5;
			s.base = new Int3(10, -64, 20);
			s.frameOffset = new Double3(0.5, 1.5, -2.5);
			m.writeMcState(s, java.util.List.of(a, b));

			Mapping.McSnapshot read = m.readMcState().orElseThrow();
			assertEquals(5, read.state().generation);
			assertEquals(2, read.state().cubeCount);
			assertEquals(new Double3(0.5, 1.5, -2.5), read.state().frameOffset);
			assertEquals(java.util.List.of(a, b), read.cubes());
		}
	}

	@Test
	void roundTripsThroughSharedMemory() {
		String name = "Local\\DeadcraftTest_" + UUID.randomUUID().toString().replace("-", "");
		try (Mapping writer = Mapping.openOrCreate(name); Mapping reader = Mapping.openExisting(name).orElseThrow()) {
			assertEquals(Proto.MAGIC, reader.readHeader().magic);
			HeroState s = new HeroState();
			s.flags = HeroFlags.PRESENT | HeroFlags.ON_GROUND;
			s.tick = 77;
			s.position = new Vec3(1, 2, 3);
			s.health = 500;
			writer.writeHeroState(s);

			HeroState read = reader.readHeroState().orElseThrow();
			assertEquals(0, read.seq % 2);
			assertEquals(s.position, read.position);
			assertEquals(500, read.health);
			assertTrue(reader.readAbilityEvent(1).isEmpty());
		}
	}
}
