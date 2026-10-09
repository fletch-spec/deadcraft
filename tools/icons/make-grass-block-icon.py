# Draws tools/icons/grass-block.ico, the icon for the "Deadcraft 3 - Show the Minecraft world" shortcut:
# an isometric pixel-art grass block. Needs Pillow.  python tools/icons/make-grass-block-icon.py
import random
from pathlib import Path
from PIL import Image, ImageDraw

N = 16  # texels per face edge
rng = random.Random(7)

greens = [(93, 155, 51), (106, 170, 60), (82, 140, 44), (118, 183, 70)]
dirts = [(134, 96, 67), (121, 85, 58), (150, 108, 74), (104, 72, 48), (140, 101, 70)]

top = [[rng.choice(greens) for _ in range(N)] for _ in range(N)]
side = [[rng.choice(dirts) for _ in range(N)] for _ in range(N)]
for u in range(N):  # grass hanging over the side's top edge
	for v in range(3 + rng.choice([0, 0, 1, 1, 2])):
		side[v][u] = rng.choice(greens)


def shade(c, f):
	return tuple(round(x * f) for x in c)


S = 512  # drawn large, the .ico writer scales down to each size
hw, rise = 0.39 * S, 0.226 * S  # half width and rise of the top rhombus; vertical edge = 2 * rise
cx, top_y = S / 2, 0.047 * S
T = (cx, top_y)
L = (cx - hw, top_y + rise)
C = (cx, top_y + 2 * rise)
R = (cx + hw, top_y + rise)
down = (0, 2 * rise)

img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)


def face(origin, du, dv, tex, f):
	# origin + u * du / N + v * dv / N maps texel (u, v) to the screen
	for v in range(N):
		for u in range(N):
			pts = [(origin[0] + (u + a) * du[0] / N + (v + b) * dv[0] / N,
					origin[1] + (u + a) * du[1] / N + (v + b) * dv[1] / N) for a, b in ((0, 0), (1, 0), (1, 1), (0, 1))]
			draw.polygon(pts, fill=shade(tex[v][u], f))


def sub(p, q):
	return (p[0] - q[0], p[1] - q[1])


face(T, sub(R, T), sub(L, T), top, 1.0)
face(L, sub(C, L), down, side, 0.82)
face(C, sub(R, C), down, side, 0.62)

out = Path(__file__).with_name('grass-block.ico')
img.save(out, sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
print(f'Wrote {out}')
