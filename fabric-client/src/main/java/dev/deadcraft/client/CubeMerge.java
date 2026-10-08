package dev.deadcraft.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers the solid cells of a voxel grid with non-overlapping cubes of edge 16, 8, 4, 2 or 1 cells,
 * each at a position that is a multiple of its edge.
 *
 * <p>Grid alignment matters more than the cube count: a cube only changes when the cells inside it
 * change, so as the player walks only the cubes at the edges of the window are added or removed and
 * the Deadlock collider pool barely moves. Callers must place the grid origin on a multiple of 16
 * cells in world space for that to hold.
 */
public final class CubeMerge {
	public static final int[] EDGES = {16, 8, 4, 2, 1};

	/** A cube: minimum corner and edge, in cells relative to the grid origin. */
	public record Cube(int x, int y, int z, int edge) {}

	private CubeMerge() {}

	/** {@code solid[(x * ny + y) * nz + z]}; the grid is nx by ny by nz cells. */
	public static List<Cube> merge(boolean[] solid, int nx, int ny, int nz) {
		boolean[] left = solid.clone();
		List<Cube> cubes = new ArrayList<>();
		for (int e : EDGES) {
			for (int x = 0; x + e <= nx; x += e) {
				for (int y = 0; y + e <= ny; y += e) {
					for (int z = 0; z + e <= nz; z += e) {
						if (allSet(left, nx, ny, nz, x, y, z, e)) {
							clear(left, ny, nz, x, y, z, e);
							cubes.add(new Cube(x, y, z, e));
						}
					}
				}
			}
		}
		return cubes;
	}

	private static boolean allSet(boolean[] g, int nx, int ny, int nz, int x0, int y0, int z0, int e) {
		for (int x = x0; x < x0 + e; x++) {
			for (int y = y0; y < y0 + e; y++) {
				int row = (x * ny + y) * nz;
				for (int z = z0; z < z0 + e; z++) {
					if (!g[row + z]) return false;
				}
			}
		}
		return true;
	}

	private static void clear(boolean[] g, int ny, int nz, int x0, int y0, int z0, int e) {
		for (int x = x0; x < x0 + e; x++) {
			for (int y = y0; y < y0 + e; y++) {
				int row = (x * ny + y) * nz;
				for (int z = z0; z < z0 + e; z++) g[row + z] = false;
			}
		}
	}
}
