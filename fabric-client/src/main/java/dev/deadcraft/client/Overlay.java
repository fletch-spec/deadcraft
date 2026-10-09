package dev.deadcraft.client;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;

/**
 * Overlay mode: Minecraft's window sits exactly over Deadlock's, borderless, always on top, click-through
 * and never activated, so you see Minecraft while Deadlock keeps the keyboard and mouse. Deadlock is
 * never touched: this only moves and restyles Minecraft's own window (user32 through the Foreign
 * Function API).
 *
 * <p>F6 hands input to Minecraft and opens chat, Esc opens its pause menu (Quit is there); when
 * Minecraft's screen closes, input goes back to
 * Deadlock. Screens Minecraft opens itself (death) also hand it input. Deadlock must run in a window or
 * borderless window: nothing can be drawn over exclusive fullscreen.
 */
final class Overlay {
	private static final String DEADLOCK_TITLE = "Deadlock";
	private static final int VK_F6 = 0x75, VK_ESCAPE = 0x1B;

	private static final int GWL_STYLE = -16, GWL_EXSTYLE = -20;
	private static final long WS_POPUP = 0x80000000L, WS_VISIBLE = 0x10000000L, WS_OVERLAPPEDWINDOW = 0x00CF0000L;
	private static final long WS_EX_LAYERED = 0x80000, WS_EX_TRANSPARENT = 0x20, WS_EX_NOACTIVATE = 0x08000000L;
	private static final long HWND_TOPMOST = -1, HWND_NOTOPMOST = -2;
	private static final int SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOACTIVATE = 0x10, SWP_FRAMECHANGED = 0x20, SWP_SHOWWINDOW = 0x40;
	private static final int LWA_ALPHA = 0x2;
	private static final long PLACE_INTERVAL_NS = 250_000_000L;

	private static final MethodHandle FIND_WINDOW, FIND_WINDOW_EX, GET_WINDOW_THREAD_PROCESS_ID, GET_WINDOW_LONG_PTR,
		SET_WINDOW_LONG_PTR, SET_WINDOW_POS, SET_LAYERED_WINDOW_ATTRIBUTES, GET_CLIENT_RECT, CLIENT_TO_SCREEN, GET_WINDOW_RECT,
		GET_ASYNC_KEY_STATE, GET_FOREGROUND_WINDOW, SET_FOREGROUND_WINDOW, ATTACH_THREAD_INPUT, GET_CURRENT_THREAD_ID,
		GET_CURRENT_PROCESS_ID, IS_WINDOW, IS_ICONIC, IS_WINDOW_VISIBLE, GET_WINDOW_TEXT_LENGTH;

	static {
		Linker linker = Linker.nativeLinker();
		SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
		SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		FIND_WINDOW = linker.downcallHandle(user32.find("FindWindowW").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
		FIND_WINDOW_EX = linker.downcallHandle(user32.find("FindWindowExW").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
		GET_WINDOW_THREAD_PROCESS_ID = linker.downcallHandle(user32.find("GetWindowThreadProcessId").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		GET_WINDOW_LONG_PTR = linker.downcallHandle(user32.find("GetWindowLongPtrW").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
		SET_WINDOW_LONG_PTR = linker.downcallHandle(user32.find("SetWindowLongPtrW").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG));
		SET_WINDOW_POS = linker.downcallHandle(user32.find("SetWindowPos").orElseThrow(),
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
		// SetLayeredWindowAttributes(HWND, COLORREF key, BYTE alpha, DWORD flags)
		SET_LAYERED_WINDOW_ATTRIBUTES = linker.downcallHandle(user32.find("SetLayeredWindowAttributes").orElseThrow(),
			FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_BYTE, JAVA_INT));
		GET_CLIENT_RECT = linker.downcallHandle(user32.find("GetClientRect").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		CLIENT_TO_SCREEN = linker.downcallHandle(user32.find("ClientToScreen").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		GET_WINDOW_RECT = linker.downcallHandle(user32.find("GetWindowRect").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		GET_ASYNC_KEY_STATE = linker.downcallHandle(user32.find("GetAsyncKeyState").orElseThrow(), FunctionDescriptor.of(JAVA_SHORT, JAVA_INT));
		GET_FOREGROUND_WINDOW = linker.downcallHandle(user32.find("GetForegroundWindow").orElseThrow(), FunctionDescriptor.of(ADDRESS));
		SET_FOREGROUND_WINDOW = linker.downcallHandle(user32.find("SetForegroundWindow").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
		ATTACH_THREAD_INPUT = linker.downcallHandle(user32.find("AttachThreadInput").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
		GET_CURRENT_THREAD_ID = linker.downcallHandle(kernel32.find("GetCurrentThreadId").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
		GET_CURRENT_PROCESS_ID = linker.downcallHandle(kernel32.find("GetCurrentProcessId").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
		IS_WINDOW = linker.downcallHandle(user32.find("IsWindow").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
		IS_ICONIC = linker.downcallHandle(user32.find("IsIconic").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
		IS_WINDOW_VISIBLE = linker.downcallHandle(user32.find("IsWindowVisible").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
		GET_WINDOW_TEXT_LENGTH = linker.downcallHandle(user32.find("GetWindowTextLengthW").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
	}

	private boolean enabled = true;
	private boolean applied;
	private boolean interactive;
	private boolean f6WasDown, escWasDown;
	/** Esc opened Deadlock's menu behind ours; the next Esc closes that, so it mustn't reopen ours. */
	private boolean deadlockMenuOpen;
	private boolean pauseOpen;
	private MemorySegment mcWindow = MemorySegment.NULL;
	private MemorySegment deadlockWindow = MemorySegment.NULL;
	private long savedStyle, savedExStyle;
	private final int[] savedRect = new int[4];
	private final int[] placedRect = new int[4];
	private long nextPlace;
	private String lastProblem = "";

	String setEnabled(boolean on) {
		enabled = on;
		return on ? "Overlay on: Minecraft covers Deadlock while linked. F6 for Minecraft input." : "Overlay off: separate windows.";
	}

	/** Every frame. {@code linked}: following the Deadlock hero. */
	void update(Minecraft mc, boolean linked) {
		try {
			boolean want = enabled && linked;
			if (want && !applied) apply();
			else if (!want && applied) restore();
			if (!applied) return;

			// Deadlock gone or closed: give the window back.
			if ((int) IS_WINDOW.invoke(deadlockWindow) == 0) {
				problem("Deadlock's window is gone");
				restore();
				return;
			}

			boolean f6Down = ((short) GET_ASYNC_KEY_STATE.invoke(VK_F6) & 0x8000) != 0;
			boolean f6Pressed = f6Down && !f6WasDown;
			f6WasDown = f6Down;
			boolean escDown = ((short) GET_ASYNC_KEY_STATE.invoke(VK_ESCAPE) & 0x8000) != 0;
			boolean escPressed = escDown && !escWasDown;
			escWasDown = escDown;
			boolean screenOpen = mc.gui.screen() != null;
			if (!interactive && escPressed && !screenOpen) {
				if (deadlockMenuOpen) {
					deadlockMenuOpen = false;  // this Esc closed Deadlock's menu
				} else {
					// Esc reaches Deadlock too (its own menu opens behind); Minecraft's pause menu has Quit.
					setInteractive(true);
					mc.gui.setScreen(new PauseScreen(true));
					pauseOpen = true;
				}
			} else if (!interactive && (f6Pressed || screenOpen)) {
				setInteractive(true);
				if (!screenOpen) mc.gui.setScreen(new ChatScreen("", false));
			} else if (interactive && (!screenOpen || f6Pressed)) {
				if (screenOpen) mc.gui.setScreen(null);
				setInteractive(false);
				if (pauseOpen) deadlockMenuOpen = true;
				pauseOpen = false;
			}

			long now = System.nanoTime();
			if (now >= nextPlace) {
				nextPlace = now + PLACE_INTERVAL_NS;
				placeOverDeadlock();
			}
		} catch (Throwable t) {
			problem("overlay error: " + t);
			try {
				restore();
			} catch (Throwable ignored) {
				// already reporting a problem
			}
			enabled = false;
		}
	}

	private void apply() throws Throwable {
		deadlockWindow = findDeadlock();
		mcWindow = findOwnWindow();
		if (deadlockWindow.address() == 0 || mcWindow.address() == 0) {
			problem(deadlockWindow.address() == 0 ? "no window titled \"" + DEADLOCK_TITLE + "\"" : "can't find Minecraft's window");
			return;
		}
		savedStyle = (long) GET_WINDOW_LONG_PTR.invoke(mcWindow, GWL_STYLE);
		savedExStyle = (long) GET_WINDOW_LONG_PTR.invoke(mcWindow, GWL_EXSTYLE);
		readRect(GET_WINDOW_RECT, mcWindow, savedRect);
		long style = (savedStyle & ~WS_OVERLAPPEDWINDOW) | WS_POPUP | WS_VISIBLE;
		long ignoredOld = (long) SET_WINDOW_LONG_PTR.invoke(mcWindow, GWL_STYLE, style);
		applied = true;
		interactive = true;  // so setInteractive(false) below applies the click-through styles
		setInteractive(false);
		placedRect[2] = -1;
		placeOverDeadlock();
		lastProblem = "";
		Follow.LOG.info("Deadcraft: overlay on (Minecraft over Deadlock; F6 for Minecraft input)");
	}

	private void restore() throws Throwable {
		if (!applied) return;
		applied = false;
		interactive = false;
		long ignored = (long) SET_WINDOW_LONG_PTR.invoke(mcWindow, GWL_STYLE, savedStyle);
		ignored = (long) SET_WINDOW_LONG_PTR.invoke(mcWindow, GWL_EXSTYLE, savedExStyle);
		int ok = (int) SET_WINDOW_POS.invoke(mcWindow, MemorySegment.ofAddress(HWND_NOTOPMOST), savedRect[0], savedRect[1],
			savedRect[2] - savedRect[0], savedRect[3] - savedRect[1], SWP_FRAMECHANGED | SWP_NOACTIVATE | SWP_SHOWWINDOW);
		Follow.LOG.info("Deadcraft: overlay off, Minecraft's window restored");
	}

	/** Interactive: Minecraft takes input (chat, inventory). Otherwise click-through and Deadlock has focus. */
	private void setInteractive(boolean on) throws Throwable {
		if (on == interactive) return;
		interactive = on;
		long ex = (long) GET_WINDOW_LONG_PTR.invoke(mcWindow, GWL_EXSTYLE);
		ex = on ? ex & ~(WS_EX_TRANSPARENT | WS_EX_NOACTIVATE) : ex | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE;
		long ignored = (long) SET_WINDOW_LONG_PTR.invoke(mcWindow, GWL_EXSTYLE, ex);
		// A layered window shows nothing until it has attributes: fully opaque.
		int ok = (int) SET_LAYERED_WINDOW_ATTRIBUTES.invoke(mcWindow, 0, (byte) 255, LWA_ALPHA);
		ok = (int) SET_WINDOW_POS.invoke(mcWindow, MemorySegment.ofAddress(HWND_TOPMOST), 0, 0, 0, 0,
			SWP_NOMOVE | SWP_NOSIZE | SWP_FRAMECHANGED | SWP_NOACTIVATE | SWP_SHOWWINDOW);
		focus(on ? mcWindow : deadlockWindow);
	}

	/** Matches Minecraft's window to Deadlock's client area (Deadlock may move or resize). */
	private void placeOverDeadlock() throws Throwable {
		if ((int) IS_ICONIC.invoke(deadlockWindow) != 0) return;
		int[] r = new int[4];
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment rect = arena.allocate(16);
			int ok = (int) GET_CLIENT_RECT.invoke(deadlockWindow, rect);
			MemorySegment point = arena.allocate(8);
			ok = (int) CLIENT_TO_SCREEN.invoke(deadlockWindow, point);
			int x = point.get(JAVA_INT, 0), y = point.get(JAVA_INT, 4);
			r[0] = x;
			r[1] = y;
			r[2] = x + rect.get(JAVA_INT, 8);
			r[3] = y + rect.get(JAVA_INT, 12);
		}
		if (java.util.Arrays.equals(r, placedRect)) return;
		System.arraycopy(r, 0, placedRect, 0, 4);
		int ok = (int) SET_WINDOW_POS.invoke(mcWindow, MemorySegment.ofAddress(HWND_TOPMOST), r[0], r[1], r[2] - r[0], r[3] - r[1],
			SWP_NOACTIVATE | SWP_SHOWWINDOW | SWP_FRAMECHANGED);
		Follow.LOG.info("Deadcraft: overlay placed at {},{} size {}x{}", r[0], r[1], r[2] - r[0], r[3] - r[1]);
	}

	/**
	 * Windows only lets the foreground process change the foreground window. Attaching to the current
	 * foreground thread's input makes us count as it for this call.
	 */
	private static void focus(MemorySegment window) throws Throwable {
		MemorySegment fg = (MemorySegment) GET_FOREGROUND_WINDOW.invoke();
		int fgThread = fg.address() == 0 ? 0 : (int) GET_WINDOW_THREAD_PROCESS_ID.invoke(fg, MemorySegment.NULL);
		int ourThread = (int) GET_CURRENT_THREAD_ID.invoke();
		boolean attached = fgThread != 0 && fgThread != ourThread && (int) ATTACH_THREAD_INPUT.invoke(ourThread, fgThread, 1) != 0;
		try {
			int ok = (int) SET_FOREGROUND_WINDOW.invoke(window);
		} finally {
			if (attached) {
				int ok = (int) ATTACH_THREAD_INPUT.invoke(ourThread, fgThread, 0);
			}
		}
	}

	/**
	 * Deadlock's game window. Several top-level windows can carry the title (helper and hidden ones),
	 * so take the visible one with the largest client area.
	 */
	private static MemorySegment findDeadlock() throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment title = arena.allocateFrom(DEADLOCK_TITLE, StandardCharsets.UTF_16LE);
			MemorySegment rect = arena.allocate(16);
			MemorySegment best = MemorySegment.NULL;
			long bestArea = 0;
			MemorySegment w = MemorySegment.NULL;
			while (true) {
				w = (MemorySegment) FIND_WINDOW_EX.invoke(MemorySegment.NULL, w, MemorySegment.NULL, title);
				if (w.address() == 0) break;
				int visible = (int) IS_WINDOW_VISIBLE.invoke(w);
				int ok = (int) GET_CLIENT_RECT.invoke(w, rect);
				long area = (long) rect.get(JAVA_INT, 8) * rect.get(JAVA_INT, 12);
				Follow.LOG.info("Deadcraft: overlay candidate window 0x{} visible {} client {}x{}", Long.toHexString(w.address()),
					visible != 0, rect.get(JAVA_INT, 8), rect.get(JAVA_INT, 12));
				if (visible != 0 && area > bestArea) {
					best = w;
					bestArea = area;
				}
			}
			return best;
		}
	}

	/**
	 * This process's main window: the visible top-level window with a title. Walks every top-level
	 * window rather than relying on GLFW's window class name, which changes between LWJGL versions.
	 */
	private static MemorySegment findOwnWindow() throws Throwable {
		int pid = (int) GET_CURRENT_PROCESS_ID.invoke();
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment pidOut = arena.allocate(JAVA_INT);
			MemorySegment w = MemorySegment.NULL;
			while (true) {
				w = (MemorySegment) FIND_WINDOW_EX.invoke(MemorySegment.NULL, w, MemorySegment.NULL, MemorySegment.NULL);
				if (w.address() == 0) return MemorySegment.NULL;
				int thread = (int) GET_WINDOW_THREAD_PROCESS_ID.invoke(w, pidOut);
				if (pidOut.get(JAVA_INT, 0) != pid) continue;
				if ((int) IS_WINDOW_VISIBLE.invoke(w) != 0 && (int) GET_WINDOW_TEXT_LENGTH.invoke(w) > 0) return w;
			}
		}
	}

	private static void readRect(MethodHandle getter, MemorySegment window, int[] out) throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment rect = arena.allocate(16);
			int ok = (int) getter.invoke(window, rect);
			for (int i = 0; i < 4; i++) out[i] = rect.get(JAVA_INT, i * 4L);
		}
	}

	private void problem(String text) {
		if (!text.equals(lastProblem)) Follow.LOG.warn("Deadcraft: overlay: {}", text);
		lastProblem = text;
	}
}
