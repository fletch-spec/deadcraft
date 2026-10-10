package dev.deadcraft.client.fx;

import java.util.Set;
import java.util.TreeSet;

/** What compiling one effect needs: a stream number per random input, and what isn't supported. */
final class Compiler {
	final String name;
	final Set<String> unsupported = new TreeSet<>();
	private int ordinal = 1;

	Compiler(String name) {
		this.name = name;
	}

	int nextOrdinal() {
		return ordinal++ * 101;
	}

	void unsupported(String what) {
		unsupported.add(what);
	}
}
