package dev.deadcraft.client;

import static java.lang.foreign.ValueLayout.ADDRESS;
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

/**
 * Reads the mouse's raw counts while Deadlock has focus, so Minecraft's camera can turn the moment the
 * mouse moves instead of waiting for Deadlock's angles to come back through its server (~70 ms).
 *
 * <p>Windows raw input with {@code RIDEV_INPUTSINK} delivers mouse reports to a background window. This
 * only listens: Deadlock gets exactly the same input as before. A raw input registration is per
 * process, and GLFW uses it for Minecraft's own mouse look, so this registers only while Minecraft isn't
 * the foreground window, re-registers if GLFW removed it, and on stop removes only its own registration.
 *
 * <p>The thread pumps a message-only window and records cumulative counts with {@link System#nanoTime}
 * timestamps; {@link #countsAt} answers "how far had the mouse moved by then".
 */
final class RawMouse implements LookPredictor.Counts {
	private static final int WM_INPUT = 0x00FF;
	private static final int RID_INPUT = 0x10000003;
	private static final int RIM_TYPEMOUSE = 0;
	private static final int MOUSE_MOVE_ABSOLUTE = 0x1;
	private static final int RIDEV_REMOVE = 0x1, RIDEV_INPUTSINK = 0x100;
	private static final int PM_REMOVE = 0x1, QS_ALLINPUT = 0x04FF;
	private static final long HWND_MESSAGE = -3;
	/** sizeof(RAWINPUTHEADER) on x64; RAWMOUSE follows it. */
	private static final int HEADER = 24;
	private static final int RAW_FLAGS = HEADER, RAW_LAST_X = HEADER + 12, RAW_LAST_Y = HEADER + 16;
	private static final int HISTORY = 1 << 14;  // ~16 s at 1000 Hz, ~2 s at 8000 Hz

	private static final MethodHandle REGISTER_CLASS_EX, CREATE_WINDOW_EX, DESTROY_WINDOW, GET_MODULE_HANDLE,
		REGISTER_RAW_INPUT_DEVICES, GET_REGISTERED_RAW_INPUT_DEVICES, GET_RAW_INPUT_DATA, PEEK_MESSAGE, DISPATCH_MESSAGE,
		MSG_WAIT_FOR_MULTIPLE_OBJECTS, GET_FOREGROUND_WINDOW, GET_WINDOW_THREAD_PROCESS_ID, GET_CURRENT_PROCESS_ID;
	private static final MemorySegment DEF_WINDOW_PROC_ADDRESS;

	static {
		Linker linker = Linker.nativeLinker();
		SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
		SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		REGISTER_CLASS_EX = linker.downcallHandle(user32.find("RegisterClassExW").orElseThrow(), FunctionDescriptor.of(JAVA_SHORT, ADDRESS));
		// CreateWindowExW(exStyle, class, name, style, x, y, w, h, parent, menu, instance, param)
		CREATE_WINDOW_EX = linker.downcallHandle(user32.find("CreateWindowExW").orElseThrow(), FunctionDescriptor.of(ADDRESS,
			JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
		DESTROY_WINDOW = linker.downcallHandle(user32.find("DestroyWindow").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
		DEF_WINDOW_PROC_ADDRESS = user32.find("DefWindowProcW").orElseThrow();
		GET_MODULE_HANDLE = linker.downcallHandle(kernel32.find("GetModuleHandleW").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS));
		REGISTER_RAW_INPUT_DEVICES = linker.downcallHandle(user32.find("RegisterRawInputDevices").orElseThrow(),
			FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
		GET_REGISTERED_RAW_INPUT_DEVICES = linker.downcallHandle(user32.find("GetRegisteredRawInputDevices").orElseThrow(),
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
		GET_RAW_INPUT_DATA = linker.downcallHandle(user32.find("GetRawInputData").orElseThrow(),
			FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
		PEEK_MESSAGE = linker.downcallHandle(user32.find("PeekMessageW").orElseThrow(),
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
		DISPATCH_MESSAGE = linker.downcallHandle(user32.find("DispatchMessageW").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, ADDRESS));
		MSG_WAIT_FOR_MULTIPLE_OBJECTS = linker.downcallHandle(user32.find("MsgWaitForMultipleObjects").orElseThrow(),
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
		GET_FOREGROUND_WINDOW = linker.downcallHandle(user32.find("GetForegroundWindow").orElseThrow(), FunctionDescriptor.of(ADDRESS));
		GET_WINDOW_THREAD_PROCESS_ID = linker.downcallHandle(user32.find("GetWindowThreadProcessId").orElseThrow(),
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		GET_CURRENT_PROCESS_ID = linker.downcallHandle(kernel32.find("GetCurrentProcessId").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
	}

	// Ring of (time, cumulative x, cumulative y), written by the pump thread.
	private final long[] times = new long[HISTORY];
	private final long[] cumX = new long[HISTORY], cumY = new long[HISTORY];
	private long count;  // entries ever written; guarded by this
	private long totalX, totalY;

	/** The pump thread that should be running, or null. A replaced thread notices and exits. */
	private volatile Thread thread;
	private volatile String problem = "";
	private volatile long reports;

	String problem() {
		return problem;
	}

	long reports() {
		return reports;
	}

	synchronized void start() {
		if (thread != null) return;
		problem = "";
		Thread t = new Thread(this::pump, "Deadcraft raw mouse");
		t.setDaemon(true);
		// Reports are timestamped when this thread reads them: don't let a busy render thread delay it.
		t.setPriority(Thread.MAX_PRIORITY);
		thread = t;
		t.start();
	}

	synchronized void stop() {
		thread = null;
	}

	/** Cumulative counts (x right, y down) at local time {@code nanos}: the last report at or before it. */
	@Override
	public synchronized long[] countsAt(long nanos) {
		if (count == 0) return new long[] {0, 0};
		long lo = Math.max(0, count - HISTORY), hi = count - 1;
		if (times[(int) (lo % HISTORY)] > nanos) return new long[] {cumX[(int) (lo % HISTORY)], cumY[(int) (lo % HISTORY)]};
		while (lo < hi) {  // last index with time <= nanos
			long mid = (lo + hi + 1) >>> 1;
			if (times[(int) (mid % HISTORY)] <= nanos) lo = mid;
			else hi = mid - 1;
		}
		int i = (int) (lo % HISTORY);
		return new long[] {cumX[i], cumY[i]};
	}

	private synchronized void record(long nanos, int dx, int dy) {
		totalX += dx;
		totalY += dy;
		int i = (int) (count % HISTORY);
		times[i] = nanos;
		cumX[i] = totalX;
		cumY[i] = totalY;
		count++;
	}

	private void pump() {
		MemorySegment window = MemorySegment.NULL;
		try (Arena arena = Arena.ofConfined()) {
			window = createWindow(arena);
			MemorySegment msg = arena.allocate(48);  // MSG
			MemorySegment data = arena.allocate(64);
			MemorySegment size = arena.allocate(JAVA_INT);
			MemorySegment devices = arena.allocate(16L * 16);
			int ourPid = (int) GET_CURRENT_PROCESS_ID.invoke();
			long nextCheck = 0;
			while (thread == Thread.currentThread()) {
				int ignored = (int) MSG_WAIT_FOR_MULTIPLE_OBJECTS.invoke(0, MemorySegment.NULL, 0, 100, QS_ALLINPUT);
				while ((int) PEEK_MESSAGE.invoke(msg, MemorySegment.NULL, 0, 0, PM_REMOVE) != 0) {
					if (msg.get(JAVA_INT, 8) == WM_INPUT) readReport(MemorySegment.ofAddress(msg.get(JAVA_LONG, 24)), data, size);
					long ignoredResult = (long) DISPATCH_MESSAGE.invoke(msg);  // DefWindowProc frees the report
				}
				long now = System.nanoTime();
				if (now >= nextCheck) {
					nextCheck = now + 250_000_000L;
					keepRegistered(window, devices, size, ourPid, arena);
				}
			}
			if (registeredTo(window, devices, size)) register(MemorySegment.NULL, RIDEV_REMOVE, arena);
		} catch (Throwable t) {
			problem = "raw mouse error: " + t;
			Follow.LOG.warn("Deadcraft: {}", problem);
		} finally {
			try {
				if (window.address() != 0) {
					int ignored = (int) DESTROY_WINDOW.invoke(window);
				}
			} catch (Throwable ignored) {
				// shutting down
			}
			synchronized (this) {
				if (thread == Thread.currentThread()) thread = null;
			}
		}
	}

	private void readReport(MemorySegment handle, MemorySegment data, MemorySegment size) throws Throwable {
		size.set(JAVA_INT, 0, (int) data.byteSize());
		int got = (int) GET_RAW_INPUT_DATA.invoke(handle, RID_INPUT, data, size, HEADER);
		if (got < RAW_LAST_Y + 4 || data.get(JAVA_INT, 0) != RIM_TYPEMOUSE) return;
		if ((data.get(JAVA_SHORT, RAW_FLAGS) & MOUSE_MOVE_ABSOLUTE) != 0) return;  // tablets, remote desktop
		int dx = data.get(JAVA_INT, RAW_LAST_X), dy = data.get(JAVA_INT, RAW_LAST_Y);
		if (dx != 0 || dy != 0) record(System.nanoTime(), dx, dy);
		reports++;
	}

	/** Takes the mouse registration while another process (Deadlock) is in front. */
	private void keepRegistered(MemorySegment window, MemorySegment devices, MemorySegment size, int ourPid, Arena arena) throws Throwable {
		MemorySegment fg = (MemorySegment) GET_FOREGROUND_WINDOW.invoke();
		if (fg.address() == 0) return;
		MemorySegment pid = arena.allocate(JAVA_INT);
		int ignored = (int) GET_WINDOW_THREAD_PROCESS_ID.invoke(fg, pid);
		if (pid.get(JAVA_INT, 0) == ourPid) return;  // Minecraft in front: GLFW's turn
		if (registeredTo(window, devices, size)) return;
		if (!register(window, RIDEV_INPUTSINK, arena)) problem = "RegisterRawInputDevices failed";
		else Follow.LOG.info("Deadcraft: raw mouse registered (background input sink)");
	}

	private static boolean register(MemorySegment window, int flags, Arena arena) throws Throwable {
		MemorySegment device = arena.allocate(16);  // RAWINPUTDEVICE
		device.set(JAVA_SHORT, 0, (short) 0x01);  // generic desktop
		device.set(JAVA_SHORT, 2, (short) 0x02);  // mouse
		device.set(JAVA_INT, 4, flags);
		device.set(ADDRESS, 8, window);
		return (int) REGISTER_RAW_INPUT_DEVICES.invoke(device, 1, 16) != 0;
	}

	private static boolean registeredTo(MemorySegment window, MemorySegment devices, MemorySegment size) throws Throwable {
		size.set(JAVA_INT, 0, (int) (devices.byteSize() / 16));
		int n = (int) GET_REGISTERED_RAW_INPUT_DEVICES.invoke(devices, size, 16);
		for (int i = 0; i < n; i++) {
			long base = i * 16L;
			if (devices.get(JAVA_SHORT, base) == 0x01 && devices.get(JAVA_SHORT, base + 2) == 0x02) {
				return devices.get(ADDRESS, base + 8).address() == window.address();
			}
		}
		return false;
	}

	private static MemorySegment createWindow(Arena arena) throws Throwable {
		MemorySegment instance = (MemorySegment) GET_MODULE_HANDLE.invoke(MemorySegment.NULL);
		MemorySegment className = arena.allocateFrom("DeadcraftRawMouse", StandardCharsets.UTF_16LE);
		MemorySegment wc = arena.allocate(80);  // WNDCLASSEXW
		wc.set(JAVA_INT, 0, 80);
		wc.set(ADDRESS, 8, DEF_WINDOW_PROC_ADDRESS);
		wc.set(ADDRESS, 24, instance);
		wc.set(ADDRESS, 64, className);
		short atom = (short) REGISTER_CLASS_EX.invoke(wc);  // 0 on the second start: already registered, fine
		MemorySegment window = (MemorySegment) CREATE_WINDOW_EX.invoke(0, className, className, 0, 0, 0, 0, 0,
			MemorySegment.ofAddress(HWND_MESSAGE), MemorySegment.NULL, instance, MemorySegment.NULL);
		if (window.address() == 0) throw new IllegalStateException("CreateWindowExW failed");
		return window;
	}
}
