package dev.deadcraft.client.fx;

import java.util.List;
import java.util.Map;

/**
 * Source 2's per-particle inputs: the float ({@code PF_TYPE_*}) and vector ({@code PVEC_TYPE_*}) values
 * operators read, with their mappings and curves, and the control point transforms. Semantics follow
 * ValveResourceFormat's particle simulation (MIT licence, github.com/ValveResourceFormat/ValveResourceFormat).
 */
final class Inputs {
	private Inputs() {
	}

	/** A float input. {@code p} is null when it's read for the whole system (an emitter's count, ...). */
	interface FloatInput {
		float get(Particle p, FxSystem sys);

		default boolean isLiteral(float value) {
			return this instanceof Literal l && l.value == value;
		}
	}

	interface VecInput {
		/** Writes the vector into {@code out} (length 3) and returns it. */
		float[] get(Particle p, FxSystem sys, float[] out);
	}

	record Literal(float value) implements FloatInput {
		@Override
		public float get(Particle p, FxSystem sys) {
			return value;
		}
	}

	record LiteralVec(float x, float y, float z) implements VecInput {
		@Override
		public float[] get(Particle p, FxSystem sys, float[] out) {
			out[0] = x;
			out[1] = y;
			out[2] = z;
			return out;
		}
	}

	/** Reads a float input; {@code ordinal} hands each random input its own per-particle stream. */
	static FloatInput floatInput(Object node, float fallback, Compiler c) {
		if (node instanceof Double d) return new Literal(d.floatValue());
		if (!(node instanceof Map<?, ?>)) return new Literal(fallback);
		@SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) node;
		String type = Kv3.s(m, "m_nType", "PF_TYPE_LITERAL");
		Mapping map = new Mapping(m);
		switch (type) {
			case "PF_TYPE_LITERAL", "PF_TYPE_INVALID":
				return new Literal(Kv3.f(m, "m_flLiteralValue", fallback));
			case "PF_TYPE_RANDOM_UNIFORM", "PF_TYPE_RANDOM_BIASED": {
				float min = Kv3.f(m, "m_flRandomMin", 0), max = Kv3.f(m, "m_flRandomMax", 1);
				boolean varying = "PF_RANDOM_MODE_VARYING".equals(Kv3.s(m, "m_nRandomMode", ""));
				boolean flip = Kv3.b(m, "m_bHasRandomSignFlip", false);
				boolean biased = type.equals("PF_TYPE_RANDOM_BIASED");
				float bias = Kv3.f(m, "m_flBiasParameter", 0);
				String biasType = Kv3.s(m, "m_nBiasType", "PF_BIAS_TYPE_STANDARD");
				int ordinal = c.nextOrdinal();
				return (p, sys) -> {
					float r = varying ? sys.rng() : sys.random(p == null ? 0 : p.id, ordinal);
					if (biased) r = bias(r, bias, biasType);
					float v = min + (max - min) * r;
					if (flip && (varying ? sys.rng() : sys.random(p == null ? 0 : p.id, ordinal + 37)) < 0.5f) v = -v;
					return map.apply(v);
				};
			}
			case "PF_TYPE_COLLECTION_AGE":
				return (p, sys) -> map.apply(sys.age);
			case "PF_TYPE_ENDCAP_AGE":
				return (p, sys) -> map.apply(sys.endCapAge());
			case "PF_TYPE_CONTROL_POINT_COMPONENT": {
				int cp = Kv3.i(m, "m_nControlPoint", 0), comp = Kv3.i(m, "m_nVectorComponent", 0);
				return (p, sys) -> map.apply(sys.cp(cp).pos[Math.min(2, Math.max(0, comp))]);
			}
			case "PF_TYPE_PARTICLE_DETAIL_LEVEL":
				return new Literal(Kv3.f(m, "m_flLOD0", 0));
			case "PF_TYPE_PARTICLE_AGE":
				return (p, sys) -> p == null ? 0 : map.apply(p.age);
			case "PF_TYPE_PARTICLE_AGE_NORMALIZED":
				return (p, sys) -> p == null ? 0 : map.apply(p.normalizedAge());
			case "PF_TYPE_PARTICLE_FLOAT", "PF_TYPE_PARTICLE_INITIAL_FLOAT": {
				int field = Kv3.i(m, "m_nScalarAttribute", Particle.RADIUS);
				boolean initial = type.endsWith("INITIAL_FLOAT");
				return (p, sys) -> p == null ? 0 : map.apply(initial ? p.initialScalar(field) : p.scalar(field));
			}
			case "PF_TYPE_PARTICLE_VECTOR_COMPONENT", "PF_TYPE_PARTICLE_INITIAL_VECTOR_COMPONENT": {
				int field = Kv3.i(m, "m_nVectorAttribute", Particle.COLOR), comp = Math.min(2, Math.max(0, Kv3.i(m, "m_nVectorComponent", 0)));
				return (p, sys) -> {
					if (p == null) return 0;
					float[] v = p.vector(field);
					return v == null ? 0 : map.apply(v[comp]);
				};
			}
			case "PF_TYPE_PARTICLE_SPEED":
				return (p, sys) -> p == null ? 0 : map.apply(len(p.vel));
			case "PF_TYPE_PARTICLE_NUMBER":
				return (p, sys) -> p == null ? 0 : map.apply(p.uniqueId);
			case "PF_TYPE_PARTICLE_NUMBER_NORMALIZED":
				return (p, sys) -> {
					if (p == null) return 0;
					int count = sys.particles.size();
					int divisor = sys.def.behaviorVersion >= 12 ? Math.max(count - 1, 1) : Math.max(count, 1);
					return map.apply(p.index / (float) divisor);
				};
			case "PF_TYPE_CONTROL_POINT_SPEED": {
				int cp = Kv3.i(m, "m_nControlPoint", 0);
				return (p, sys) -> map.apply(len(sys.cp(cp).vel));
			}
			case "PF_TYPE_CONTROL_POINT_DISTANCE": {
				int cp = Kv3.i(m, "m_nControlPoint", 0);
				return (p, sys) -> p == null ? 0 : map.apply(dist(p.pos, sys.cp(cp).pos));
			}
			case "PF_TYPE_RENDERER_CAMERA_DISTANCE", "PF_TYPE_CLOSEST_CAMERA_DISTANCE": {
				int cp = Kv3.i(m, "m_nControlPoint", 0);
				return (p, sys) -> map.apply(dist(sys.camera(), sys.cp(cp).pos));
			}
			case "PF_TYPE_CONTROL_POINT_IS_SET": {
				int cp = Kv3.i(m, "m_nControlPoint", 0);
				return (p, sys) -> map.apply(sys.cpIsSet(cp) ? 1 : 0);
			}
			case "PF_TYPE_PARTICLE_NOISE": {
				float lo = Kv3.f(m, "m_flNoiseOutputMin", 0), hi = Kv3.f(m, "m_flNoiseOutputMax", 1), scale = Kv3.f(m, "m_flNoiseScale", 0.1f);
				return (p, sys) -> {
					float[] at = p == null ? sys.cp(0).pos : p.pos;
					float n = Noise.value3(at[0] * scale, at[1] * scale, at[2] * scale + sys.age);
					return map.apply(lo + (hi - lo) * (n * 0.5f + 0.5f));
				};
			}
			default:
				c.unsupported("input " + type);
				return new Literal(Kv3.f(m, "m_flLiteralValue", fallback));
		}
	}

	static VecInput vecInput(Object node, float x, float y, float z, Compiler c) {
		if (node instanceof List<?> l && l.size() >= 3) return new LiteralVec(Kv3.num(l.get(0)), Kv3.num(l.get(1)), Kv3.num(l.get(2)));
		if (!(node instanceof Map<?, ?>)) return new LiteralVec(x, y, z);
		@SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) node;
		String type = Kv3.s(m, "m_nType", "PVEC_TYPE_LITERAL");
		switch (type) {
			case "PVEC_TYPE_LITERAL": {
				float[] v = Kv3.vec(m, "m_vLiteralValue", x, y, z);
				return new LiteralVec(v[0], v[1], v[2]);
			}
			case "PVEC_TYPE_LITERAL_COLOR": {
				float[] v = Kv3.vec(m, "m_LiteralColor", 255, 255, 255);
				return new LiteralVec(v[0] / 255f, v[1] / 255f, v[2] / 255f);
			}
			case "PVEC_TYPE_PARTICLE_VECTOR", "PVEC_TYPE_PARTICLE_INITIAL_VECTOR": {
				int field = Kv3.i(m, "m_nVectorAttribute", Particle.COLOR);
				float[] scale = Kv3.vec(m, "m_vVectorAttributeScale", 1, 1, 1);
				boolean initial = type.contains("INITIAL");
				return (p, sys, out) -> {
					float[] v = p == null ? null : initial ? p.initialVector(field) : p.vector(field);
					for (int k = 0; k < 3; k++) out[k] = v == null ? 0 : v[k] * scale[k];
					return out;
				};
			}
			case "PVEC_TYPE_PARTICLE_VELOCITY":
				return (p, sys, out) -> {
					for (int k = 0; k < 3; k++) out[k] = p == null ? 0 : p.vel[k];
					return out;
				};
			case "PVEC_TYPE_CP_VALUE": {
				int cp = Kv3.i(m, "m_nControlPoint", 0);
				float[] scale = Kv3.vec(m, "m_vCPValueScale", 1, 1, 1);
				return (p, sys, out) -> {
					float[] v = sys.cp(cp).pos;
					for (int k = 0; k < 3; k++) out[k] = v[k] * scale[k];
					return out;
				};
			}
			case "PVEC_TYPE_CP_RELATIVE_POSITION": {
				int cp = Kv3.i(m, "m_nControlPoint", 0);
				float[] rel = Kv3.vec(m, "m_vCPRelativePosition", 0, 0, 0);
				return (p, sys, out) -> {
					float[] v = sys.cp(cp).pos;
					for (int k = 0; k < 3; k++) out[k] = v[k] + rel[k];
					return out;
				};
			}
			case "PVEC_TYPE_CP_DELTA": {
				int cp = Kv3.i(m, "m_nControlPoint", 0), delta = Kv3.i(m, "m_nDeltaControlPoint", 0);
				return (p, sys, out) -> {
					float[] a = sys.cp(cp).pos, b = sys.cp(delta).pos;
					for (int k = 0; k < 3; k++) out[k] = a[k] - b[k];
					return out;
				};
			}
			case "PVEC_TYPE_FLOAT_COMPONENTS": {
				FloatInput fx = floatInput(m.get("m_FloatComponentX"), 0, c), fy = floatInput(m.get("m_FloatComponentY"), 0, c),
					fz = floatInput(m.get("m_FloatComponentZ"), 0, c);
				return (p, sys, out) -> {
					out[0] = fx.get(p, sys);
					out[1] = fy.get(p, sys);
					out[2] = fz.get(p, sys);
					return out;
				};
			}
			case "PVEC_TYPE_FLOAT_INTERP_CLAMPED", "PVEC_TYPE_FLOAT_INTERP_OPEN": {
				FloatInput t = floatInput(m.get("m_FloatInterp"), 0, c);
				float in0 = Kv3.f(m, "m_flInterpInput0", 0), in1 = Kv3.f(m, "m_flInterpInput1", 1);
				float[] o0 = Kv3.vec(m, "m_vInterpOutput0", 0, 0, 0), o1 = Kv3.vec(m, "m_vInterpOutput1", 1, 1, 1);
				boolean clamp = type.endsWith("CLAMPED");
				return (p, sys, out) -> {
					float f = remap(t.get(p, sys), in0, in1);
					if (clamp) f = clamp01(f);
					for (int k = 0; k < 3; k++) out[k] = o0[k] + (o1[k] - o0[k]) * f;
					return out;
				};
			}
			case "PVEC_TYPE_FLOAT_INTERP_GRADIENT": {
				FloatInput t = floatInput(m.get("m_FloatInterp"), 0, c);
				float in0 = Kv3.f(m, "m_flInterpInput0", 0), in1 = Kv3.f(m, "m_flInterpInput1", 1);
				List<Object> stops = Kv3.list(Kv3.map(m, "m_Gradient"), "m_Stops");
				int n = stops.size();
				float[] pos = new float[n];
				float[][] col = new float[n][];
				for (int i = 0; i < n; i++) {
					@SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) stops.get(i);
					pos[i] = Kv3.f(s, "m_flPosition", 0);
					float[] cc = Kv3.vec(s, "m_Color", 255, 255, 255);
					col[i] = new float[] {cc[0] / 255f, cc[1] / 255f, cc[2] / 255f};
				}
				return (p, sys, out) -> {
					if (n == 0) {
						out[0] = out[1] = out[2] = 1;
						return out;
					}
					float g = remap(t.get(p, sys), in0, in1);
					if (g <= pos[0]) {
						System.arraycopy(col[0], 0, out, 0, 3);
						return out;
					}
					if (g >= pos[n - 1]) {
						System.arraycopy(col[n - 1], 0, out, 0, 3);
						return out;
					}
					for (int i = 0; i < n - 1; i++) {
						if (g >= pos[i] && g <= pos[i + 1]) {
							float b = remap(g, pos[i], pos[i + 1]);
							for (int k = 0; k < 3; k++) out[k] = col[i][k] + (col[i + 1][k] - col[i][k]) * b;
							return out;
						}
					}
					System.arraycopy(col[n - 1], 0, out, 0, 3);
					return out;
				};
			}
			case "PVEC_TYPE_RANDOM_UNIFORM", "PVEC_TYPE_RANDOM_UNIFORM_OFFSET": {
				float[] lo = Kv3.vec(m, "m_vRandomMin", 0, 0, 0), hi = Kv3.vec(m, "m_vRandomMax", 0, 0, 0);
				boolean offset = type.endsWith("OFFSET");
				int field = Kv3.i(m, "m_nVectorAttribute", Particle.COLOR);
				return (p, sys, out) -> {
					float[] base = offset && p != null ? p.vector(field) : null;
					for (int k = 0; k < 3; k++) out[k] = lo[k] + (hi[k] - lo[k]) * sys.rng() + (base == null ? 0 : base[k]);
					return out;
				};
			}
			default: {
				c.unsupported("vector input " + type);
				float[] v = Kv3.vec(m, "m_vLiteralValue", x, y, z);
				return new LiteralVec(v[0], v[1], v[2]);
			}
		}
	}

	/** {@code PF_MAP_TYPE_*}: how a float input's raw value becomes its output. */
	static final class Mapping {
		private final String type;
		private final boolean looped;
		private final float mult, in0, in1, out0, out1, notchMin, notchMax, notchOut, notchIn, compare, biasParam;
		private final String biasType, roundType;
		private final Curve curve;

		Mapping(Map<String, Object> m) {
			type = Kv3.s(m, "m_nMapType", "PF_MAP_TYPE_DIRECT");
			looped = "PF_INPUT_MODE_LOOPED".equals(Kv3.s(m, "m_nInputMode", ""));
			mult = Kv3.f(m, "m_flMultFactor", 1);
			float i0 = Kv3.f(m, "m_flInput0", 0), i1 = Kv3.f(m, "m_flInput1", 1), o0 = Kv3.f(m, "m_flOutput0", 0), o1 = Kv3.f(m, "m_flOutput1", 1);
			if (type.equals("PF_MAP_TYPE_REMAP") && i0 > i1) {
				float t = i0;
				i0 = i1;
				i1 = t;
				t = o0;
				o0 = o1;
				o1 = t;
			}
			in0 = i0;
			in1 = i1;
			out0 = o0;
			out1 = o1;
			notchMin = Kv3.f(m, "m_flNotchedRangeMin", 0);
			notchMax = Kv3.f(m, "m_flNotchedRangeMax", 1);
			notchOut = Kv3.f(m, "m_flNotchedOutputOutside", 0);
			notchIn = Kv3.f(m, "m_flNotchedOutputInside", 1);
			compare = Kv3.f(m, "m_flCompareValue", 0);
			biasParam = Kv3.f(m, "m_flBiasParameter", 0);
			biasType = Kv3.s(m, "m_nBiasType", "PF_BIAS_TYPE_STANDARD");
			roundType = Kv3.s(m, "m_nRoundType", "PF_ROUND_TYPE_NEAREST");
			curve = type.equals("PF_MAP_TYPE_CURVE") ? new Curve(Kv3.map(m, "m_Curve"), looped) : null;
		}

		float apply(float v) {
			switch (type) {
				case "PF_MAP_TYPE_MULT":
					return v * mult;
				case "PF_MAP_TYPE_REMAP": {
					float x = wrap(v);
					if (in0 == in1) return x >= in1 ? out1 : out0;
					return clampRange(out0 + (x - in0) * (out1 - out0) / (in1 - in0), out0, out1);
				}
				case "PF_MAP_TYPE_REMAP_BIASED": {
					float x = Math.min(1, remap(wrap(v), in0, in1));
					return clampRange(out0 + (out1 - out0) * bias(x, biasParam, biasType), out0, out1);
				}
				case "PF_MAP_TYPE_CURVE":
					return curve.evaluate(v);
				case "PF_MAP_TYPE_NOTCHED":
					return v >= notchMin && v <= notchMax ? notchIn : notchOut;
				case "PF_MAP_TYPE_ROUND":
					return switch (roundType) {
						case "PF_ROUND_TYPE_FLOOR" -> (float) Math.floor(v);
						case "PF_ROUND_TYPE_CEIL" -> (float) Math.ceil(v);
						default -> Math.round(v);
					};
				case "PF_MAP_TYPE_MIN":
					return Math.min(v, compare);
				case "PF_MAP_TYPE_MAX":
					return Math.max(v, compare);
				case "PF_MAP_TYPE_MOD":
					return compare == 0 ? v : v % compare;
				default:
					return v;
			}
		}

		private float wrap(float v) {
			if (!looped || in1 == 0) return v;
			float f = v / in1;
			return in1 * (f - (float) Math.floor(f));
		}
	}

	/** A piecewise curve (Hermite segments from each point's slopes), clamped to its domain. */
	static final class Curve {
		private final float[] x, y, slopeIn, slopeOut;
		private final boolean[] linear;
		private final float domainMin, domainMax, rangeMin, rangeMax;
		private final boolean looped;

		Curve(Map<String, Object> m, boolean looped) {
			this.looped = looped;
			List<Object> spline = Kv3.list(m, "m_spline"), tangents = Kv3.list(m, "m_tangents");
			int n = spline.size();
			x = new float[n];
			y = new float[n];
			slopeIn = new float[n];
			slopeOut = new float[n];
			linear = new boolean[n];
			for (int i = 0; i < n; i++) {
				@SuppressWarnings("unchecked") Map<String, Object> pt = (Map<String, Object>) spline.get(i);
				x[i] = Kv3.f(pt, "x", 0);
				y[i] = Kv3.f(pt, "y", 0);
				slopeIn[i] = Kv3.f(pt, "m_flSlopeIncoming", 0);
				slopeOut[i] = Kv3.f(pt, "m_flSlopeOutgoing", 0);
				if (i < tangents.size() && tangents.get(i) instanceof Map<?, ?> t) {
					linear[i] = "CURVE_TANGENT_LINEAR".equals(t.get("m_nOutgoingTangent"));
				}
			}
			float[] dmin = Kv3.vec2(m, "m_vDomainMins"), dmax = Kv3.vec2(m, "m_vDomainMaxs");
			domainMin = dmin[0];
			domainMax = dmax[0];
			rangeMin = dmin[1];
			rangeMax = dmax[1];
		}

		float evaluate(float v) {
			int n = x.length;
			if (n == 0) return 0;
			if (looped && domainMax > domainMin) {
				float span = domainMax - domainMin, f = (v - domainMin) / span;
				v = domainMin + span * (f - (float) Math.floor(f));
			} else {
				v = Math.min(Math.max(v, domainMin), domainMax);
			}
			if (n == 1 || v <= x[0]) return range(y[0]);
			if (v >= x[n - 1]) return range(y[n - 1]);
			for (int i = 0; i < n - 1; i++) {
				if (v >= x[i] && v <= x[i + 1]) {
					float dx = x[i + 1] - x[i];
					if (dx <= 0) return range(y[i + 1]);
					float t = (v - x[i]) / dx;
					if (linear[i]) return range(y[i] + (y[i + 1] - y[i]) * t);
					float t2 = t * t, t3 = t2 * t;
					float h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
					return range(h00 * y[i] + h10 * dx * slopeOut[i] + h01 * y[i + 1] + h11 * dx * slopeIn[i + 1]);
				}
			}
			return range(y[n - 1]);
		}

		private float range(float v) {
			if (rangeMax <= rangeMin) return v;
			return Math.min(Math.max(v, rangeMin), rangeMax);
		}
	}

	/** {@code m_TransformInput}: a control point's position and orientation. */
	record Transform(int cp) {
		static Transform parse(Object node, int fallbackCp) {
			if (node instanceof Map<?, ?> m) {
				@SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) m;
				return new Transform(Kv3.i(mm, "m_nControlPoint", fallbackCp));
			}
			return new Transform(fallbackCp);
		}

		ControlPoint get(FxSystem sys) {
			return sys.cp(cp);
		}
	}

	// ---- maths -----------------------------------------------------------------------------------

	static float remap(float v, float a, float b) {
		return b == a ? (v >= b ? 1 : 0) : (v - a) / (b - a);
	}

	static float clamp01(float v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	static float clampRange(float v, float a, float b) {
		return a <= b ? Math.min(Math.max(v, a), b) : Math.min(Math.max(v, b), a);
	}

	static float len(float[] v) {
		return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
	}

	static float dist(float[] a, float[] b) {
		float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	static float smoothstep(float a, float b, float x) {
		float t = clamp01(remap(x, a, b));
		return t * t * (3 - 2 * t);
	}

	/** Source's bias curve (Schlick's), as particle bias parameters use it. */
	static float bias(float x, float param, String type) {
		if ("PF_BIAS_TYPE_EXPONENTIAL".equals(type)) {
			float e = param >= 0 ? 1 - clamp01(param) : 20 - clamp01(param + 1) * 19;
			if (e <= 0) return 1;
			return x <= 0 ? 0 : (float) Math.pow(x, e);
		}
		float b = clamp01(param * 0.5f + 0.5f);
		if ("PF_BIAS_TYPE_GAIN".equals(type)) return x < 0.5f ? biasCurve(2 * x, b) * 0.5f : 1 - biasCurve(2 - 2 * x, b) * 0.5f;
		return biasCurve(x, b);
	}

	static float biasCurve(float x, float b) {
		if (b <= 0) return 0;
		if (b >= 1) return 1;
		return x / ((1 / b - 2) * (1 - x) + 1);
	}
}
