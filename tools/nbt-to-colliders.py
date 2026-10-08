"""Convert a Minecraft structure-block .nbt into box colliders for the collider-test plugin.

    python tools/nbt-to-colliders.py courses/feel1.nbt

Writes courses/<name>.json and copies it to <Deadlock>\\game\\bin\\win64\\deadcraft\\courses, next to
deadworks.exe, where the plugin's /dc_build command reads it. Set DEADLOCK_DIR if Deadlock is elsewhere.

Blocks are voxelised at half-block resolution so slabs and stairs keep their shape, then merged
greedily into the largest solid cubes that fit (8 blocks down to half a block), because the only
collider that works is a uniformly scaled crate (docs/collider-test.md). Output coordinates are
Minecraft blocks relative to the structure origin: x east, y up, z south.

Spike code for the M1 feel test. M4 does this in the Fabric client from live world data.
"""
import json
import os
import shutil
import sys
from pathlib import Path

import nbtlib
import numpy as np

RES = 2  # voxels per block
SIZES = [16, 8, 4, 2, 1]  # cube edge in voxels, largest first

# Blocks with no collision. Anything not listed (and not matched below) is a full block.
NON_SOLID = {
    "air", "cave_air", "void_air", "water", "lava", "short_grass", "tall_grass", "fern", "large_fern",
    "dead_bush", "vine", "snow", "light", "structure_void", "cobweb", "sugar_cane", "kelp", "seagrass",
}
NON_SOLID_SUFFIXES = (
    "_sapling", "_flower", "torch", "_button", "_pressure_plate", "rail", "_sign", "_banner", "_carpet",
    "tulip", "_mushroom", "_roots", "_coral_fan",
)
NON_SOLID_PLANTS = {"dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
                    "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony", "carpet"}


def block_shape(name: str, props: dict) -> np.ndarray | None:
    """2x2x2 occupancy [x, y, z] for one block, or None if it has no collision."""
    short = name.split(":", 1)[-1]
    if short in NON_SOLID or short in NON_SOLID_PLANTS or short.endswith(NON_SOLID_SUFFIXES):
        return None
    shape = np.zeros((RES, RES, RES), dtype=bool)
    if short.endswith("_slab"):
        kind = props.get("type", "bottom")
        if kind == "double":
            shape[:] = True
        else:
            shape[:, 0 if kind == "bottom" else 1, :] = True
        return shape
    if short.endswith("_stairs"):
        # Straight stairs only; inner and outer corners are approximated as straight.
        base, step = (0, 1) if props.get("half", "bottom") == "bottom" else (1, 0)
        shape[:, base, :] = True
        facing = props.get("facing", "north")
        if facing == "east":
            shape[1, step, :] = True
        elif facing == "west":
            shape[0, step, :] = True
        elif facing == "south":
            shape[:, step, 1] = True
        else:
            shape[:, step, 0] = True
        return shape
    shape[:] = True
    return shape


def voxelise(path: Path) -> tuple[np.ndarray, list[int]]:
    nbt = nbtlib.load(path)
    size = [int(v) for v in nbt["size"]]
    palette = []
    for entry in nbt["palette"]:
        # 26.x writes "id"/"properties"; older versions wrote "Name"/"Properties".
        name = str(entry.get("id", entry.get("Name", "minecraft:air")))
        props = {str(k): str(v) for k, v in entry.get("properties", entry.get("Properties", {})).items()}
        palette.append(block_shape(name, props))
    grid = np.zeros((size[0] * RES, size[1] * RES, size[2] * RES), dtype=bool)
    for block in nbt["blocks"]:
        shape = palette[int(block["state"])]
        if shape is None:
            continue
        x, y, z = (int(v) * RES for v in block["pos"])
        grid[x:x + RES, y:y + RES, z:z + RES] |= shape
    return grid, size


def merge_cubes(grid: np.ndarray) -> list[tuple[int, int, int, int]]:
    """Greedy cover of the solid voxels with non-overlapping cubes, largest first."""
    free = grid.copy()
    cubes = []
    for s in SIZES:
        if s > min(free.shape):
            continue
        # Summed-volume table to find candidate positions where an s-cube is entirely free.
        sat = np.zeros(tuple(d + 1 for d in free.shape), dtype=np.int32)
        sat[1:, 1:, 1:] = free.cumsum(0).cumsum(1).cumsum(2)
        full = (sat[s:, s:, s:] - sat[:-s, s:, s:] - sat[s:, :-s, s:] - sat[s:, s:, :-s]
                + sat[:-s, :-s, s:] + sat[:-s, s:, :-s] + sat[s:, :-s, :-s] - sat[:-s, :-s, :-s]) == s ** 3
        for x, y, z in zip(*np.nonzero(full)):
            if free[x:x + s, y:y + s, z:z + s].all():
                free[x:x + s, y:y + s, z:z + s] = False
                cubes.append((int(x), int(y), int(z), s))
    assert not free.any()
    return cubes


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    src = Path(sys.argv[1])
    grid, size = voxelise(src)
    cubes = merge_cubes(grid)
    out = {
        "name": src.stem,
        "source": src.name,
        "sizeBlocks": size,
        # Each box: min corner and edge length, in blocks, relative to the structure origin.
        "boxes": [[x / RES, y / RES, z / RES, s / RES] for x, y, z, s in cubes],
    }
    dest = src.with_suffix(".json")
    dest.write_text(json.dumps(out, separators=(",", ":")))
    deadlock = Path(os.environ.get("DEADLOCK_DIR", r"C:\Program Files (x86)\Steam\steamapps\common\Deadlock"))
    install = deadlock / "game" / "bin" / "win64" / "deadcraft" / "courses"
    install.mkdir(parents=True, exist_ok=True)
    shutil.copy(dest, install / dest.name)
    by_size = {}
    for *_, s in cubes:
        by_size[s / RES] = by_size.get(s / RES, 0) + 1
    print(f"{src.name}: {int(grid.sum())} solid voxels -> {len(cubes)} colliders "
          f"({', '.join(f'{n} x {k:g}' for k, n in sorted(by_size.items(), reverse=True))} blocks)")
    print(f"wrote {dest} and {install / dest.name}")


if __name__ == "__main__":
    main()
