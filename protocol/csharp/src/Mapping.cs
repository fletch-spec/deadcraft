using System.IO.MemoryMappedFiles;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Threading;

namespace Deadcraft.Protocol;

/// <summary>
/// The shared-memory block, opened by name (<see cref="Proto.MappingName"/>). Either side may create
/// it; the first one in writes the header. Not thread-safe: one writer thread per side.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed unsafe class Mapping : IDisposable
{
	private readonly MemoryMappedFile _file;
	private readonly MemoryMappedViewAccessor _view;
	private readonly byte* _base;
	private uint _abilitySerial;

	private Mapping(MemoryMappedFile file)
	{
		_file = file;
		_view = file.CreateViewAccessor(0, Proto.MappingSize);
		byte* p = null;
		_view.SafeMemoryMappedViewHandle.AcquirePointer(ref p);
		_base = p + _view.PointerOffset;
	}

	/// <summary>Opens the block, creating it if needed, and checks its header.</summary>
	/// <exception cref="InvalidDataException">The block exists with another protocol version or size.</exception>
	public static Mapping OpenOrCreate(string name = Proto.MappingName)
	{
		var mapping = new Mapping(MemoryMappedFile.CreateOrOpen(name, Proto.MappingSize));
		try
		{
			mapping.InitHeader();
			return mapping;
		}
		catch
		{
			mapping.Dispose();
			throw;
		}
	}

	private Span<byte> Region(int offset, int size) => new(_base + offset, size);

	private void InitHeader()
	{
		var header = Header.Read(Region(Header.Offset, Header.Size));
		if (header.Magic == 0)
		{
			new Header { Magic = Proto.Magic, Version = Proto.Version, MappingSize = Proto.MappingSize, UnitsPerBlock = Proto.UnitsPerBlock }
				.Write(Region(Header.Offset, Header.Size));
			return;
		}
		if (header.Magic != Proto.Magic || header.Version != Proto.Version || header.MappingSize != Proto.MappingSize)
			throw new InvalidDataException(
				$"{Proto.MappingName} has magic 0x{header.Magic:X8} version {header.Version} size {header.MappingSize}; " +
				$"this build speaks version {Proto.Version} size {Proto.MappingSize}. Restart both games on matching builds.");
	}

	public Header ReadHeader() => Header.Read(Region(Header.Offset, Header.Size));

	/// <summary>Marks the Deadlock side alive: its process id and GetTickCount64.</summary>
	public void DeadlockHeartbeat()
	{
		*(uint*)(_base + Header.Offset + Header.DeadlockPidAt) = (uint)Environment.ProcessId;
		Volatile.Write(ref *(long*)(_base + Header.Offset + Header.DeadlockHeartbeatMsAt), Environment.TickCount64);
	}

	/// <summary>Publishes hero state under the seqlock. <see cref="HeroState.Seq"/> and
	/// <see cref="HeroState.AbilityEventSerial"/> are filled in here.</summary>
	public void WriteHeroState(HeroState state)
	{
		ref uint seq = ref *(uint*)(_base + HeroState.Offset + HeroState.SeqAt);
		uint start = Volatile.Read(ref seq) | 1;  // odd: writing
		Volatile.Write(ref seq, start);
		state.Seq = start;
		state.AbilityEventSerial = _abilitySerial;
		state.Write(Region(HeroState.Offset, HeroState.Size));
		Volatile.Write(ref seq, start + 1);       // even: stable
	}

	/// <summary>Reads hero state; false only if the writer stayed mid-update for the whole retry budget
	/// (about a millisecond). A write takes microseconds, so retries must wait, not just loop.</summary>
	public bool TryReadHeroState(out HeroState state)
	{
		ref uint seq = ref *(uint*)(_base + HeroState.Offset + HeroState.SeqAt);
		Span<byte> copy = stackalloc byte[HeroState.Size];
		var spin = new SpinWait();
		for (int attempt = 0; attempt < 10_000; attempt++)
		{
			if (attempt > 0) spin.SpinOnce(sleep1Threshold: -1);
			uint before = Volatile.Read(ref seq);
			if ((before & 1) != 0) continue;
			Region(HeroState.Offset, HeroState.Size).CopyTo(copy);
			Interlocked.MemoryBarrier();
			if (Volatile.Read(ref seq) != before) continue;
			state = HeroState.Read(copy);
			return true;
		}
		state = default;
		return false;
	}

	/// <summary>Appends an ability event; it becomes visible to readers with the next hero state.</summary>
	public void AppendAbilityEvent(AbilityEventKind kind, ulong tick, string abilityName)
	{
		uint serial = ++_abilitySerial;
		int at = Proto.AbilityEventsOffset + (int)((serial - 1) % Proto.AbilityEventsCapacity) * AbilityEvent.Size;
		ref uint entrySerial = ref *(uint*)(_base + at + AbilityEvent.SerialAt);
		Volatile.Write(ref entrySerial, 0u);
		new AbilityEvent { Serial = 0, Kind = (uint)kind, Tick = tick, AbilityName = abilityName }.Write(Region(at, AbilityEvent.Size));
		Volatile.Write(ref entrySerial, serial);
	}

	/// <summary>Reads the ability event with this serial, or null if it was overwritten or not written yet.</summary>
	public AbilityEvent? TryReadAbilityEvent(uint serial)
	{
		int at = Proto.AbilityEventsOffset + (int)((serial - 1) % Proto.AbilityEventsCapacity) * AbilityEvent.Size;
		ref uint entrySerial = ref *(uint*)(_base + at + AbilityEvent.SerialAt);
		if (Volatile.Read(ref entrySerial) != serial) return null;
		Span<byte> copy = stackalloc byte[AbilityEvent.Size];
		Region(at, AbilityEvent.Size).CopyTo(copy);
		Interlocked.MemoryBarrier();
		return Volatile.Read(ref entrySerial) == serial ? AbilityEvent.Read(copy) : null;
	}

	/// <summary>Publishes the Minecraft side's state and cube set under the McState seqlock
	/// (the Fabric client's job; here for tests and tools). Fills in <see cref="McState.Seq"/> and
	/// <see cref="McState.CubeCount"/>.</summary>
	public void WriteMcState(McState state, IReadOnlyList<Cube> cubes)
	{
		int count = Math.Min(cubes.Count, Proto.CubesCapacity);
		ref uint seq = ref *(uint*)(_base + McState.Offset + McState.SeqAt);
		uint start = Volatile.Read(ref seq) | 1;
		Volatile.Write(ref seq, start);
		for (int i = 0; i < count; i++)
			cubes[i].Write(Region(Proto.CubesOffset + i * Cube.Size, Cube.Size));
		state.Seq = start;
		state.CubeCount = (uint)count;
		state.Write(Region(McState.Offset, McState.Size));
		Volatile.Write(ref seq, start + 1);
	}

	/// <summary>
	/// Reads the Minecraft side's state. The cube list is copied only when its generation differs
	/// from <paramref name="knownGeneration"/>; otherwise <paramref name="cubes"/> is left alone.
	/// False if the writer stayed mid-update for the whole retry budget.
	/// </summary>
	public bool TryReadMcState(uint knownGeneration, out McState state, List<Cube> cubes)
	{
		ref uint seq = ref *(uint*)(_base + McState.Offset + McState.SeqAt);
		var spin = new SpinWait();
		byte[]? buffer = null;
		for (int attempt = 0; attempt < 10_000; attempt++)
		{
			if (attempt > 0) spin.SpinOnce(sleep1Threshold: -1);
			uint before = Volatile.Read(ref seq);
			if ((before & 1) != 0) continue;
			var candidate = McState.Read(Region(McState.Offset, McState.Size));
			int count = (int)Math.Min(candidate.CubeCount, (uint)Proto.CubesCapacity);
			bool copyCubes = candidate.Generation != knownGeneration;
			if (copyCubes)
			{
				buffer ??= new byte[Proto.CubesCapacity * Cube.Size];
				Region(Proto.CubesOffset, count * Cube.Size).CopyTo(buffer);
			}
			Interlocked.MemoryBarrier();
			if (Volatile.Read(ref seq) != before) continue;
			state = candidate;
			if (copyCubes)
			{
				cubes.Clear();
				for (int i = 0; i < count; i++) cubes.Add(Cube.Read(buffer.AsSpan(i * Cube.Size, Cube.Size)));
			}
			return true;
		}
		state = default;
		return false;
	}

	/// <summary>Milliseconds since the Minecraft side last wrote its heartbeat.</summary>
	public long MinecraftHeartbeatAgeMs() =>
		Environment.TickCount64 - Volatile.Read(ref *(long*)(_base + Header.Offset + Header.MinecraftHeartbeatMsAt));

	public void Dispose()
	{
		_view.SafeMemoryMappedViewHandle.ReleasePointer();
		_view.Dispose();
		_file.Dispose();
	}
}
