namespace osu.Framework.Timing
{
    public sealed class FramedClock { public double CurrentTime { get; set; } }
}
namespace osu.Game.Rulesets.UI
{
    public sealed class FrameStabilityContainer
    {
        public readonly osu.Framework.Timing.FramedClock framedClock = new();
    }
}
