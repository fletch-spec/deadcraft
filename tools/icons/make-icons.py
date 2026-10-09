# Draws the desktop shortcut icons as isometric pixel-art blocks (needs Pillow):
#   grass-block.ico   "Deadcraft 3 - Show the Minecraft world"
#   server-block.ico  "Deadcraft 1 - Start the movement server"
# Shortcut 2 uses Deadlock's own icon.  python tools/icons/make-icons.py
import random
from pathlib import Path
from PIL import Image, ImageDraw

N = 16  # texels per face edge
S = 512  # drawn large, the .ico writer scales down to each size
ICO_SIZES = [(s, s) for s in (16, 24, 32, 48, 64, 128, 256)]


def shade(c, f):
	return tuple(round(x * f) for x in c)


def sub(p, q):
	return (p[0] - q[0], p[1] - q[1])


def draw_block(top, left, right, out):
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

	face(T, sub(R, T), sub(L, T), top, 1.0)
	face(L, sub(C, L), down, left, 0.82)
	face(C, sub(R, C), down, right, 0.62)
	path = Path(__file__).with_name(out)
	img.save(path, sizes=ICO_SIZES)
	print(f'Wrote {path}')


def grass_block():
	rng = random.Random(7)
	greens = [(93, 155, 51), (106, 170, 60), (82, 140, 44), (118, 183, 70)]
	dirts = [(134, 96, 67), (121, 85, 58), (150, 108, 74), (104, 72, 48), (140, 101, 70)]
	top = [[rng.choice(greens) for _ in range(N)] for _ in range(N)]
	side = [[rng.choice(dirts) for _ in range(N)] for _ in range(N)]
	for u in range(N):  # grass hanging over the side's top edge
		for v in range(3 + rng.choice([0, 0, 1, 1, 2])):
			side[v][u] = rng.choice(greens)
	draw_block(top, side, side, 'grass-block.ico')


def server_block():
	# A rack server: steel casing, front panels of drive bays with status lights
	rng = random.Random(3)
	steel = [(120, 124, 132), (110, 114, 122), (130, 134, 142)]
	frame, bay, slot = (70, 73, 80), (44, 46, 52), (28, 29, 33)
	lights = [(80, 230, 90), (80, 230, 90), (80, 230, 90), (255, 190, 40), (70, 160, 255)]
	top = [[rng.choice(steel) for _ in range(N)] for _ in range(N)]
	for i in range(N):
		for j in (0, N - 1):
			top[i][j] = top[j][i] = frame
	for v in range(4, 12, 2):  # vent grille
		for u in range(4, 12):
			top[v][u] = slot

	def panel(seed):
		r = random.Random(seed)
		tex = [[frame] * N for _ in range(N)]
		for row in range(4):  # four 1U bays, each 3 texels tall with a gap
			y = 1 + row * 4
			for v in range(y, y + 3):
				for u in range(1, N - 1):
					tex[v][u] = bay
			for u in range(2, 10):
				tex[y + 1][u] = slot
			tex[y + 1][11] = r.choice(lights)
			tex[y + 1][13] = r.choice(lights)
		return tex

	draw_block(top, panel(1), panel(2), 'server-block.ico')


grass_block()
server_block()
