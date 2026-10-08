package dev.deadcraft.client;

import dev.deadcraft.protocol.Cube;
import dev.deadcraft.protocol.Double3;
import dev.deadcraft.protocol.Int3;
import dev.deadcraft.protocol.Mapping;
import dev.deadcraft.protocol.McFlags;
import dev.deadcraft.protocol.McState;
import dev.deadcraft.protocol.Proto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Sends the solid blocks around the player to Deadlock, where the plugin builds colliders from them.
 *
 * <p>Blocks are sampled from their collision shapes into half-block cells (so slabs, stairs, fences
 * and doors keep a rough shape), merged into grid-aligned cubes ({@link CubeMerge}) and published
 * only when the set changes.
 */
final class BlockExport {
	/** Horizontal reach and vertical span around the player's feet, in blocks. */
	private static final int RADIUS = 12, BELOW = 8, ABOVE = 16;
	/** Window corners snap to this many blocks, matching the largest cube (16 half blocks). */
	private static final int ALIGN = 8;
	/** Cells per block. */
	private static final int RES = 2;
	/** A shape counts in a half-block cell when it covers at least this much of it on every axis. */
	private static final double MIN_COVER = 0.125;
	private static final int REFRESH_TICKS = 10;

	private long[] lastKeys = new long[0];
	private Double3 lastOffset = Double3.ZERO;
	private Int3 lastBase = Int3.ZERO;
	private int generation;
	private int ticksUntilRefresh;
	private boolean publishedUnlinked;

	/** Called every client tick while a bridge mapping is open. */
	void tick(Minecraft mc, Mapping mapping, boolean linked, Double3 frameOffset) {
		mapping.minecraftHeartbeat();
		if (!linked || mc.player == null || mc.level == null) {
			if (!publishedUnlinked) {
				// Tell Deadlock the set isn't being kept up to date; it keeps its colliders.
				McState state = new McState();
				state.generation = ++generation;
				state.base = lastBase;
				state.frameOffset = lastOffset;
				mapping.writeMcState(state, List.of());
				lastKeys = new long[0];
				publishedUnlinked = true;
			}
			return;
		}
		publishedUnlinked = false;

		BlockPos feet = mc.player.blockPosition();
		Int3 base = new Int3(
			Math.floorDiv(feet.getX() - RADIUS, ALIGN) * ALIGN,
			Math.floorDiv(feet.getY() - BELOW, ALIGN) * ALIGN,
			Math.floorDiv(feet.getZ() - RADIUS, ALIGN) * ALIGN);
		boolean moved = !base.equals(lastBase) || !frameOffset.equals(lastOffset);
		if (!moved && --ticksUntilRefresh > 0) return;
		ticksUntilRefresh = REFRESH_TICKS;

		int sx = Math.floorDiv(feet.getX() + RADIUS, ALIGN) * ALIGN + ALIGN - base.x();
		int sy = Math.floorDiv(feet.getY() + ABOVE, ALIGN) * ALIGN + ALIGN - base.y();
		int sz = Math.floorDiv(feet.getZ() + RADIUS, ALIGN) * ALIGN + ALIGN - base.z();
		int nx = sx * RES, ny = sy * RES, nz = sz * RES;
		boolean[] solid = sample(mc.level, base, sx, sy, sz);
		List<CubeMerge.Cube> merged = new ArrayList<>();
		int buried = 0;
		for (CubeMerge.Cube c : CubeMerge.merge(solid, nx, ny, nz)) {
			if (CubeMerge.buried(solid, nx, ny, nz, c)) buried++;
			else merged.add(c);
		}

		if (merged.size() > Proto.CUBES_CAPACITY) {
			// Keep the cubes nearest the player.
			double px = (mc.player.getX() - base.x()) * RES, py = (mc.player.getY() - base.y()) * RES, pz = (mc.player.getZ() - base.z()) * RES;
			merged = new ArrayList<>(merged);
			merged.sort(Comparator.comparingDouble(c -> sq(c.x() + c.edge() / 2.0 - px) + sq(c.y() + c.edge() / 2.0 - py) + sq(c.z() + c.edge() / 2.0 - pz)));
			Follow.LOG.warn("Deadcraft: {} cubes around the player ({} buried ones skipped), sending the nearest {}", merged.size(), buried, Proto.CUBES_CAPACITY);
			merged = merged.subList(0, Proto.CUBES_CAPACITY);
		}

		long[] keys = new long[merged.size()];
		for (int i = 0; i < keys.length; i++) {
			CubeMerge.Cube c = merged.get(i);
			keys[i] = ((long) (c.x() + base.x() * RES) & 0xFFFFFF) << 40 | ((long) (c.y() + base.y() * RES) & 0xFFFF) << 24
				| ((long) (c.z() + base.z() * RES) & 0xFFFFFF) | (long) Integer.numberOfTrailingZeros(c.edge()) << 37;
		}
		Arrays.sort(keys);
		if (!moved && Arrays.equals(keys, lastKeys)) return;

		List<Cube> cubes = new ArrayList<>(merged.size());
		for (CubeMerge.Cube c : merged) {
			Cube cube = new Cube();
			cube.x = (short) c.x();
			cube.y = (short) c.y();
			cube.z = (short) c.z();
			cube.edge = c.edge();
			cubes.add(cube);
		}
		McState state = new McState();
		state.flags = McFlags.LINKED;
		state.generation = ++generation;
		state.base = base;
		state.frameOffset = frameOffset;
		mapping.writeMcState(state, cubes);
		lastKeys = keys;
		lastBase = base;
		lastOffset = frameOffset;
	}

	/** Solid half-block cells in the window; {@code [(x * ny + y) * nz + z]} with n = blocks * RES. */
	private static boolean[] sample(ClientLevel level, Int3 base, int sx, int sy, int sz) {
		int nx = sx * RES, ny = sy * RES, nz = sz * RES;
		boolean[] solid = new boolean[nx * ny * nz];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int bx = 0; bx < sx; bx++) {
			for (int by = 0; by < sy; by++) {
				for (int bz = 0; bz < sz; bz++) {
					pos.set(base.x() + bx, base.y() + by, base.z() + bz);
					BlockState state = level.getBlockState(pos);
					if (state.isAir()) continue;
					VoxelShape shape = state.getCollisionShape(level, pos);
					if (shape.isEmpty()) continue;
					if (Block.isShapeFullBlock(shape)) {
						fill(solid, ny, nz, bx * RES, by * RES, bz * RES, RES, RES, RES, nx);
						continue;
					}
					for (AABB box : shape.toAabbs()) {
						// Cells this box covers by at least MIN_COVER; shapes may reach past their block (fences).
						int x0 = cellFrom(box.minX), x1 = cellTo(box.maxX);
						int y0 = cellFrom(box.minY), y1 = cellTo(box.maxY);
						int z0 = cellFrom(box.minZ), z1 = cellTo(box.maxZ);
						fill(solid, ny, nz, bx * RES + x0, by * RES + y0, bz * RES + z0, x1 - x0, y1 - y0, z1 - z0, nx);
					}
				}
			}
		}
		return solid;
	}

	/** First cell (in the block's local cells) a box starting at {@code min} covers enough: (i + 1) - c >= cover. */
	private static int cellFrom(double min) {
		double c = min * RES;
		return (int) Math.ceil(c - (1 - MIN_COVER * RES) - 1e-9);
	}

	/** One past the last cell a box ending at {@code max} covers enough. */
	private static int cellTo(double max) {
		double c = max * RES;
		return (int) Math.floor(c - MIN_COVER * RES + 1e-9) + 1;
	}

	private static void fill(boolean[] g, int ny, int nz, int x0, int y0, int z0, int dx, int dy, int dz, int nx) {
		for (int x = Math.max(0, x0); x < Math.min(nx, x0 + dx); x++) {
			for (int y = Math.max(0, y0); y < Math.min(ny, y0 + dy); y++) {
				int row = (x * ny + y) * nz;
				for (int z = Math.max(0, z0); z < Math.min(nz, z0 + dz); z++) g[row + z] = true;
			}
		}
	}

	private static double sq(double v) {
		return v * v;
	}
}
