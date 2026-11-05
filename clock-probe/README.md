# Read-only lazer clock probe

Build with `dotnet build clock-probe/ClockProbe.csproj -c Release`.
Run `clock-probe/bin/Release/net10.0-windows/ClockProbe.exe` (requires .NET 10 desktop runtime).

Start lazer, enter a map and pause. Select its process and click **Discover clocks**.
Discovery runs in a separate helper with a 30-second timeout, using ClrMD's non-suspending attachment.
Resume/pause/retry and observe the gameplay candidates. Audio references are shown for comparison.
Discovery is manual; repeat after a map change or an invalidated address.

The display samples at approximately 50 Hz. This is a diagnostic display, not the playback scheduler.
Reads verify object method tables and the owner-to-clock reference before and after reading the time.
These checks detect some stale addresses, but do not prove object identity after garbage collection.
Multiple candidates may include old maps or other gameplay screens. A readable candidate is not automatically the active player.
No input is sent and no game memory is written. The probe does not attach a debugger, inject code, or suspend lazer.

`ClockProbe.exe --self-test` starts a controlled managed fixture and tests ordinary and targeted discovery,
matching field addresses, advancing external reads, and rejection of a mismatched object identity.
`ClockProbe.exe --scan PID` writes JSON candidate addresses to stdout for diagnostics.

## Discovery diagnostics

The **Diagnostics** button shows `discovery.log` next to the executable, including the runtime version,
loaded diagnostic DLL, full exception and (when available) individual heap-query results.
The log includes local paths, process IDs and memory addresses; review it before sharing.

Testing on the installed 2026.921.0-lazer release found that both `coreclr.dll` and
`mscordaccore.dll` exactly match the cached release package (SHA-256). The runtime reports
42.42.42.42424 and the DAC reports 10.0.1226.42308. Those differing version strings alone
are not evidence of a damaged installation. ClrMD 4.1.745802 and 4.0.732401 both loaded
the DAC, successfully queried common method tables, but received `E_FAIL` from
`GetGCHeapData`. Conventional DAC heap enumeration remains unsupported in this tested process;
the follow-up investigation below identifies a Satori diagnostic-support limitation as the leading explanation. No game files were changed.

For comparative testing only, build with `-p:ClrMdVersion=4.0.732401` and a separate output directory.

## Satori runtime investigation

The installed runtime matches **ppy/Satori 2026.911.0**, not the latest Satori release:

- PDB identity: `1b2a0238-cd55-46eb-a2a2-723acef07a1f`, age 1.
- `.text` SHA-256: `2CABE84B9408B9EABDF4B1B4F8C1B6D5ABB596FF21651B49400C6F53843150E0`.
- `.rdata` SHA-256: `BF522FF1650BF572580539376C028949F620D897462ACC1C0EA0F9578FE90721`.

These section hashes and PDB identity were compared with the published `win-x64.zip` asset in memory;
no runtime files were installed or replaced. Whole-file hashes need not match after signing.

The matching [GC initialization source](https://github.com/ppy/Satori/blob/2026.911.0/src/coreclr/gc/gcload.cpp)
creates Satori without calling the workstation/server `PopulateDacVars` routines. Only handle-table
DAC variables are populated afterward. The conventional heap query depends on those GC diagnostic
variables. This strongly explains why common method-table queries succeed while `GetGCHeapData`
fails. The exact failing native instruction has not been traced, so this is not a claim that every
diagnostic operation is unsupported.

`ClockProbe.exe --runtime-info PID` reads the runtime's PE debug identity and exported runtime
contract descriptor. It resolves only declared GC-related pointer entries with bounded reads.
This metadata is also logged automatically after a heap-discovery failure. In the tested runtime,
the descriptor did not expose the GC heap globals needed to replace the failing DAC query.

## Implemented targeted discovery

On this verified Satori runtime identity, discovery now automatically falls back to a targeted
read-only scan. It gets method tables and field offsets from the working DAC type-metadata APIs,
without requesting the unsupported heap API. It scans readable committed private memory for exact
method-table matches, checks Satori region/page headers, follows the actual `framedClock` field,
and rechecks both type identities and the reference before accepting a finite time value.

The Satori layout checks are gated to the PDB identity above; an unknown runtime does not silently
reuse those offsets. This is a targeted pattern scanner, not a complete Satori heap walker or
proof that an object is live or belongs to the current player. Old map objects can remain readable.
Validate pause/resume/retry behavior before choosing any candidate.

Reads use 1 MiB chunks, yield periodically, and stop after 20 seconds or 2 GiB of successful reads;
the UI also retains its 30-second helper deadline. Incomplete scans report an error, not success.
The process handle grants query/read permissions only. No game writes, injection, suspension,
debugger attachment, input simulation, or game file changes are used.

Initial live validation found three gameplay and two audio candidates in 8.65 seconds.
Replay-clock integration and identification of the active gameplay candidate remain separate work.

## Experimental active-screen resolver

`ClockProbe.exe --trace-active PID` performs a bounded initial scan for the desktop game instance,
then traces the active screen for 30 seconds at 250 ms intervals. This diagnostic command is not
yet connected to the probe UI or Java replay scheduler.

The decompiled ownership chain is:

`OsuGame.<ScreenStack>k__BackingField` → `ScreenStack.stack` → `Stack<IScreen>` top element
→ `Player.<DrawableRuleset>k__BackingField` → `DrawableRuleset<T>.frameStabilityContainer`
→ `FrameStabilityContainer.framedClock` → `FramedClock.<CurrentTime>k__BackingField`.

Field offsets are resolved from runtime metadata and cached by method table. The stack array layout
currently assumes x64 .NET SZARRAY. The game-instance address is cached and checked, not a durable
GC handle: if the root moves, rediscovery is required. No static game singleton was identified in
the inspected entry point/game/host classes. Type checks alone cannot exclude address reuse.

Live test on 2026-10-02: initial root scan took about 6 seconds. The same resolver followed menu,
song selection, loader, and SoloPlayer, then a return to selection and a new SoloPlayer clock
(`0xAB15E298` → `0x970A0E20`) without rescanning. Warm resolutions were commonly 100–200 µs;
initial metadata lookup took several milliseconds. These are resolution costs, not guarantees of
input precision, GC-movement recovery, or clock update frequency. Reads retry/reject changing
chain state, and the command sends no input or game-memory writes.
