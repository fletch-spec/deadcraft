package dev.deadcraft.protocol;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The shared-memory block, opened by name ({@link Proto#MAPPING_NAME}) through kernel32 with the
 * Foreign Function API. Needs {@code --enable-native-access=ALL-UNNAMED}. One reader or writer
 * thread per instance.
 */
public final class Mapping implements AutoCloseable {
	private static final ValueLayout.OfInt INT = JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfLong LONG = JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final int PAGE_READWRITE = 0x04;
	private static final int FILE_MAP_READ = 0x04;
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
	private static final long INVALID_HANDLE_VALUE = -1;
	private static final int SEQLOCK_ATTEMPTS = 10_000;

	private static final MethodHandle CREATE_FILE_MAPPING, OPEN_FILE_MAPPING, MAP_VIEW_OF_FILE, UNMAP_VIEW_OF_FILE,
		CLOSE_HANDLE, GET_TICK_COUNT64, GET_CURRENT_PROCESS_ID;

	static {
		Linker linker = Linker.nativeLinker();
		SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		CREATE_FILE_MAPPING = linker.downcallHandle(k32.find("CreateFileMappingW").orElseThrow(),
			FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
		OPEN_FILE_MAPPING = linker.downcallHandle(k32.find("OpenFileMappingW").orElseThrow(),
			FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
		MAP_VIEW_OF_FILE = linker.downcallHandle(k32.find("MapViewOfFile").orElseThrow(),
			FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
		UNMAP_VIEW_OF_FILE = linker.downcallHandle(k32.find("UnmapViewOfFile").orElseThrow(),
			FunctionDescriptor.of(JAVA_INT, ADDRESS));
		CLOSE_HANDLE = linker.downcallHandle(k32.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
		GET_TICK_COUNT64 = linker.downcallHandle(k32.find("GetTickCount64").orElseThrow(), FunctionDescriptor.of(JAVA_LONG));
		GET_CURRENT_PROCESS_ID = linker.downcallHandle(k32.find("GetCurrentProcessId").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
	}

	private final MemorySegment handle;
	private final MemorySegment view;

	private Mapping(MemorySegment handle, MemorySegment view) {
		this.handle = handle;
		this.view = view.reinterpret(Proto.MAPPING_SIZE);
	}

	/** Opens an existing block read-only, or returns empty if the Deadlock side hasn't created it yet. */
	public static Optional<Mapping> openExisting(String name) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment h = (MemorySegment) OPEN_FILE_MAPPING.invokeExact(FILE_MAP_READ, 0, arena.allocateFrom(name, StandardCharsets.UTF_16LE));
			if (h.address() == 0) return Optional.empty();
			MemorySegment v = (MemorySegment) MAP_VIEW_OF_FILE.invokeExact(h, FILE_MAP_READ, 0, 0, (long) Proto.MAPPING_SIZE);
			if (v.address() == 0) {
				int ignored = (int) CLOSE_HANDLE.invokeExact(h);
				throw new IllegalStateException("MapViewOfFile failed for " + name);
			}
			Mapping m = new Mapping(h, v);
			m.checkHeader(false);
			return Optional.of(m);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	public static Optional<Mapping> openExisting() {
		return openExisting(Proto.MAPPING_NAME);
	}

	/** Opens the block read-write, creating it (and its header) if it doesn't exist. */
	public static Mapping openOrCreate(String name) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment h = (MemorySegment) CREATE_FILE_MAPPING.invokeExact(MemorySegment.ofAddress(INVALID_HANDLE_VALUE),
				MemorySegment.NULL, PAGE_READWRITE, 0, Proto.MAPPING_SIZE, arena.allocateFrom(name, StandardCharsets.UTF_16LE));
			if (h.address() == 0) throw new IllegalStateException("CreateFileMappingW failed for " + name);
			MemorySegment v = (MemorySegment) MAP_VIEW_OF_FILE.invokeExact(h, FILE_MAP_ALL_ACCESS, 0, 0, (long) Proto.MAPPING_SIZE);
			if (v.address() == 0) {
				int ignored = (int) CLOSE_HANDLE.invokeExact(h);
				throw new IllegalStateException("MapViewOfFile failed for " + name);
			}
			Mapping m = new Mapping(h, v);
			m.checkHeader(true);
			return m;
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	private void checkHeader(boolean initIfEmpty) {
		Header h = readHeader();
		if (h.magic == 0 && initIfEmpty) {
			Header fresh = new Header();
			fresh.magic = Proto.MAGIC;
			fresh.version = Proto.VERSION;
			fresh.mappingSize = Proto.MAPPING_SIZE;
			fresh.unitsPerBlock = Proto.UNITS_PER_BLOCK;
			ByteBuffer b = ByteBuffer.allocate(Header.SIZE).order(ByteOrder.LITTLE_ENDIAN);
			fresh.write(b, 0);
			MemorySegment.copy(MemorySegment.ofArray(b.array()), 0, view, Header.OFFSET, Header.SIZE);
			return;
		}
		if (h.magic == 0) return;  // created but not initialised yet; the writer will fill it in
		if (h.magic != Proto.MAGIC || h.version != Proto.VERSION || h.mappingSize != Proto.MAPPING_SIZE) {
			close();
			throw new IllegalStateException(String.format(
				"%s has magic 0x%08X version %d size %d; this build speaks version %d size %d",
				Proto.MAPPING_NAME, h.magic, h.version, h.mappingSize, Proto.VERSION, Proto.MAPPING_SIZE));
		}
	}

	private ByteBuffer copy(long offset, int size) {
		byte[] bytes = new byte[size];
		MemorySegment.copy(view, offset, MemorySegment.ofArray(bytes), 0, size);
		return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
	}

	public Header readHeader() {
		return Header.read(copy(Header.OFFSET, Header.SIZE), 0);
	}

	/**
	 * Reads hero state under the seqlock; empty only if the writer stayed mid-update for the whole
	 * retry budget (about a millisecond). A write takes microseconds, so retries must wait, not just loop.
	 */
	public Optional<HeroState> readHeroState() {
		long seqAt = HeroState.OFFSET + HeroState.SEQ_AT;
		for (int attempt = 0; attempt < SEQLOCK_ATTEMPTS; attempt++) {
			if (attempt > 0) Thread.onSpinWait();
			int before = view.get(INT, seqAt);
			VarHandle.acquireFence();
			if ((before & 1) != 0) continue;
			ByteBuffer b = copy(HeroState.OFFSET, HeroState.SIZE);
			VarHandle.acquireFence();
			if (view.get(INT, seqAt) != before) continue;
			return Optional.of(HeroState.read(b, 0));
		}
		return Optional.empty();
	}

	/** Writes hero state under the seqlock (the Deadlock plugin's job; here for tests and tools). */
	public void writeHeroState(HeroState state) {
		long seqAt = HeroState.OFFSET + HeroState.SEQ_AT;
		int start = view.get(INT, seqAt) | 1;
		view.set(INT, seqAt, start);
		VarHandle.releaseFence();
		state.seq = start;
		ByteBuffer b = ByteBuffer.allocate(HeroState.SIZE).order(ByteOrder.LITTLE_ENDIAN);
		state.write(b, 0);
		MemorySegment.copy(MemorySegment.ofArray(b.array()), 4, view, HeroState.OFFSET + 4, HeroState.SIZE - 4);
		VarHandle.releaseFence();
		view.set(INT, seqAt, start + 1);
	}

	/** The ability event with this serial, or empty if it was overwritten or isn't written yet. */
	public Optional<AbilityEvent> readAbilityEvent(int serial) {
		long at = Proto.ABILITY_EVENTS_OFFSET + (long) Integer.remainderUnsigned(serial - 1, Proto.ABILITY_EVENTS_CAPACITY) * AbilityEvent.SIZE;
		if (view.get(INT, at + AbilityEvent.SERIAL_AT) != serial) return Optional.empty();
		VarHandle.acquireFence();
		ByteBuffer b = copy(at, AbilityEvent.SIZE);
		VarHandle.acquireFence();
		if (view.get(INT, at + AbilityEvent.SERIAL_AT) != serial) return Optional.empty();
		return Optional.of(AbilityEvent.read(b, 0));
	}

	/** The shot with this serial, or empty if it was overwritten or not written yet. */
	public Optional<Shot> readShot(int serial) {
		long at = Proto.SHOTS_OFFSET + (long) Integer.remainderUnsigned(serial - 1, Proto.SHOTS_CAPACITY) * Shot.SIZE;
		if (view.get(INT, at + Shot.SERIAL_AT) != serial) return Optional.empty();
		VarHandle.acquireFence();
		ByteBuffer b = copy(at, Shot.SIZE);
		VarHandle.acquireFence();
		if (view.get(INT, at + Shot.SERIAL_AT) != serial) return Optional.empty();
		return Optional.of(Shot.read(b, 0));
	}

	/** Marks the Minecraft side alive: its process id and GetTickCount64. */
	public void minecraftHeartbeat() {
		view.set(INT, Header.OFFSET + Header.MINECRAFT_PID_AT, currentProcessId());
		VarHandle.releaseFence();
		view.set(LONG, Header.OFFSET + Header.MINECRAFT_HEARTBEAT_MS_AT, tickCount64());
	}

	/**
	 * Publishes the Minecraft side's state and cube set under the McState seqlock. Fills in
	 * {@code seq} and {@code cubeCount}; cubes beyond the capacity are dropped.
	 */
	public void writeMcState(McState state, List<Cube> cubes) {
		int count = Math.min(cubes.size(), Proto.CUBES_CAPACITY);
		long seqAt = McState.OFFSET + McState.SEQ_AT;
		int start = view.get(INT, seqAt) | 1;
		view.set(INT, seqAt, start);
		VarHandle.releaseFence();
		ByteBuffer b = ByteBuffer.allocate(count * Cube.SIZE).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < count; i++) cubes.get(i).write(b, i * Cube.SIZE);
		MemorySegment.copy(MemorySegment.ofArray(b.array()), 0, view, Proto.CUBES_OFFSET, count * Cube.SIZE);
		state.seq = start;
		state.cubeCount = count;
		ByteBuffer h = ByteBuffer.allocate(McState.SIZE).order(ByteOrder.LITTLE_ENDIAN);
		state.write(h, 0);
		MemorySegment.copy(MemorySegment.ofArray(h.array()), 4, view, McState.OFFSET + 4, McState.SIZE - 4);
		VarHandle.releaseFence();
		view.set(INT, seqAt, start + 1);
	}

	/** McState and its cubes, read together under the seqlock. */
	public record McSnapshot(McState state, List<Cube> cubes) {}

	/** Reads McState and its cubes under the seqlock (the plugin's job; here for tests and tools). */
	public Optional<McSnapshot> readMcState() {
		long seqAt = McState.OFFSET + McState.SEQ_AT;
		for (int attempt = 0; attempt < SEQLOCK_ATTEMPTS; attempt++) {
			if (attempt > 0) Thread.onSpinWait();
			int before = view.get(INT, seqAt);
			VarHandle.acquireFence();
			if ((before & 1) != 0) continue;
			McState state = McState.read(copy(McState.OFFSET, McState.SIZE), 0);
			int count = Math.min(state.cubeCount, Proto.CUBES_CAPACITY);
			ByteBuffer b = copy(Proto.CUBES_OFFSET, count * Cube.SIZE);
			VarHandle.acquireFence();
			if (view.get(INT, seqAt) != before) continue;
			List<Cube> cubes = new ArrayList<>(count);
			for (int i = 0; i < count; i++) cubes.add(Cube.read(b, i * Cube.SIZE));
			return Optional.of(new McSnapshot(state, cubes));
		}
		return Optional.empty();
	}

	/** Milliseconds since the Deadlock side last wrote, by GetTickCount64 (the clock both sides use). */
	public long deadlockHeartbeatAgeMs() {
		return tickCount64() - view.get(LONG, Header.OFFSET + Header.DEADLOCK_HEARTBEAT_MS_AT);
	}

	public static long tickCount64() {
		try {
			return (long) GET_TICK_COUNT64.invokeExact();
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	public static int currentProcessId() {
		try {
			return (int) GET_CURRENT_PROCESS_ID.invokeExact();
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	@Override
	public void close() {
		try {
			int ignored = (int) UNMAP_VIEW_OF_FILE.invokeExact(MemorySegment.ofAddress(view.address()));
			ignored = (int) CLOSE_HANDLE.invokeExact(handle);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}
}
