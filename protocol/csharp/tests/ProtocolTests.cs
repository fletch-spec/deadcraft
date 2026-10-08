using System.Numerics;
using Xunit;

namespace Deadcraft.Protocol.Tests;

public class ProtocolTests
{
	private static readonly byte[] GoldenBytes = File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "golden", "v1.bin"));

	[Fact]
	public void EncodesTheGoldenFixture()
	{
		var buf = new byte[Golden.Extent];
		foreach (var (at, value) in Golden.Structs)
		{
			var span = buf.AsSpan(at);
			switch (value)
			{
				case Header h: h.Write(span); break;
				case HeroState s: s.Write(span); break;
				case AbilityEvent e: e.Write(span); break;
				default: throw new InvalidOperationException(value.GetType().Name);
			}
		}
		Assert.Equal(GoldenBytes, buf);
	}

	[Fact]
	public void DecodesTheGoldenFixture()
	{
		foreach (var (at, expected) in Golden.Structs)
		{
			var span = GoldenBytes.AsSpan(at);
			object actual = expected switch
			{
				Header => Header.Read(span),
				HeroState => HeroState.Read(span),
				AbilityEvent => AbilityEvent.Read(span),
				_ => throw new InvalidOperationException(expected.GetType().Name),
			};
			Assert.Equal(expected, actual);
		}
	}

	[Fact]
	public void ConvertsUnitsAndAxes()
	{
		// One block east, two up, three south.
		Assert.Equal(new Vector3(64f, -192f, 128f), Proto.ToSource(new Vector3(1f, 2f, 3f)));
		Assert.Equal(new Vector3(1f, 2f, 3f), Proto.ToMinecraft(new Vector3(64f, -192f, 128f)));
	}

	[Fact]
	public void RoundTripsThroughSharedMemory()
	{
		using var mapping = Mapping.OpenOrCreate($@"Local\DeadcraftTest_{Guid.NewGuid():N}");
		Assert.Equal(Proto.Magic, mapping.ReadHeader().Magic);

		mapping.AppendAbilityEvent(AbilityEventKind.Used, 77, "citadel_ability_dash");
		var written = new HeroState { Flags = (uint)(HeroFlags.Present | HeroFlags.OnGround), Tick = 77, Position = new Vector3(1, 2, 3), Health = 500 };
		mapping.WriteHeroState(written);

		Assert.True(mapping.TryReadHeroState(out var read));
		Assert.Equal(0u, read.Seq % 2);
		Assert.Equal(1u, read.AbilityEventSerial);
		Assert.Equal(written.Position, read.Position);
		Assert.Equal(500, read.Health);
		Assert.Equal("citadel_ability_dash", mapping.TryReadAbilityEvent(1)?.AbilityName);
		Assert.Null(mapping.TryReadAbilityEvent(2));
	}

	[Fact]
	public void LapsTheAbilityRing()
	{
		using var mapping = Mapping.OpenOrCreate($@"Local\DeadcraftTest_{Guid.NewGuid():N}");
		for (int i = 1; i <= Proto.AbilityEventsCapacity + 3; i++)
			mapping.AppendAbilityEvent(AbilityEventKind.Used, (ulong)i, $"ability_{i}");
		Assert.Null(mapping.TryReadAbilityEvent(3));  // overwritten by serial 35
		Assert.Equal("ability_35", mapping.TryReadAbilityEvent(35)?.AbilityName);
	}
}
