package dev.deadcraft.client.fx;

/**
 * One particle of a running system, in the system's frame (Source units, Z up). Fields follow Source 2's
 * particle attributes (the {@code m_nOutputField} numbers in the effect files): see {@link #scalar} and
 * {@link #vector}. Angles are stored in radians.
 */
final class Particle {
	// Attribute numbers (Source 2's PARTICLE_ATTRIBUTE_*).
	static final int POSITION = 0, LIFE_DURATION = 1, POSITION_PREVIOUS = 2, RADIUS = 3, ROLL = 4, ROLL_SPEED = 5,
		COLOR = 6, ALPHA = 7, CREATION_TIME = 8, SEQUENCE = 9, TRAIL_LENGTH = 10, PARTICLE_ID = 11, YAW = 12,
		SEQUENCE1 = 13, HITBOX_OFFSET = 15, ALPHA2 = 16, SCRATCH_VECTOR = 17, SCRATCH_FLOAT = 18, PITCH = 20,
		NORMAL = 21, GLOW_RGB = 22, GLOW_ALPHA = 23, SCRATCH_FLOAT1 = 26, SCRATCH_FLOAT2 = 27, SCRATCH_VECTOR2 = 30,
		FORCE_SCALE = 34, MANUAL_FRAME = 38;

	final float[] pos = new float[3], prev = new float[3], vel = new float[3], force = new float[3];
	final float[] color = {1, 1, 1}, normal = {0, 0, 1}, scratch = new float[3], scratch2 = new float[3], glow = new float[3],
		hitbox = new float[3];
	float age, lifetime = 1, alpha = 1, alpha2 = 1, radius = 5, trailLength = 0.1f, roll, rollSpeed, yaw, pitch,
		creationTime, scratchF, scratchF1, scratchF2, glowAlpha, forceScale = 1, sequence, sequence1, manualFrame;
	int id, uniqueId, index;
	boolean killed;
	/** The particle as it was spawned (operators that scale an initial value read it). */
	Particle initial;

	float normalizedAge() {
		return age / Math.max(0.0001f, lifetime);
	}

	static boolean isAngle(int field) {
		return field == ROLL || field == YAW || field == PITCH || field == ROLL_SPEED;
	}

	static boolean isVector(int field) {
		return switch (field) {
			case POSITION, POSITION_PREVIOUS, COLOR, HITBOX_OFFSET, SCRATCH_VECTOR, NORMAL, GLOW_RGB, SCRATCH_VECTOR2 -> true;
			default -> false;
		};
	}

	float scalar(int field) {
		return switch (field) {
			case LIFE_DURATION -> lifetime;
			case RADIUS -> radius;
			case ROLL -> roll;
			case ROLL_SPEED -> rollSpeed;
			case ALPHA -> alpha;
			case CREATION_TIME -> creationTime;
			case SEQUENCE -> sequence;
			case TRAIL_LENGTH -> trailLength;
			case PARTICLE_ID -> id;
			case YAW -> yaw;
			case SEQUENCE1 -> sequence1;
			case ALPHA2 -> alpha2;
			case SCRATCH_FLOAT -> scratchF;
			case PITCH -> pitch;
			case GLOW_ALPHA -> glowAlpha;
			case SCRATCH_FLOAT1 -> scratchF1;
			case SCRATCH_FLOAT2 -> scratchF2;
			case FORCE_SCALE -> forceScale;
			case MANUAL_FRAME -> manualFrame;
			default -> 0;
		};
	}

	void setScalar(int field, float v) {
		switch (field) {
			case LIFE_DURATION -> lifetime = v;
			case RADIUS -> radius = v;
			case ROLL -> roll = v;
			case ROLL_SPEED -> rollSpeed = v;
			case ALPHA -> alpha = v;
			case CREATION_TIME -> creationTime = v;
			case SEQUENCE -> sequence = v;
			case TRAIL_LENGTH -> trailLength = v;
			case YAW -> yaw = v;
			case SEQUENCE1 -> sequence1 = v;
			case ALPHA2 -> alpha2 = v;
			case SCRATCH_FLOAT -> scratchF = v;
			case PITCH -> pitch = v;
			case GLOW_ALPHA -> glowAlpha = v;
			case SCRATCH_FLOAT1 -> scratchF1 = v;
			case SCRATCH_FLOAT2 -> scratchF2 = v;
			case FORCE_SCALE -> forceScale = v;
			case MANUAL_FRAME -> manualFrame = v;
			default -> { }
		}
	}

	/** The vector attribute itself (writable), or null for a scalar field. */
	float[] vector(int field) {
		return switch (field) {
			case POSITION -> pos;
			case POSITION_PREVIOUS -> prev;
			case COLOR -> color;
			case HITBOX_OFFSET -> hitbox;
			case SCRATCH_VECTOR -> scratch;
			case NORMAL -> normal;
			case GLOW_RGB -> glow;
			case SCRATCH_VECTOR2 -> scratch2;
			default -> null;
		};
	}

	void setVector(int field, float x, float y, float z) {
		float[] v = vector(field);
		if (v == null) return;
		if (field == NORMAL && x == 0 && y == 0 && z == 0) return;
		v[0] = x;
		v[1] = y;
		v[2] = z;
	}

	float initialScalar(int field) {
		return initial == null ? scalar(field) : initial.scalar(field);
	}

	float[] initialVector(int field) {
		return initial == null ? vector(field) : initial.vector(field);
	}

	void copyFrom(Particle o) {
		System.arraycopy(o.pos, 0, pos, 0, 3);
		System.arraycopy(o.prev, 0, prev, 0, 3);
		System.arraycopy(o.vel, 0, vel, 0, 3);
		System.arraycopy(o.color, 0, color, 0, 3);
		System.arraycopy(o.normal, 0, normal, 0, 3);
		System.arraycopy(o.scratch, 0, scratch, 0, 3);
		System.arraycopy(o.scratch2, 0, scratch2, 0, 3);
		System.arraycopy(o.glow, 0, glow, 0, 3);
		System.arraycopy(o.hitbox, 0, hitbox, 0, 3);
		force[0] = force[1] = force[2] = 0;
		age = o.age;
		lifetime = o.lifetime;
		alpha = o.alpha;
		alpha2 = o.alpha2;
		radius = o.radius;
		trailLength = o.trailLength;
		roll = o.roll;
		rollSpeed = o.rollSpeed;
		yaw = o.yaw;
		pitch = o.pitch;
		creationTime = o.creationTime;
		scratchF = o.scratchF;
		scratchF1 = o.scratchF1;
		scratchF2 = o.scratchF2;
		glowAlpha = o.glowAlpha;
		forceScale = o.forceScale;
		sequence = o.sequence;
		sequence1 = o.sequence1;
		manualFrame = o.manualFrame;
		id = o.id;
		uniqueId = o.uniqueId;
		index = o.index;
		killed = false;
	}
}
