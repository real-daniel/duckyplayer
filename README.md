# Ducky Player

Ducky Player is a Java 21 Windows desktop application that reads osu!standard `.osr` files and
replays their cursor and key data into a foreground osu! window. It is intended
for recording existing replays locally in osu!lazer.

## Features

- Native `.osr` parsing and LZMA decompression.
- Built-in visual replay editor with a 512×384 path canvas, brush deformation,
  four input lanes, drag-range selection, selected M1/M2 and K1/K2 reversal, scrubbing,
  preview playback, undo/redo, vertical mirroring,
  hit-circle/slider/spinner overlays, OD/position-based 300/100/50/miss evaluation,
  slider tick/repeat/tail tracking, rotation-based spinner evaluation,
  accuracy and combo summaries, miss navigation, left-click input-block insertion,
  right-click input-block removal, a configurable conservative accuracy margin,
  precise frame editing, and verified `.osr` export.
- The editor applies osu!'s legacy hit-object stacking, stack leniency, circle-radius
  replay tolerance, legacy coordinate decoding, note lock, Hidden fading,
  Easy/Hard Rock CS/AR/OD transforms, and Relax/Spun Out automation rules. Its coordinate viewport
  automatically expands when the visible replay path leaves the outlined 512×384 playfield.
- `.osu` and `.osz` drag-and-drop; beatmapsets are matched to the replay difficulty by MD5 without extraction.
- Native replay timing or 240, 500, and 1000 Hz cursor interpolation.
- Key and mouse-button transitions retain their exact replay timestamps.
- Automatic Hard Rock beatmap transforms in the editor and DT/Nightcore/Half Time clock-rate conversion.
- Primary audio-clock synchronization using timestamped WASAPI loopback capture.
- Automatic reference-audio resolution from the stable `Songs` library.
- Lazer runtime-log synchronization remains available as a fallback.
- F8 provides a manual fallback; F12 is a global emergency stop.
- Playback stops as soon as the selected osu! window loses foreground focus.
- Configurable, saved K1/K2 replay keyboard bindings (Z/X by default).
- Every stop path releases the selected K1/K2 keys and both mouse buttons.
- Normal-priority playback thread and interruptible native waits capped at 5 ms.
- Optional read-only active-gameplay memory-clock synchronization and cadence measurement.
- No global input hooks, game-memory writes, injection, elevated priority, or busy loop.

## Memory-clock mode (experimental)

The Java UI now includes **Measure clock (no input)** and **Arm memory sync**.
The bundled read-only .NET helper requires the .NET 10 Desktop Runtime. It discovers the game
instance once per armed session, then follows the active-screen ownership chain on each request.
It does not scan the whole process again on each map switch during that session.

Test **Measure clock** first: select lazer, click the button, wait for “Clock ready”, then focus
lazer and play for 10 seconds without pausing. This mode sends no cursor, key, or release events.
The final status reports median sampling interval and observed clock increments; these are
measurements of our observations, not proof of every internal clock update. CSV logs with
monotonic timestamps, read duration, clock identity, game time, pause and focus state are saved in
`%USERPROFILE%\ducky-player-clock-logs`. Measurement logs every sample. To keep diagnostics from
interrupting cursor output, playback logs at up to 100 Hz and additionally records the first sample
after any detected scheduling stall. The UI's Change column is not used for this measurement.
Measurement waits for successive advancing samples rather than treating an initial zero clock as
gameplay. Pauses, retries and loading stalls reset the 10-second observation window instead of
raising a playback-stall error. A `.csv.status.txt` companion records the mode and final result.
The initial/waiting session remains bounded to 120 seconds; F12 and focus loss still stop it.

For playback, load the replay, select the matching map and mods in lazer, click **Arm memory sync**,
wait for “Clock ready”, then focus lazer and start/retry the map. Rate-changing mods such as DT are
supported because lazer's gameplay clock and replay frames share beatmap time. It must observe the
current clock at zero or in negative lead-in,
then advancing while unpaused. A normal start is accepted within the first 100 ms; a later initial
jump is accepted only when it is a safe intro skip before the replay's first pressed input.
It will not join gameplay already in progress. Map/replay identity is not automatically checked. Positive offset delays
input in game-time milliseconds. The existing cursor-output selection still applies.

Playback follows observed game time directly—no wall-clock extrapolation. Requested polling is
about 1 ms plus IPC/read/scheduling overhead, not a guaranteed 1000 Hz. Coarse clock updates can
still cause multiple due events per sample; exact recording accuracy needs live validation.
The packaged runtime uses generational ZGC, a fixed heap, cached window geometry and phase-stable
cursor deadlines to minimize Java pauses and timing drift in the high-frequency playback path.
Clock jumps are accepted when they cross cursor movement and releases without losing a key or
mouse-button press; Ducky Player advances directly to the matching cursor and input state instead
of emitting skipped frames in a burst. Unsafe forward jumps, pause, map/clock changes, rewind, stalled clocks, helper timeouts, invalid pointers,
focus loss and F12 stop playback and release inputs. Re-arm after a stop.
The cached game-root address is validated but not protected from GC relocation or address reuse;
a moved root requires a new discovery. Measurement and playback are not automatically started.

## Build

The build requires a Java 21 JDK and .NET 10 SDK (or a compatible newer SDK). On this PC Java uses:

`C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot`

Run:

```powershell
.\build.ps1 -Clean
```

The script downloads four pinned libraries from Maven Central, verifies their
SHA-256 checksums, compiles with all Java lint warnings treated as errors, runs
the self-tests, and creates a self-contained application image at:

`build\package\Ducky Player\Ducky Player.exe`

The packaged application includes its own reduced Java 21 runtime. It does not
use or require the machine's default Java installation.

Use `-SkipPackage` for a quicker compile-and-test build.

## Use

1. Launch osu!lazer and `Ducky Player.exe`.
2. Load an osu!standard `.osr` file, or drag it directly onto the replay field.
   HR and DT are read from the replay header automatically. HR flips the editor's
   beatmap overlay and applies its CS/AR/OD difficulty changes; DT runs playback
   and editor preview at 1.5× while replay timestamps remain in beatmap time.
   Use **Edit replay…** to reshape the cursor path or edit key/mouse lanes, then
   export a separate `.osr`; the original replay is never overwritten automatically.
   Export requires the matching beatmap and refreshes the estimated 300/100/50/miss,
   maximum-combo, and perfect fields. The original score and replay checksum are retained
   because exact score reconstruction requires the complete osu! ruleset.
3. Select the osu! process, timing offset, cursor output rate, and K1/K2 replay keys.
   Set lazer's hit-key bindings to the same keys. Replay mouse lanes still use mouse buttons.
4. Open the matching beatmap in osu! and reset custom playfield positioning.
5. Disable **High precision mouse** in lazer so injected absolute cursor
   positions are used.
6. Click **Arm audio sync** and wait until the status says that audio sync is
   ready. Switch to lazer and start the map normally. The player identifies the
   opening beatmap audio in the Windows output stream and anchors replay timing
   to its WASAPI hardware timestamp. Keep music audible and do not skip intro.
7. Press **F12** at any time to cancel or release every injected input.

Audio sync currently supports No Mod replays. It automatically finds the exact
`.osu` by its replay hash under `%LOCALAPPDATA%\osu!\Songs` and decodes the
corresponding MP3. Use **Arm log sync (fallback)** for DT, NC, HT, a missing
stable beatmap, or an opening that cannot be identified reliably. **Arm manual
(F8)** is the final fallback.

The timing adjustment is measured in milliseconds. Positive values delay replay
input and negative values send it earlier. A small negative adjustment can
compensate for a measured constant decoder or output-device offset.

### Output rates

- **Native replay frames** sends only positions stored in the replay.
- **240/500/1000 Hz interpolated** linearly resamples cursor positions while
  preserving original key-transition times.

Interpolation makes low-rate source replays look smoother but cannot recreate
movement that was never recorded. The default is 1000 Hz.

## Target support

osu!lazer is the primary target. osu!stable windows are also detected and use
the same standard 512x384 playfield transform, but stable remains compatibility
support rather than the primary tested target.

## Safety and scope

Use the application only for local replay recording, not online score
submission. Input is injected only while the explicitly selected osu! window is
foreground.

## Dependencies

See [THIRD_PARTY.md](THIRD_PARTY.md) for pinned versions and upstream licenses.
# duckyplayer
