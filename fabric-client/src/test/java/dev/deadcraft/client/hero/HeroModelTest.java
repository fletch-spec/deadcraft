package dev.deadcraft.client.hero;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HeroModelTest {
	private static final float[] IDENTITY_TRS = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};

	/**
	 * A one-triangle pack: a root node and a child one unit up, the child skinned (inverse bind
	 * undoing its rest position). Clip "slide": the child moves 0 -> 1 along x over two frames at 2 fps,
	 * looping. Clip "turn": the child turned 90 degrees about y.
	 */
	private static ByteBuffer pack() {
		ByteBuffer b = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
		b.put("DCHM".getBytes(StandardCharsets.US_ASCII)).putInt(HeroModel.VERSION);
		b.putInt(1);
		str(b, "skin");
		str(b, "hero_skin.png");
		b.putInt(3);
		for (int v = 0; v < 3; v++) {
			b.putFloat(v).putFloat(1).putFloat(0);   // position (at the child's height)
			b.putFloat(0).putFloat(1).putFloat(0);   // normal
			b.putFloat(0.5f).putFloat(0.25f);        // uv
			b.putInt(0xFFFFFFFF);
			b.putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0);
			b.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
		}
		b.putInt(1).putInt(0).putInt(1).putInt(2);  // one triangle
		b.putInt(2);  // nodes
		str(b, "root");
		b.putInt(-1);
		for (float f : IDENTITY_TRS) b.putFloat(f);
		str(b, "head");
		b.putInt(0);
		trs(b, 0, 1, 0, 0, 0, 0, 1);
		b.putInt(1);  // skinned joints
		b.putInt(1);
		float[] inverseBind = {1, 0, 0, 0, 0, 1, 0, -1, 0, 0, 1, 0};
		for (float f : inverseBind) b.putFloat(f);
		b.putInt(3);  // clips
		str(b, "slide");
		b.putFloat(2f).putInt(2).put((byte) 1);
		for (int f = 0; f < 2; f++) {
			for (float x : IDENTITY_TRS) b.putFloat(x);
			trs(b, f, 1, 0, 0, 0, 0, 1);
		}
		for (int f = 0; f < 2 * 3; f++) b.putFloat(0);  // travel
		// "swing": 170 then 190 degrees about y. Stored as glTF would (w >= 0 each), the two frames sit on
		// opposite sides of the quaternion sphere, though they are only 20 degrees apart.
		str(b, "swing");
		b.putFloat(2f).putInt(2).put((byte) 0);
		for (double deg : new double[] {170, 190}) {
			for (float x : IDENTITY_TRS) b.putFloat(x);
			double h = Math.toRadians(deg) / 2;
			float qy = (float) Math.sin(h), qw = (float) Math.cos(h);
			if (qw < 0) {
				qy = -qy;
				qw = -qw;
			}
			trs(b, 0, 1, 0, 0, qy, 0, qw);
		}
		for (int f = 0; f < 2 * 3; f++) b.putFloat(0);
		str(b, "turn");
		float s = (float) Math.sqrt(0.5);
		b.putFloat(2f).putInt(1).put((byte) 1);
		for (float x : IDENTITY_TRS) b.putFloat(x);
		trs(b, 0, 1, 0, 0, s, 0, s);
		for (int f = 0; f < 3; f++) b.putFloat(0);
		return b.flip();
	}

	private static void trs(ByteBuffer b, float tx, float ty, float tz, float qx, float qy, float qz, float qw) {
		b.putFloat(tx).putFloat(ty).putFloat(tz).putFloat(qx).putFloat(qy).putFloat(qz).putFloat(qw).putFloat(1).putFloat(1).putFloat(1);
	}

	private static void str(ByteBuffer b, String s) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		b.putInt(bytes.length).put(bytes);
	}

	private static float[] skinAt(HeroModel m, String clip, float time, float twist) {
		HeroModel.Pose pose = m.newPose();
		pose.add(m.clips.get(clip), time, 1);
		pose.finish();
		float[] world = new float[m.nodeCount() * 12], out = new float[m.skinnedJointCount() * 12];
		m.skin(pose, world, twist, new int[] {m.node("head")}, new float[] {1}, out);
		return out;
	}

	@Test
	void readsAPack() throws Exception {
		HeroModel m = HeroModel.read(pack(), Path.of("."));
		assertEquals(3, m.vertexCount());
		assertEquals("hero_skin.png", m.materials.get(0).texture());
		assertEquals(0.25f, m.uvs[1]);
		assertEquals(2, m.nodeCount());
		assertEquals(1, m.node("head"));
		assertEquals(1, m.skinnedJointCount());
		assertEquals(1f, m.clips.get("slide").duration());  // 2 frames at 2 fps, looping
	}

	@Test
	void restPoseSkinsToIdentity() throws Exception {
		HeroModel m = HeroModel.read(pack(), Path.of("."));
		float[] skin = skinAt(m, "slide", 0, 0);
		float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
		for (int i = 0; i < 12; i++) assertEquals(identity[i], skin[i], 1e-6, "element " + i);
	}

	@Test
	void interpolatesAndLoops() throws Exception {
		HeroModel m = HeroModel.read(pack(), Path.of("."));
		assertEquals(0.5f, skinAt(m, "slide", 0.25f, 0)[3], 1e-6);  // halfway from frame 0 to frame 1
		assertEquals(0.5f, skinAt(m, "slide", 0.75f, 0)[3], 1e-6);  // halfway from frame 1 round to frame 0
	}

	@Test
	void blendsRotationsPerJoint() throws Exception {
		HeroModel m = HeroModel.read(pack(), Path.of("."));
		HeroModel.Pose pose = m.newPose();
		pose.add(m.clips.get("slide"), 0, 0.5f);  // no turn
		pose.add(m.clips.get("turn"), 0, 0.5f);   // 90 degrees
		pose.finish();
		float[] world = new float[24], out = new float[12];
		m.skin(pose, world, 0, new int[0], new float[0], out);
		// Halfway: 45 degrees about y, so x maps to (cos 45, 0, -sin 45).
		assertEquals(Math.cos(Math.PI / 4), out[0], 1e-5);
		assertEquals(-Math.sin(Math.PI / 4), out[8], 1e-5);
	}

	@Test
	void interpolatesFarSwingsTheShortWay() throws Exception {
		HeroModel m = HeroModel.read(pack(), Path.of("."));
		float[] skin = skinAt(m, "swing", 0.25f, 0);  // halfway between 170 and 190: 180 degrees
		assertEquals(-1, skin[0], 1e-4);   // x maps to -x
		assertEquals(0, skin[8], 1e-4);
	}

	@Test
	void twistTurnsAboutTheJoint() throws Exception {
		HeroModel m = HeroModel.read(pack(), Path.of("."));
		float[] skin = skinAt(m, "slide", 0, (float) (Math.PI / 2));
		// A point one unit along x from the head (at y = 1) turns to -z, and the head stays where it is.
		float x = 1, y = 1, z = 0;
		float px = skin[0] * x + skin[1] * y + skin[2] * z + skin[3];
		float py = skin[4] * x + skin[5] * y + skin[6] * z + skin[7];
		float pz = skin[8] * x + skin[9] * y + skin[10] * z + skin[11];
		assertEquals(0, px, 1e-5);
		assertEquals(1, py, 1e-5);
		assertEquals(-1, pz, 1e-5);
	}

	/** On a real export (skipped where none exists): the rest pose skins every joint to identity. */
	@Test
	void realPackRestPoseIsIdentity() throws Exception {
		Path file = HeroRenderer.heroDirectory().resolve("unicorn.dchero");
		assumeTrue(java.nio.file.Files.exists(file), "no exported Celeste on this machine");
		HeroModel m = HeroModel.load(file);
		HeroModel.Pose pose = m.newPose();
		pose.finish();
		float[] world = new float[m.nodeCount() * 12], out = new float[m.skinnedJointCount() * 12];
		m.skin(pose, world, 0, new int[0], new float[0], out);
		float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
		double worst = 0;
		for (int j = 0; j < m.skinnedJointCount(); j++)
			for (int i = 0; i < 12; i++) worst = Math.max(worst, Math.abs(out[j * 12 + i] - identity[i]));
		assertEquals(0, worst, 1e-3, "largest difference from identity");
	}
}
