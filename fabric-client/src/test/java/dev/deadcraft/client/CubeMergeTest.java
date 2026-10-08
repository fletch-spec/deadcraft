package dev.deadcraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CubeMergeTest {
	private static boolean[] grid(int nx, int ny, int nz) {
		return new boolean[nx * ny * nz];
	}

	private static void set(boolean[] g, int ny, int nz, int x, int y, int z) {
		g[(x * ny + y) * nz + z] = true;
	}

	@Test
	void fullGridIsOneBigCube() {
		boolean[] g = grid(16, 16, 16);
		java.util.Arrays.fill(g, true);
		assertEquals(List.of(new CubeMerge.Cube(0, 0, 0, 16)), CubeMerge.merge(g, 16, 16, 16));
	}

	@Test
	void coversExactlyTheSolidCells() {
		int nx = 32, ny = 16, nz = 32;
		boolean[] g = grid(nx, ny, nz);
		java.util.Random random = new java.util.Random(7);
		for (int x = 0; x < nx; x++)
			for (int y = 0; y < ny; y++)
				for (int z = 0; z < nz; z++)
					if (y < 8 || random.nextInt(4) == 0) set(g, ny, nz, x, y, z);

		boolean[] covered = grid(nx, ny, nz);
		for (CubeMerge.Cube c : CubeMerge.merge(g, nx, ny, nz)) {
			assertEquals(0, c.x() % c.edge());
			assertEquals(0, c.y() % c.edge());
			assertEquals(0, c.z() % c.edge());
			for (int x = c.x(); x < c.x() + c.edge(); x++)
				for (int y = c.y(); y < c.y() + c.edge(); y++)
					for (int z = c.z(); z < c.z() + c.edge(); z++) {
						int i = (x * ny + y) * nz + z;
						assertTrue(g[i], "cube covers an empty cell");
						assertTrue(!covered[i], "cubes overlap");
						covered[i] = true;
					}
		}
		assertTrue(java.util.Arrays.equals(g, covered), "some solid cells are not covered");
	}

	@Test
	void onlyCubesWithAnOpenFaceAreKept() {
		int n = 48;
		boolean[] g = grid(n, n, n);
		for (int x = 0; x < n; x++)
			for (int y = 0; y < n; y++)
				for (int z = 0; z < n; z++)
					if (y < 40) set(g, n, n, x, y, z);  // solid up to y = 40, air above
		List<CubeMerge.Cube> cubes = CubeMerge.merge(g, n, n, n);
		// The cube at the centre of the solid mass is surrounded; the one at the surface isn't.
		CubeMerge.Cube deep = cubes.stream().filter(c -> c.x() == 16 && c.y() == 16 && c.z() == 16).findFirst().orElseThrow();
		CubeMerge.Cube top = cubes.stream().filter(c -> c.y() + c.edge() == 40 && c.x() == 16 && c.z() == 16).findFirst().orElseThrow();
		assertTrue(CubeMerge.buried(g, n, n, n, deep));
		assertTrue(!CubeMerge.buried(g, n, n, n, top));
		// At the grid's edge we can't see past it, so it's never buried.
		CubeMerge.Cube corner = cubes.stream().filter(c -> c.x() == 0 && c.y() == 0 && c.z() == 0).findFirst().orElseThrow();
		assertTrue(!CubeMerge.buried(g, n, n, n, corner));
	}

	@Test
	void aSolidFloorBecomesFewLargeCubes() {
		// Superflat: a 4-block (8-cell) floor across a 32 x 32 block window is (64 / 8)^2 cubes of edge 8.
		int nx = 64, ny = 48, nz = 64;
		boolean[] g = grid(nx, ny, nz);
		for (int x = 0; x < nx; x++)
			for (int y = 0; y < 8; y++)
				for (int z = 0; z < nz; z++) set(g, ny, nz, x, y, z);
		List<CubeMerge.Cube> cubes = CubeMerge.merge(g, nx, ny, nz);
		assertEquals(64, cubes.size());
		assertTrue(cubes.stream().allMatch(c -> c.edge() == 8));
	}
}
