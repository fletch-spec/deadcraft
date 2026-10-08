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
		try (InputStream in = ProtocolTest.class.getResourceAsStream("/v1.bin")) {
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
