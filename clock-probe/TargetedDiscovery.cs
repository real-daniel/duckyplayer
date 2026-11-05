using System.Diagnostics;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using Microsoft.Diagnostics.Runtime;
using Microsoft.Diagnostics.Runtime.AbstractDac;

namespace ClockProbe;

// Uses DAC type/field metadata, but never requests the unsupported GC heap API.
static class TargetedDiscovery
{
    record Layout(ulong Table, int Offset);
    public static List<Candidate> Scan(int pid, ClrRuntime runtime, bool satori, Action<string> log)
    {
        var watch = Stopwatch.StartNew();
        var services = typeof(ClrRuntime).GetField("_services", BindingFlags.Instance | BindingFlags.NonPublic)?.GetValue(runtime) as IServiceProvider;
        var types = services?.GetService(typeof(IAbstractTypeHelpers)) as IAbstractTypeHelpers
            ?? throw new IOException("ClrMD type-metadata service unavailable.");
        var wanted = new Dictionary<string, string>
        {
            ["osu.Game.Rulesets.UI.FrameStabilityContainer"] = "framedClock",
            ["osu.Framework.Timing.FramedClock"] = "<CurrentTime>k__BackingField",
            ["osu.Framework.Audio.Track.TrackBass"] = "currentTime"
        };
        var layouts = new Dictionary<string, Layout>();
        foreach (var module in runtime.EnumerateModules())
        {
            if (watch.Elapsed.TotalSeconds > 20) throw new IOException("Type discovery timed out.");
            if (module.Name == null || !File.Exists(module.Name)) continue;
            string filename = Path.GetFileName(module.Name);
            if (filename != "osu.Game.dll" && filename != "osu.Framework.dll" && filename != "ClockProbe.dll") continue;
            using var file = File.OpenRead(module.Name);
            using var pe = new PEReader(file);
            if (!pe.HasMetadata) continue;
            var metadata = pe.GetMetadataReader();
            foreach (var entry in module.EnumerateTypeDefToMethodTableMap())
            {
                if (entry.MethodTable == 0 || !types.GetTypeInfo(entry.MethodTable, out var type)) continue;
                string? name = types.GetTypeName(type.ModuleAddress, entry.MethodTable, type.MetadataToken);
                if (name == null || !wanted.TryGetValue(name, out var fieldName)) continue;
                foreach (var field in types.EnumerateFields(type, 0))
                {
                    if (field.Kind != FieldKind.Instance || field.Offset < 0 || field.Offset > 65536) continue;
                    var definition = metadata.GetFieldDefinition((FieldDefinitionHandle)MetadataTokens.Handle(field.Token));
                    if (MetadataTokens.GetToken(definition.GetDeclaringType()) != type.MetadataToken) continue;
                    if (metadata.GetString(definition.Name) != fieldName) continue;
                    layouts[name] = new(entry.MethodTable, checked(field.Offset + 8)); // object method-table word
                    log($"Target {name}: MT=0x{entry.MethodTable:X}, {fieldName} object offset={field.Offset + 8}");
                    break;
                }
            }
        }
        if (!layouts.TryGetValue("osu.Game.Rulesets.UI.FrameStabilityContainer", out var gameplay) ||
            !layouts.TryGetValue("osu.Framework.Timing.FramedClock", out var clock))
            throw new IOException("Gameplay clock types are not loaded. Enter a map, pause, and discover again.");
        layouts.TryGetValue("osu.Framework.Audio.Track.TrackBass", out var audio);
        using var reader = new Reader(pid);
        var results = new List<Candidate>();
        long bytesRead = 0;
        int matches = 0;
        foreach (var range in reader.PrivateRanges())
        {
            for (ulong address = range.Start; address < range.End;)
            {
                if (watch.Elapsed.TotalSeconds > 20 || bytesRead >= 2L * 1024 * 1024 * 1024)
                    throw new IOException("Targeted discovery reached its scan limit; no partial results accepted. Pause and retry.");
                int length = (int)Math.Min(1024UL * 1024, range.End - address);
                byte[] data;
                try { data = reader.Bytes(address, length); }
                catch (IOException) { address += (uint)length; continue; }
                bytesRead += length;
                for (int i = 0; i + 8 <= data.Length; i += 8)
                {
                    ulong table = BitConverter.ToUInt64(data, i);
                    bool isGameplay = table == gameplay.Table;
                    if (!isGameplay && (audio == null || table != audio.Table)) continue;
                    matches++;
                    ulong owner = address + (uint)i;
                    try
                    {
                        if (satori && !IsSatoriObject(reader, owner)) continue;
                        ulong link = isGameplay ? owner + (uint)gameplay.Offset : 0;
                        ulong clockAddress = isGameplay ? BitConverter.ToUInt64(reader.Bytes(link, 8)) : owner;
                        if (satori && !IsSatoriObject(reader, clockAddress)) continue;
                        var candidate = new Candidate(isGameplay ? "Gameplay candidate" : "Audio reference", owner, table, link,
                            clockAddress, isGameplay ? clock.Table : audio!.Table,
                            clockAddress + (uint)(isGameplay ? clock.Offset : audio!.Offset));
                        reader.Sample(candidate); // recheck method tables, link and finite value
                        results.Add(candidate);
                    }
                    catch (IOException) { /* Concurrent GC or non-object pattern match. */ }
                }
                address += (uint)length;
                // Bounded yielding at ordinary priority; no busy polling or hooks.
                if (bytesRead % (16 * 1024 * 1024) < length) Thread.Sleep(1);
            }
        }
        log($"Targeted discovery: {bytesRead / 1048576} MiB, {matches} patterns, {results.Count} validated candidates, {watch.Elapsed.TotalSeconds:F2}s");
        return results.DistinctBy(c => c.Owner).ToList();
    }

    // x64 Satori 2026.911.0 only; caller verifies runtime PDB identity before enabling.
    // Small objects reside in a 2 MiB-aligned region with a containing-page backlink.
    static bool IsSatoriObject(Reader reader, ulong address)
    {
        if (address < 0x10000 || (address & 7) != 0) return false;
        ulong region = address & ~0x1fffffUL;
        var header = reader.Bytes(region, 40);
        ulong end = BitConverter.ToUInt64(header, 24), page = BitConverter.ToUInt64(header, 32);
        if (end != region + 0x200000 || page >= region || page < 0x10000 || (page & 0x3fffffff) != 0) return false;
        var pageHeader = reader.Bytes(page, 32);
        ulong pageEnd = BitConverter.ToUInt64(pageHeader, 8), firstRegion = BitConverter.ToUInt64(pageHeader, 24);
        // Bitmap: 4097 x 8; index: 1026 x 4; sync block: 8.
        return firstRegion <= region && region < pageEnd && end <= pageEnd && address >= region + 36888;
    }
}
