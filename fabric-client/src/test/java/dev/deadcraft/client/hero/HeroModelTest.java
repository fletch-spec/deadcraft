package dev.deadcraft.client.hero;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HeroModelTest {
	/** A one-triangle, one-joint pack whose two-frame looping clip slides the joint 0 -> 1 along x. */
	private static ByteBuffer pack() {
		ByteBuffer b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
		b.put("DCHM".getBytes(StandardCharsets.US_ASCII)).putInt(HeroModel.VERSION);
		b.putInt(1);
		str(b, "skin");
		str(b, "hero_skin.png");
		b.putInt(3);
		for (int v = 0; v < 3; v++) {
			b.putFloat(v).putFloat(0).putFloat(0);   // position
			b.putFloat(0).putFloat(1).putFloat(0);   // normal
			b.putFloat(0.5f).putFloat(0.25f);        // uv
			b.putInt(0xFFFFFFFF);
			b.putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0);
			b.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
		}
		b.putInt(1).putInt(0).putInt(1).putInt(2);  // one triangle
		b.putInt(1);  // joints
		b.putInt(1);  // clips
		str(b, "slide");
		b.putFloat(2f).putInt(2).put((byte) 1);
		for (int f = 0; f < 2; f++) {
			b.putFloat(1).putFloat(0).putFloat(0).putFloat(f);
			b.putFloat(0).putFloat(1).putFloat(0).putFloat(0);
			b.putFloat(0).putFloat(0).putFloat(1).putFloat(0);
		}
		return b.flip();
	}

	private static void str(ByteBuffer b, String s) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		b.putInt(bytes.length).put(bytes);
	}

	@Test
	void readsAPack() throws Exception {
		HeroModel m = HeroModel.read(pack(), Path.of("."));
		assertEquals(3, m.vertexCount());
		assertEquals("hero_skin.png", m.materials.get(0).texture());
		assertEquals(3, m.materials.get(0).indices().length);
		assertEquals(0.25f, m.uvs[1]);
		assertEquals(1, m.jointCount);
		assertEquals(1f, m.clips.get("slide").duration());  // 2 frames at 2 fps, looping
	}

	@Test
	void interpolatesAndLoops() throws Exception {
		HeroModel m = HeroModel.read(pack(), Path.of("."));
		HeroModel.Clip clip = m.clips.get("slide");
		float[] out = new float[12];
		m.accumulate(clip, 0.25f, 1, out);  // halfway from frame 0 to frame 1
		assertEquals(0.5f, out[3], 1e-6);
		out = new float[12];
		m.accumulate(clip, 0.75f, 1, out);  // halfway from frame 1 back round to frame 0
		assertEquals(0.5f, out[3], 1e-6);
		out = new float[12];
		m.accumulate(clip, 0f, 0.5f, out);  // weights scale
		assertEquals(0.5f, out[0], 1e-6);
	}
}
