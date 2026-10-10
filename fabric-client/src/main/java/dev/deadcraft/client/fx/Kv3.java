package dev.deadcraft.client.fx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Valve's KeyValues3 text format, as the effects pack holds each particle system (hero-export's FxPack):
 * objects become {@code Map<String, Object>} (in order), arrays {@code List<Object>}, numbers {@code Double},
 * booleans {@code Boolean}, strings and typed strings ({@code resource:"..."}) {@code String}, null null.
 * Plus typed readers that fall back to Deadlock's defaults for absent keys.
 */
public final class Kv3 {
	private final String s;
	private int p;

	private Kv3(String s) {
		this.s = s;
	}

	/** Parses a whole document (its {@code <!-- kv3 ... -->} header is skipped). */
	public static Map<String, Object> parse(String text) {
		Kv3 k = new Kv3(text);
		k.skip();
		Object root = k.value();
		if (!(root instanceof Map<?, ?>)) throw new IllegalArgumentException("kv3: root is not an object");
		@SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) root;
		return map;
	}

	private void skip() {
		while (p < s.length()) {
			char c = s.charAt(p);
			if (Character.isWhitespace(c)) {
				p++;
			} else if (s.startsWith("<!--", p)) {
				int end = s.indexOf("-->", p);
				p = end < 0 ? s.length() : end + 3;
			} else if (s.startsWith("//", p)) {
				while (p < s.length() && s.charAt(p) != '\n') p++;
			} else if (s.startsWith("/*", p)) {
				int end = s.indexOf("*/", p);
				p = end < 0 ? s.length() : end + 2;
			} else {
				return;
			}
		}
	}

	private Object value() {
		skip();
		if (p >= s.length()) throw error("unexpected end");
		char c = s.charAt(p);
		if (c == '{') return object();
		if (c == '[') return array();
		if (c == '"') return string();
		if (c == '#' && p + 1 < s.length() && s.charAt(p + 1) == '[') {
			// A binary blob: kept as its hex text.
			int end = s.indexOf(']', p);
			String hex = s.substring(p + 2, end).trim();
			p = end + 1;
			return hex;
		}
		String word = word();
		skip();
		if (p < s.length() && s.charAt(p) == ':' && !word.isEmpty() && !Character.isDigit(word.charAt(0)) && word.charAt(0) != '-') {
			// A typed value: resource:"...", resource_name:"...", soundevent:"...", ...
			p++;
			skip();
			return value();
		}
		return switch (word) {
			case "true" -> Boolean.TRUE;
			case "false" -> Boolean.FALSE;
			case "null" -> null;
			default -> {
				try {
					yield Double.parseDouble(word);
				} catch (NumberFormatException e) {
					yield word;
				}
			}
		};
	}

	private String word() {
		int start = p;
		while (p < s.length()) {
			char c = s.charAt(p);
			if (Character.isWhitespace(c) || c == ',' || c == ']' || c == '}' || c == '=' || c == ':' || c == '{' || c == '[') break;
			p++;
		}
		if (start == p) throw error("expected a value");
		return s.substring(start, p);
	}

	private String string() {
		if (s.startsWith("\"\"\"", p)) {
			int end = s.indexOf("\"\"\"", p + 3);
			String out = s.substring(p + 3, end);
			p = end + 3;
			return out;
		}
		StringBuilder out = new StringBuilder();
		p++;
		while (p < s.length()) {
			char c = s.charAt(p++);
			if (c == '"') return out.toString();
			if (c == '\\' && p < s.length()) {
				char e = s.charAt(p++);
				out.append(switch (e) {
					case 'n' -> '\n';
					case 't' -> '\t';
					default -> e;
				});
			} else {
				out.append(c);
			}
		}
		throw error("unterminated string");
	}

	private Map<String, Object> object() {
		Map<String, Object> map = new LinkedHashMap<>();
		p++;
		while (true) {
			skip();
			if (p >= s.length()) throw error("unterminated object");
			if (s.charAt(p) == '}') {
				p++;
				return map;
			}
			if (s.charAt(p) == ',') {
				p++;
				continue;
			}
			String key = s.charAt(p) == '"' ? string() : word();
			skip();
			if (p >= s.length() || s.charAt(p) != '=') throw error("expected = after " + key);
			p++;
			map.put(key, value());
		}
	}

	private List<Object> array() {
		List<Object> list = new ArrayList<>();
		p++;
		while (true) {
			skip();
			if (p >= s.length()) throw error("unterminated array");
			char c = s.charAt(p);
			if (c == ']') {
				p++;
				return list;
			}
			if (c == ',') {
				p++;
				continue;
			}
			list.add(value());
		}
	}

	private IllegalArgumentException error(String what) {
		int line = 1;
		for (int i = 0; i < Math.min(p, s.length()); i++) if (s.charAt(i) == '\n') line++;
		return new IllegalArgumentException("kv3: " + what + " at line " + line);
	}

	// ---- typed readers ---------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	public static Map<String, Object> map(Map<String, Object> m, String key) {
		Object v = m == null ? null : m.get(key);
		return v instanceof Map<?, ?> ? (Map<String, Object>) v : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(Map<String, Object> m, String key) {
		Object v = m == null ? null : m.get(key);
		return v instanceof List<?> ? (List<Object>) v : List.of();
	}

	public static float f(Map<String, Object> m, String key, float fallback) {
		Object v = m == null ? null : m.get(key);
		if (v instanceof Double d) return d.floatValue();
		if (v instanceof Boolean b) return b ? 1 : 0;
		return fallback;
	}

	public static int i(Map<String, Object> m, String key, int fallback) {
		Object v = m == null ? null : m.get(key);
		return v instanceof Double d ? (int) Math.round(d) : fallback;
	}

	public static boolean b(Map<String, Object> m, String key, boolean fallback) {
		Object v = m == null ? null : m.get(key);
		if (v instanceof Boolean b) return b;
		if (v instanceof Double d) return d != 0;
		return fallback;
	}

	public static String s(Map<String, Object> m, String key, String fallback) {
		Object v = m == null ? null : m.get(key);
		return v instanceof String str ? str : fallback;
	}

	/** A 3-vector (or the first three of a colour); fallback when absent. */
	public static float[] vec(Map<String, Object> m, String key, float x, float y, float z) {
		List<Object> l = list(m, key);
		if (l.size() < 3) return new float[] {x, y, z};
		return new float[] {num(l.get(0)), num(l.get(1)), num(l.get(2))};
	}

	/** A 2-vector; zeros when absent. */
	public static float[] vec2(Map<String, Object> m, String key) {
		List<Object> l = list(m, key);
		return l.size() < 2 ? new float[2] : new float[] {num(l.get(0)), num(l.get(1))};
	}

	public static float num(Object o) {
		return o instanceof Double d ? d.floatValue() : 0;
	}
}
