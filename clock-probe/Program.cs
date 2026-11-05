using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Reflection;
using Microsoft.Diagnostics.Runtime;
using Microsoft.Win32.SafeHandles;

namespace ClockProbe;

record Candidate(string Kind, ulong Owner, ulong OwnerType, ulong Link, ulong Clock, ulong ClockType, ulong Time);

static class Program
{
    internal static readonly string LogPath = Path.Combine(AppContext.BaseDirectory, "discovery.log");
    static void Log(string message) => File.AppendAllText(LogPath, $"{DateTimeOffset.Now:O} {message}{Environment.NewLine}");
    // Diagnostic-only inspection of our ClrMD instance, never target-process writes.
    static void LogHeapFailure(ClrRuntime runtime)
    {
        try
        {
            const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public;
            var services = typeof(ClrRuntime).GetField("_services", flags)?.GetValue(runtime);
            var sos = services?.GetType().GetField("_sos", flags)?.GetValue(services);
            if (sos == null) return;
            foreach (var name in new[] { "GetGCHeapData", "GetCommonMethodTables" })
            {
                object?[] values = [null];
                var result = sos.GetType().GetMethod(name, flags)!.Invoke(sos, values);
                Log($"{name}: HRESULT={result}");
                if (values[0] is object data)
                    foreach (var field in data.GetType().GetFields(flags))
                        Log($"  {field.Name}={field.GetValue(data)}");
            }
        }
        catch (Exception e) { Log("Extra heap diagnostics unavailable: " + e.Message); }
    }
    [STAThread]
    static void Main(string[] args)
    {
        if (args.Length == 2 && args[0] == "--serve-active")
        {
            try
            {
                using var target = DataTarget.AttachToProcess(int.Parse(args[1]), suspend: false);
                using var runtime = target.ClrVersions.Single().CreateRuntime();
                using var resolver = new ActiveClockResolver(int.Parse(args[1]), runtime);
                resolver.FindGame();
                Console.WriteLine("READY"); Console.Out.Flush();
                while (Console.ReadLine() is string command)
                {
                    if (command != "SAMPLE") break;
                    try
                    {
                        var sample = resolver.ReadStableSnapshot();
                        Console.WriteLine(FormattableString.Invariant($"OK\t{sample.Clock}\t{sample.Time:R}\t{(sample.Paused ? 1 : 0)}"));
                    }
                    catch (IOException e) { Console.WriteLine("ERR\t" + e.Message.Replace('\t', ' ').Replace('\n', ' ')); }
                    Console.Out.Flush();
                }
            }
            catch (Exception e) { Console.WriteLine("ERR\t" + e.Message.Replace('\n', ' ')); Environment.ExitCode = 1; }
            return;
        }
        if (args.Length == 2 && args[0] == "--trace-active")
        {
            try
            {
                int pid = int.Parse(args[1]);
                using var target = DataTarget.AttachToProcess(pid, suspend: false);
                using var runtime = target.ClrVersions.Single().CreateRuntime();
                using var resolver = new ActiveClockResolver(pid, runtime);
                Console.WriteLine(resolver.FindGame());
                for (int i = 0; i < 120; i++)
                {
                    try { Console.WriteLine(resolver.Resolve()); } catch (IOException e) { Console.WriteLine(e.Message); }
                    Thread.Sleep(250);
                }
            }
            catch (Exception e) { Console.Error.WriteLine(e); Environment.ExitCode = 1; }
            return;
        }
        if (args.Length == 2 && args[0] == "--runtime-info")
        {
            try { var result = RuntimeMetadata.Inspect(int.Parse(args[1])); Log(result); Console.WriteLine(result); }
            catch (Exception e) { Log(e.ToString()); Console.Error.WriteLine(e); Environment.ExitCode = 1; }
            return;
        }
        if (args.Length == 1 && args[0] == "--fixture")
        {
            var fixture = new osu.Game.Rulesets.UI.FrameStabilityContainer();
            var watch = Stopwatch.StartNew();
            while (watch.Elapsed.TotalSeconds < 45) { fixture.framedClock.CurrentTime = watch.Elapsed.TotalMilliseconds; Thread.Sleep(5); }
            GC.KeepAlive(fixture);
            return;
        }
        if (args.Length == 1 && args[0] == "--self-test")
        {
            using var fixture = Process.Start(new ProcessStartInfo(Environment.ProcessPath!) { Arguments = "--fixture", UseShellExecute = false, CreateNoWindow = true })!;
            try
            {
                Thread.Sleep(1000);
                var candidate = Scan(fixture.Id).Single(c => c.Kind == "Gameplay candidate");
                using var reader = new Reader(fixture.Id);
                double first = reader.Sample(candidate); Thread.Sleep(100); double second = reader.Sample(candidate);
                if (second - first < 50 || second - first > 1000) throw new Exception("Clock did not advance correctly");
                Console.WriteLine($"PASS: external discovery and live reads; clock advanced {second - first:F3} ms.");
                using var target = DataTarget.AttachToProcess(fixture.Id, suspend: false);
                using var runtime = target.ClrVersions.Single().CreateRuntime();
                var targeted = TargetedDiscovery.Scan(fixture.Id, runtime, false, Log);
                if (!targeted.Any(c => c.Owner == candidate.Owner && c.Time == candidate.Time))
                    throw new Exception("Targeted discovery did not find the fixture with the correct field addresses.");
                try { reader.Sample(candidate with { OwnerType = candidate.OwnerType ^ 8 }); throw new Exception("Stale identity was accepted."); }
                catch (IOException) { }
                Console.WriteLine("PASS: targeted discovery matches heap discovery; stale identity rejected.");
            }
            catch (Exception e) { Console.Error.WriteLine(e.ToString()); Environment.ExitCode = 1; }
            finally { if (!fixture.HasExited) fixture.Kill(); }
            return;
        }
        if (args.Length == 2 && args[0] == "--scan")
        {
            try { Console.WriteLine(JsonSerializer.Serialize(Scan(int.Parse(args[1])))); }
            catch (Exception e) { Log(e.ToString()); Console.Error.WriteLine(e.Message + " Diagnostics: " + LogPath); Environment.ExitCode = 1; }
            return;
        }
        ApplicationConfiguration.Initialize();
        Application.Run(new ProbeWindow());
    }

    static List<Candidate> Scan(int pid)
    {
        Log($"Discovery PID {pid}; ClrMD {typeof(DataTarget).Assembly.FullName}");
        using var target = DataTarget.AttachToProcess(pid, suspend: false);
        foreach (var clr in target.ClrVersions)
            Log($"Runtime: {clr.Version}; flavor {clr.Flavor}; module {clr.ModuleInfo.FileName}");
        Log("Creating diagnostic runtime (automatic DAC selection)");
        using var runtime = target.ClrVersions.Single().CreateRuntime();
        using (var self = Process.GetCurrentProcess())
            foreach (ProcessModule module in self.Modules)
                if (module.ModuleName.Contains("dac", StringComparison.OrdinalIgnoreCase))
                    Log($"Loaded diagnostic module: {module.FileName}; {module.FileVersionInfo.FileVersion}");
        Log("Opening managed heap");
        ClrHeap heap;
        try { heap = runtime.Heap; }
        catch (Exception e)
        {
            LogHeapFailure(runtime);
            var metadata = RuntimeMetadata.Inspect(pid);
            Log(metadata);
            if (metadata.Contains("1b2a0238-cd55-46eb-a2a2-723acef07a1f/1"))
            {
                Log("Using targeted Satori 2026.911.0 discovery (no GC heap API).");
                return TargetedDiscovery.Scan(pid, runtime, true, Log);
            }
            throw new IOException("The runtime diagnostic DLL could not expose the managed heap. See Diagnostics for the failing call; no clocks were discovered.", e);
        }
        Log($"Heap available; CanWalkHeap={heap.CanWalkHeap}");
        var result = new List<Candidate>();
        var watch = Stopwatch.StartNew();
        foreach (var obj in heap.EnumerateObjects())
        {
            if (watch.Elapsed.TotalSeconds > 20) break;
            var type = obj.Type;
            if (type == null) continue;
            bool gameplay = type.Name == "osu.Game.Rulesets.UI.FrameStabilityContainer";
            bool audio = type.Name == "osu.Framework.Audio.Track.TrackBass";
            if (!gameplay && !audio) continue;
            try
            {
                var linkField = gameplay ? type.GetFieldByName("framedClock") : null;
                var clock = gameplay ? obj.ReadObjectField("framedClock") : obj;
                var field = clock.Type?.GetFieldByName(gameplay ? "<CurrentTime>k__BackingField" : "currentTime");
                if (clock.IsNull || field == null || clock.Type == null) continue;
                result.Add(new Candidate(gameplay ? "Gameplay candidate" : "Audio reference", obj.Address,
                    type.MethodTable, linkField?.GetAddress(obj.Address) ?? 0, clock.Address,
                    clock.Type.MethodTable, field.GetAddress(clock.Address)));
            }
            catch { /* A live collection may invalidate an object while scanning. */ }
        }
        Log($"Discovery complete: {result.Count} candidates in {watch.Elapsed.TotalSeconds:F2}s");
        return result;
    }
}

sealed class Reader : IDisposable
{
    readonly SafeProcessHandle handle;
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern SafeProcessHandle OpenProcess(uint access, bool inherit, int pid);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool ReadProcessMemory(SafeProcessHandle process, nint address, byte[] data, nuint length, out nuint read);
    public Reader(int pid)
    {
        handle = OpenProcess(0x0410, false, pid); // Query information + VM_READ only; needed by VirtualQueryEx.
        if (handle.IsInvalid) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
    }
    public byte[] Bytes(ulong address, int length)
    {
        if (length < 1 || length > 4 * 1024 * 1024) throw new ArgumentOutOfRangeException(nameof(length));
        byte[] data = new byte[length];
        if (!ReadProcessMemory(handle, (nint)address, data, (nuint)length, out var count) || count != (nuint)length)
            throw new IOException("Address unreadable; discover again.");
        return data;
    }
    ulong Read(ulong address) => BitConverter.ToUInt64(Bytes(address, 8));
    [StructLayout(LayoutKind.Sequential)]
    struct MemoryInfo
    {
        public ulong BaseAddress, AllocationBase;
        public uint AllocationProtect, Partition;
        public ulong RegionSize;
        public uint State, Protect, Type, Padding;
    }
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern nuint VirtualQueryEx(SafeProcessHandle process, nint address, out MemoryInfo info, nuint length);
    public IEnumerable<(ulong Start, ulong End)> PrivateRanges()
    {
        ulong address = 0;
        while (address < 0x0000800000000000 && VirtualQueryEx(handle, (nint)address, out var info, (nuint)Marshal.SizeOf<MemoryInfo>()) != 0)
        {
            ulong end = checked(info.BaseAddress + info.RegionSize);
            if (end <= address) yield break;
            if (info.State == 0x1000 && info.Type == 0x20000 && (info.Protect & 0x100) == 0 &&
                (info.Protect & 0xff) is 0x02 or 0x04 or 0x08 or 0x20 or 0x40 or 0x80)
                yield return (info.BaseAddress, end);
            address = end;
        }
    }
    void Validate(Candidate c)
    {
        if (Read(c.Owner) != c.OwnerType || Read(c.Clock) != c.ClockType ||
            (c.Link != 0 && Read(c.Link) != c.Clock))
            throw new IOException("Object changed; discover again.");
    }
    public double Sample(Candidate c)
    {
        Validate(c);
        var value = BitConverter.UInt64BitsToDouble(Read(c.Time));
        Validate(c);
        if (!double.IsFinite(value)) throw new IOException("Invalid clock value.");
        return value;
    }
    public void Dispose() => handle.Dispose();
}

sealed class ProbeWindow : Form
{
    readonly ComboBox processes = new() { Width = 340, DropDownStyle = ComboBoxStyle.DropDownList };
    readonly Button refresh = new() { Text = "Refresh processes", AutoSize = true };
    readonly Button discover = new() { Text = "Discover clocks", AutoSize = true };
    readonly Button stop = new() { Text = "Stop sampling", AutoSize = true };
    readonly Button diagnostics = new() { Text = "Diagnostics", AutoSize = true };
    readonly Label status = new() { Dock = DockStyle.Bottom, Height = 56, Text = "Launch lazer, enter a map and pause, then discover clocks." };
    readonly DataGridView grid = new() { Dock = DockStyle.Fill, ReadOnly = true, AllowUserToAddRows = false, AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill };
    readonly System.Windows.Forms.Timer timer = new() { Interval = 20 };
    readonly List<Candidate> candidates = [];
    readonly Dictionary<ulong, double> previous = [];
    Reader? reader;
    Process? scanProcess;
    public ProbeWindow()
    {
        Text = "osu! live clock probe — read only"; Width = 1080; Height = 480;
        var controls = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 48 };
        controls.Controls.AddRange([processes, refresh, discover, stop, diagnostics]);
        foreach (var name in new[] { "Kind", "Clock address", "Time (ms)", "Change (ms)", "Read (µs)", "State" }) grid.Columns.Add(name, name);
        Controls.Add(grid); Controls.Add(controls); Controls.Add(status);
        refresh.Click += (_, _) => RefreshProcesses();
        discover.Click += async (_, _) => await Discover();
        stop.Click += (_, _) => StopSampling();
        diagnostics.Click += (_, _) =>
        {
            using var window = new Form { Text = "Discovery diagnostics", Width = 1000, Height = 650 };
            var text = new TextBox { Multiline = true, ReadOnly = true, Dock = DockStyle.Fill,
                ScrollBars = ScrollBars.Both, WordWrap = false };
            try { text.Text = File.Exists(Program.LogPath) ? File.ReadAllText(Program.LogPath) : "No discovery log yet."; }
            catch (Exception e) { text.Text = e.Message; }
            window.Controls.Add(text); window.ShowDialog(this);
        };
        timer.Tick += (_, _) => Sample();
        FormClosed += (_, _) => { StopSampling(); if (scanProcess is { HasExited: false }) scanProcess.Kill(); timer.Dispose(); };
        RefreshProcesses();
    }
    record Target(int Id, string Title) { public override string ToString() => $"PID {Id}: {Title}"; }
    void RefreshProcesses()
    {
        processes.Items.Clear();
        foreach (var p in Process.GetProcessesByName("osu!"))
        {
            using (p) processes.Items.Add(new Target(p.Id, p.MainWindowTitle));
        }
        if (processes.Items.Count > 0) processes.SelectedIndex = 0;
        else status.Text = "No osu! process found. Launch lazer, then Refresh processes.";
    }
    void StopSampling() { timer.Stop(); reader?.Dispose(); reader = null; status.Text = "Sampling stopped."; }
    async Task Discover()
    {
        if (processes.SelectedItem is not Target selected) return;
        StopSampling(); discover.Enabled = refresh.Enabled = processes.Enabled = false;
        candidates.Clear(); previous.Clear(); grid.Rows.Clear();
        status.Text = "Scanning live heap without suspending lazer (30-second limit)…";
        try
        {
            var info = new ProcessStartInfo(Environment.ProcessPath!) { UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true };
            info.ArgumentList.Add("--scan"); info.ArgumentList.Add(selected.Id.ToString());
            using var child = Process.Start(info) ?? throw new IOException("Could not start scanner");
            scanProcess = child;
            var output = child.StandardOutput.ReadToEndAsync(); var error = child.StandardError.ReadToEndAsync();
            using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            try { await child.WaitForExitAsync(deadline.Token); }
            catch (OperationCanceledException) { child.Kill(); throw new IOException("Discovery timed out. Pause the map and retry."); }
            if (IsDisposed) return;
            if (child.ExitCode != 0) throw new IOException(await error);
            candidates.Clear(); candidates.AddRange(JsonSerializer.Deserialize<List<Candidate>>(await output) ?? []);
            previous.Clear(); grid.Rows.Clear();
            foreach (var c in candidates) grid.Rows.Add(c.Kind, $"0x{c.Clock:X}", "", "", "", "Awaiting sample");
            reader = new Reader(selected.Id); timer.Start();
            status.Text = $"Found {candidates.Count} candidates. Resume, pause and retry to identify the gameplay clock. Rediscover if addresses become stale. No input is sent.";
        }
        catch (Exception e) { if (!IsDisposed) status.Text = "Discovery failed: " + e.Message; }
        finally { scanProcess = null; if (!IsDisposed) discover.Enabled = refresh.Enabled = processes.Enabled = true; }
    }
    void Sample()
    {
        if (reader == null) return;
        for (int i = 0; i < candidates.Count; i++)
        {
            var c = candidates[i]; var row = grid.Rows[i];
            try
            {
                long start = Stopwatch.GetTimestamp(); double value = reader.Sample(c);
                double micros = Stopwatch.GetElapsedTime(start).TotalMicroseconds;
                row.Cells[2].Value = value.ToString("F3");
                row.Cells[3].Value = previous.TryGetValue(c.Clock, out var last) ? (value - last).ToString("F3") : "—";
                row.Cells[4].Value = micros.ToString("F1"); row.Cells[5].Value = "Readable; verify behavior";
                previous[c.Clock] = value;
            }
            catch (Exception e) { row.Cells[2].Value = "—"; row.Cells[5].Value = e.Message; previous.Remove(c.Clock); }
        }
    }
}
