using System.Diagnostics;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using Microsoft.Diagnostics.Runtime;
using Microsoft.Diagnostics.Runtime.AbstractDac;

namespace ClockProbe;

// Experimental diagnostic resolver. Owns only read/query handles to the target.
sealed class ActiveClockResolver : IDisposable
{
    readonly Reader reader;
    readonly int pid;
    readonly IAbstractTypeHelpers types;
    readonly ClrRuntime runtime;
    readonly Dictionary<ulong, string> modulePaths;
    readonly Dictionary<(ulong, string), int> offsets = [];
    readonly HashSet<(ulong, string)> missing = [];
    readonly Dictionary<ulong, string> names = [];
    ulong game, gameTable;
    public ActiveClockResolver(int pid, ClrRuntime runtime)
    {
        this.pid = pid;
        this.runtime = runtime;
        var services = (IServiceProvider)typeof(ClrRuntime).GetField("_services", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(runtime)!;
        types = (IAbstractTypeHelpers)services.GetService(typeof(IAbstractTypeHelpers))!;
        modulePaths = runtime.EnumerateModules().Where(m => m.Name != null).ToDictionary(m => m.Address, m => m.Name!);
        reader = new Reader(pid);
    }
    ulong U64(ulong address) => BitConverter.ToUInt64(reader.Bytes(address, 8));
    int I32(ulong address) => BitConverter.ToInt32(reader.Bytes(address, 4));
    string Name(ulong obj)
    {
        if (obj == 0) return "null/unknown";
        ulong table = U64(obj);
        if (names.TryGetValue(table, out var name)) return name;
        if (!types.GetTypeInfo(table, out var info)) return "unknown";
        return names[table] = types.GetTypeName(info.ModuleAddress, info.MethodTable, info.MetadataToken) ?? "unknown";
    }
    int Offset(ulong table, string fieldName)
    {
        var key = (table, fieldName);
        if (missing.Contains(key)) throw new IOException($"Field {fieldName} unavailable.");
        if (offsets.TryGetValue(key, out int offset)) return offset;
        for (int depth = 0; table != 0 && depth < 64; depth++)
        {
            if (!types.GetTypeInfo(table, out var info)) break;
            if (modulePaths.TryGetValue(info.ModuleAddress, out var path) && File.Exists(path))
            {
                using var file = File.OpenRead(path); using var pe = new PEReader(file);
                var metadata = pe.GetMetadataReader();
                foreach (var field in types.EnumerateFields(info, 0))
                {
                    if (field.Kind != FieldKind.Instance || field.Offset < 0 || field.Offset > 65536) continue;
                    var definition = metadata.GetFieldDefinition((FieldDefinitionHandle)MetadataTokens.Handle(field.Token));
                    if (MetadataTokens.GetToken(definition.GetDeclaringType()) != info.MetadataToken) continue;
                    if (metadata.GetString(definition.Name) == fieldName)
                        return offsets[key] = field.Offset + 8;
                }
            }
            table = info.ParentMethodTable;
        }
        missing.Add(key);
        throw new IOException($"Field {fieldName} unavailable on MT 0x{key.Item1:X}.");
    }
    ulong FieldAddress(ulong obj, string name)
    {
        if (obj == 0) throw new IOException("Object not available during screen transition.");
        return obj + (uint)Offset(U64(obj), name);
    }
    ulong Ref(ulong obj, string name) => U64(FieldAddress(obj, name));
    string CachePath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "DuckyPlayer", $"clock-root-{pid}.txt");
    bool TryCachedGame()
    {
        try
        {
            if (!File.Exists(CachePath)) return false;
            ulong candidate = Convert.ToUInt64(File.ReadAllText(CachePath).Trim(), 16);
            if (U64(candidate) != gameTable || Name(Ref(candidate, "<ScreenStack>k__BackingField")) != "osu.Game.Screens.OsuScreenStack")
                return false;
            game = candidate;
            return true;
        }
        catch (Exception e) when (e is IOException or FormatException or OverflowException or BadImageFormatException or ArgumentException)
        {
            return false;
        }
    }
    void CacheGame()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(CachePath)!);
            File.WriteAllText(CachePath, game.ToString("X"));
        }
        catch (IOException) { }
    }
    public string FindGame()
    {
        var watch = Stopwatch.StartNew();
        foreach (var module in runtime.EnumerateModules().Where(m => Path.GetFileName(m.Name) == "osu!.dll"))
            foreach (var entry in module.EnumerateTypeDefToMethodTableMap())
                if (types.GetTypeInfo(entry.MethodTable, out var info) &&
                    types.GetTypeName(info.ModuleAddress, entry.MethodTable, info.MetadataToken) == "osu.Desktop.OsuGameDesktop")
                    gameTable = entry.MethodTable;
        if (gameTable == 0) throw new IOException("Desktop game type is not loaded.");
        if (TryCachedGame()) return $"Game root 0x{game:X}, restored from validated cache.";
        long scanned = 0;
        foreach (var range in reader.PrivateRanges())
            for (ulong address = range.Start; address < range.End;)
            {
                if (watch.Elapsed.TotalSeconds > 20 || scanned >= 2L * 1024 * 1024 * 1024) throw new IOException("Game-root discovery limit reached.");
                int length = (int)Math.Min(1024UL * 1024, range.End - address);
                byte[] data;
                try { data = reader.Bytes(address, length); } catch (IOException) { address += (uint)length; continue; }
                scanned += length;
                for (int i = 0; i + 8 <= length; i += 8)
                    if (BitConverter.ToUInt64(data, i) == gameTable)
                    {
                        ulong candidate = address + (uint)i;
                        try
                        {
                            if (Name(Ref(candidate, "<ScreenStack>k__BackingField")) != "osu.Game.Screens.OsuScreenStack") continue;
                            game = candidate;
                            CacheGame();
                            return $"Game root 0x{game:X}, initial scan {watch.Elapsed.TotalSeconds:F2}s.";
                        }
                        catch (Exception e) when (e is IOException or BadImageFormatException or ArgumentException) { }
                    }
                address += (uint)length;
                if (scanned % (16 * 1024 * 1024) < length) Thread.Sleep(1);
            }
        throw new IOException("Game root was not found before the discovery limit.");
    }
    public record Snapshot(ulong Clock, double Time, bool Paused, string Screen);
    public Snapshot ReadSnapshot()
    {
        if (U64(game) != gameTable) throw new IOException("Game root moved; rediscovery required.");
        ulong screens = Ref(game, "<ScreenStack>k__BackingField");
        ulong stack = Ref(screens, "stack");
        ulong sizeAddress = FieldAddress(stack, "_size");
        int size = I32(sizeAddress);
        ulong array = Ref(stack, "_array");
        // x64 managed SZARRAY: length at +8, elements at +16.
        if (size == 0) return new(0, 0, true, "No active screen");
        if (array == 0 || size < 0 || size > 1024 || size > I32(array + 8)) throw new IOException("Invalid screen stack.");
        ulong topAddress = array + 16 + (uint)(size - 1) * 8;
        ulong screen = U64(topAddress);
        string screenName = Name(screen);
        ulong ruleset;
        try { ruleset = Ref(screen, "<DrawableRuleset>k__BackingField"); }
        catch (IOException) { return new(0, 0, true, screenName); }
        ulong gameplayClock = Ref(screen, "<GameplayClockContainer>k__BackingField");
        ulong pausedBindable = Ref(gameplayClock, "isPaused");
        ulong pausedAddress = FieldAddress(pausedBindable, "value");
        bool paused = reader.Bytes(pausedAddress, 1)[0] != 0;
        ulong container = Ref(ruleset, "frameStabilityContainer");
        ulong link = FieldAddress(container, "framedClock"), clock = U64(link);
        var candidate = new Candidate("Active gameplay", container, U64(container), link, clock, U64(clock), FieldAddress(clock, "<CurrentTime>k__BackingField"));
        double value = reader.Sample(candidate);
        if (U64(game) != gameTable || I32(sizeAddress) != size || U64(topAddress) != screen ||
            Ref(screens, "stack") != stack || Ref(stack, "_array") != array || Ref(game, "<ScreenStack>k__BackingField") != screens ||
            Ref(screen, "<DrawableRuleset>k__BackingField") != ruleset || Ref(ruleset, "frameStabilityContainer") != container)
            throw new IOException("Screen chain changed during read; retry.");
        if (reader.Bytes(pausedAddress, 1)[0] != (paused ? 1 : 0)) throw new IOException("Pause state changed during read.");
        return new(clock, value, paused, screenName);
    }
    public string Resolve()
    {
        var watch = Stopwatch.StartNew();
        var sample = ReadSnapshot();
        return $"{sample.Screen}: clock=0x{sample.Clock:X}, time={sample.Time:F3} ms, paused={sample.Paused}, resolve={watch.Elapsed.TotalMicroseconds:F1} us";
    }
    public Snapshot ReadStableSnapshot()
    {
        for (int attempt = 0; ; attempt++)
        {
            try { return ReadSnapshot(); }
            catch (IOException e) when (attempt < 2 &&
                (e.Message == "Pause state changed during read." || e.Message == "Screen chain changed during read; retry."))
            { Thread.Sleep(1); }
        }
    }
    public void Dispose() => reader.Dispose();
}
