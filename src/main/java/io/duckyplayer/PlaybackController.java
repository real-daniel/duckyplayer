package io.duckyplayer;

import com.sun.jna.platform.win32.Kernel32;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class PlaybackController implements AutoCloseable {
    private static final long PLAYBACK_LOG_INTERVAL_NANOS = 10_000_000L;
    private static final long STALL_LOG_THRESHOLD_NANOS = 3_000_000L;
    interface Listener {
        void onStarted();
        void onProgress(int perMille);
        void onStopped(StopReason reason, String detail);
        default void onStatus(String message) { }
    }

    enum StopReason { COMPLETED, STOPPED, FOCUS_LOST, ERROR }

    record SkipPoint(int index, int keys) { }

    enum OutputRate {
        NATIVE("Native replay frames", 0),
        HZ_240("240 Hz interpolated", 240),
        HZ_500("500 Hz interpolated", 500),
        HZ_1000("1000 Hz interpolated", 1000);

        private final String label;
        private final int hertz;

        OutputRate(String label, int hertz) {
            this.label = label;
            this.hertz = hertz;
        }

        int hertz() { return hertz; }

        @Override
        public String toString() { return label; }
    }

    private final Listener listener;
    private final NativeWindows.HighResolutionWaiter waiter = new NativeWindows.HighResolutionWaiter();
    private final NativeWindows.InputSender input = new NativeWindows.InputSender();
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;
    private volatile MemoryClock liveClock;
    private volatile boolean diagnosticOnly;

    synchronized boolean startLive(NativeWindows.TargetWindow target, ReplayData replay, int offsetMs,
                                   OutputRate rate, boolean measureOnly) {
        if (running.get() || target == null || (!measureOnly && (replay == null || replay.frames().isEmpty()))) return false;
        joinFinishedWorker(); waiter.reset(); diagnosticOnly = measureOnly; running.set(true);
        worker = Thread.ofPlatform().name("memory-clock-playback").priority(Thread.NORM_PRIORITY)
                .start(() -> runLive(target, replay, offsetMs, rate, measureOnly));
        return true;
    }

    private void runLive(NativeWindows.TargetWindow target, ReplayData replay, int offsetMs,
                         OutputRate rate, boolean measureOnly) {
        java.nio.file.Path log = java.nio.file.Path.of(System.getProperty("user.home"), "ducky-player-clock-logs",
                "clock-" + java.util.UUID.randomUUID() + ".csv");
        StopReason reason = StopReason.STOPPED;
        String detail = "Memory-clock session stopped.";
        try {
            java.nio.file.Files.createDirectories(log.getParent());
            try (var csv = java.nio.file.Files.newBufferedWriter(log);
                 var clock = new MemoryClock(target.processId())) {
                liveClock = clock;
                if (waiter.isStopped()) return;
                clock.awaitReady();
                notifyOnUi(() -> listener.onStatus(measureOnly ? "Clock ready. Focus lazer and play for 10 seconds; no input will be sent."
                        : "Clock ready. Focus lazer and start/retry the matching map. Waiting for a fresh clock at zero. F12 cancels."));
                csv.write("elapsed_ns,read_ns,clock_identity,game_ms,paused,foreground,active,replay_frame_index\n");
                long started = System.nanoTime(), deadline = started + 120_000_000_000L;
                long identity = 0, lastAdvance = started, measureStart = 0, lastSample = 0;
                long nextCursor = 0, nextProgress = 0, nextLog = 0, previousLoop = 0;
                long cursorInterval = rate.hertz() == 0 ? 0 : 1_000_000_000L / rate.hertz();
                double lastTime = Double.NaN;
                boolean focused = false, playing = false;
                int index = 0, keys = 0;
                var intervals = new ArrayList<Double>();
                var changes = new ArrayList<Double>();
                MemoryClock.Sample previousWaiting = null;
                boolean freshClockSeen = false;
                while (!waiter.isStopped()) {
                    boolean foreground = NativeWindows.isForeground(target.handle());
                    if (focused && !foreground) throw new FocusLostException();
                    focused |= foreground;
                    MemoryClock.Sample sample = clock.sample();
                    long now = sample.receivedNanos();
                    // Full-rate text logging created enough short-lived objects to cause periodic
                    // stop-the-world pauses during playback. Measurement remains full-rate; normal
                    // playback logs at 100 Hz and always captures the first sample after a stall.
                    boolean stalled = previousLoop != 0 && now - previousLoop >= STALL_LOG_THRESHOLD_NANOS;
                    if (measureOnly || now >= nextLog || stalled) {
                        csv.write((now - started) + "," + sample.readNanos() + ","
                                + Long.toUnsignedString(sample.identity()) + "," + sample.timeMs() + ","
                                + sample.paused() + "," + foreground + "," + playing + "," + index + "\n");
                        if (!measureOnly) nextLog = nextPeriodicDeadline(nextLog,
                                PLAYBACK_LOG_INTERVAL_NANOS, now);
                    }
                    previousLoop = now;
                    if (!playing) {
                        if (now > deadline) throw new IllegalStateException("No fresh gameplay start within 120 seconds.");
                        if (previousWaiting == null || previousWaiting.identity() != sample.identity()) freshClockSeen = false;
                        if (sample.identity() != 0 && sample.timeMs() <= 0) freshClockSeen = true;
                        double replayTime = measureOnly || replay == null ? sample.timeMs()
                                : sample.timeMs() - offsetMs;
                        SkipPoint introStart = measureOnly || replay == null ? null
                                : safeSkipIndex(replay.frames(), index, keys, replayTime);
                        boolean regularStart = MemoryClock.isFreshStart(freshClockSeen, sample);
                        boolean introSkipStart = freshClockSeen && sample.timeMs() > 100 && introStart != null;
                        if (foreground && sample.identity() != 0 && !sample.paused()
                                && MemoryClock.isAdvancing(previousWaiting, sample)
                                && (measureOnly || regularStart || introSkipStart)) {
                            playing = true; identity = sample.identity(); measureStart = now; lastAdvance = now;
                            lastTime = sample.timeMs();
                            if (introSkipStart && introStart.index() > index) {
                                index = introStart.index();
                                ReplayFrame current = replay.frames().get(index - 1);
                                ensureForeground(target.handle());
                                if (!input.sendCursor(target.handle(), current.x(), current.y())
                                        || !input.sendKeyTransitions(keys, introStart.keys()))
                                    throw new IllegalStateException("SendInput rejected while applying intro skip.");
                                keys = introStart.keys();
                            }
                            notifyOnUi(() -> listener.onStatus(measureOnly ? "Measuring clock cadence—no input sent. F12 cancels."
                                    : introSkipStart ? "Intro skip detected; replay advanced safely. F12 stops."
                                    : "Memory-clock playback active. F12 stops."));
                        }
                        previousWaiting = sample;
                    } else {
                        // Measurement has no held inputs: pause/loading/retry restarts its observation window.
                        // Playback retains the stricter fail-closed behavior below.
                        if (measureOnly && (sample.identity() != identity || sample.paused()
                                || sample.timeMs() < lastTime - 0.5
                                || (sample.timeMs() <= lastTime && now - lastAdvance > 250_000_000L))) {
                            playing = false; previousWaiting = null; lastSample = 0;
                            intervals.clear(); changes.clear();
                            notifyOnUi(() -> listener.onStatus("Measurement waiting for an advancing clock. Resume/play for 10 seconds. No input sent."));
                            if (waiter.waitForNanos(5_000_000L)) break;
                            continue;
                        }
                        if (sample.identity() != identity || sample.paused())
                            throw new IllegalStateException("Map changed or paused. Re-arm for a fresh start.");
                        if (sample.timeMs() < lastTime - 0.5) throw new IllegalStateException("Clock rewound. Re-arm after retry.");
                        if (!measureOnly && sample.timeMs() - lastTime > 100) {
                            double replayTime = sample.timeMs() - offsetMs;
                            SkipPoint skipped = safeSkipIndex(replay.frames(), index, keys, replayTime);
                            if (skipped == null)
                                throw new IllegalStateException("Clock jumped forward during gameplay. Re-arm; skipped key presses will not be replayed late.");
                            if (skipped.index() > index) {
                                index = skipped.index();
                                ReplayFrame current = replay.frames().get(index - 1);
                                ensureForeground(target.handle());
                                if (!input.sendCursor(target.handle(), current.x(), current.y())
                                        || !input.sendKeyTransitions(keys, skipped.keys()))
                                    throw new IllegalStateException("SendInput rejected while applying intro skip.");
                                keys = skipped.keys();
                                notifyOnUi(() -> listener.onStatus("Intro skip detected; replay advanced safely. F12 stops."));
                            }
                        }
                        if (measureOnly && lastSample != 0) intervals.add((now - lastSample) / 1_000_000.0);
                        if (sample.timeMs() > lastTime) { if (measureOnly) changes.add(sample.timeMs() - lastTime); lastAdvance = now; }
                        if (now - lastAdvance > 250_000_000L) throw new IllegalStateException("Gameplay clock stalled.");
                        lastTime = sample.timeMs(); lastSample = now;
                        if (measureOnly) {
                            if (now - measureStart >= 10_000_000_000L) {
                                reason = StopReason.COMPLETED;
                                detail = String.format("No-input measurement: median sample %.3f ms; observed clock step %.3f ms (p95 %.3f ms).",
                                        percentile(intervals, .5), percentile(changes, .5), percentile(changes, .95));
                                break;
                            }
                        } else {
                            double time = sample.timeMs() - offsetMs;
                            List<ReplayFrame> frames = replay.frames();
                            int dueEnd = index;
                            boolean hasTransition = false;
                            int dueKeys = keys;
                            while (dueEnd < frames.size() && frames.get(dueEnd).timeMs() <= time) {
                                int frameKeys = frames.get(dueEnd).keys();
                                hasTransition |= frameKeys != dueKeys;
                                dueKeys = frameKeys;
                                dueEnd++;
                            }
                            boolean cursorDue = nextCursor == 0 || now >= nextCursor;
                            if (rate.hertz() != 0 && dueEnd > 0
                                    && (cursorDue || hasTransition) && !waiter.isStopped()) {
                                // A key transition may request an early cursor placement, but it must
                                // not shift the regular cursor phase. Due ticks advance from their
                                // previous deadline so scheduler jitter cannot accumulate as drift.
                                if (cursorDue) nextCursor = nextPeriodicDeadline(nextCursor, cursorInterval, now);
                                ReplayFrame from = frames.get(dueEnd - 1);
                                ReplayFrame to = frames.get(Math.min(dueEnd, frames.size() - 1));
                                double fraction = to.timeMs() == from.timeMs() ? 0
                                        : Math.clamp((time - from.timeMs()) / (to.timeMs() - from.timeMs()), 0, 1);
                                ensureForeground(target.handle());
                                if (!input.sendCursor(target.handle(), from.x() + (to.x() - from.x()) * fraction,
                                        from.y() + (to.y() - from.y()) * fraction))
                                    throw new IllegalStateException("SendInput rejected.");
                            }
                            // Preserve every key transition. Interpolated mode has already placed
                            // the cursor at the current position, avoiding stale-frame snap-backs.
                            while (index < dueEnd) {
                                if (waiter.isStopped()) break;
                                ensureForeground(target.handle());
                                ReplayFrame frame = frames.get(index++);
                                if ((rate.hertz() == 0 && !input.sendCursor(target.handle(), frame.x(), frame.y()))
                                        || !input.sendKeyTransitions(keys, frame.keys()))
                                    throw new IllegalStateException("SendInput rejected.");
                                keys = frame.keys();
                            }
                            if (index == frames.size()) { reason = StopReason.COMPLETED; detail = "Memory-clock playback complete."; break; }
                            if (now >= nextProgress) { notifyProgress(index, frames.size()); nextProgress = now + 100_000_000L; }
                        }
                    }
                    long delay = playing ? 1_000_000L : 5_000_000L;
                    if (waiter.waitForNanos(delay)) break;
                }
            }
        } catch (FocusLostException e) { reason = StopReason.FOCUS_LOST; detail = "Focus lost. Inputs released.";
        } catch (java.io.IOException | RuntimeException e) {
            reason = waiter.isStopped() ? StopReason.STOPPED : StopReason.ERROR;
            detail = waiter.isStopped() ? "Memory-clock session stopped." : "Memory-clock error: " + e.getMessage();
        } finally {
            liveClock = null; if (!measureOnly) input.releaseAll(); running.set(false);
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(log.toString() + ".status.txt"),
                        "Mode: " + (measureOnly ? "measurement" : "playback") + "\nResult: " + reason + "\n" + detail + "\n");
            } catch (java.io.IOException ignored) { /* Preserve the original failure in the UI. */ }
            finish(reason, detail + " Log: " + log);
        }
    }

    private static double percentile(List<Double> values, double fraction) {
        if (values.isEmpty()) return Double.NaN;
        values.sort(Double::compare);
        return values.get((int)Math.floor((values.size()-1)*fraction));
    }

    /** Collapses cursor movement and releases, but never skips a key/button press. */
    static SkipPoint safeSkipIndex(List<ReplayFrame> frames, int currentIndex, int currentKeys,
                                   double targetTimeMs) {
        int next = Math.max(0, currentIndex);
        int keys = currentKeys;
        while (next < frames.size() && frames.get(next).timeMs() <= targetTimeMs) {
            int nextKeys = frames.get(next).keys();
            if ((nextKeys & ~keys) != 0) return null;
            keys = nextKeys;
            next++;
        }
        return new SkipPoint(next, keys);
    }

    PlaybackController(Listener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    synchronized void setKeyboardKeys(NativeWindows.PlaybackKey first, NativeWindows.PlaybackKey second) {
        if (running.get()) throw new IllegalStateException("Cannot change keys during playback.");
        input.setKeyboardKeys(first, second);
    }

    synchronized boolean start(NativeWindows.TargetWindow target, ReplayData replay,
                               int offsetMs, OutputRate outputRate) {
        return startAt(target, replay, offsetMs, outputRate, System.nanoTime());
    }

    synchronized boolean startAt(NativeWindows.TargetWindow target, ReplayData replay,
                                 int offsetMs, OutputRate outputRate, long replayZeroNanos) {
        if (running.get() || target == null || replay.frames().isEmpty()) return false;
        joinFinishedWorker();
        waiter.reset();
        diagnosticOnly = false;
        running.set(true);
        worker = Thread.ofPlatform().name("replay-playback").priority(Thread.NORM_PRIORITY)
                .start(() -> run(target, replay, offsetMs, outputRate, replayZeroNanos));
        return true;
    }

    synchronized void stop() {
        waiter.stop();
        MemoryClock clock = liveClock;
        if (clock != null) clock.close();
        Thread currentWorker = worker;
        if (currentWorker != null && currentWorker != Thread.currentThread()) {
            try {
                currentWorker.join(3000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        running.set(false);
        if (!diagnosticOnly) input.releaseAll();
    }

    boolean isRunning() {
        return running.get();
    }

    private void run(NativeWindows.TargetWindow target, ReplayData replay, int offsetMs,
                     OutputRate outputRate, long replayZeroNanos) {
        Kernel32.INSTANCE.SetThreadPriority(Kernel32.INSTANCE.GetCurrentThread(), Kernel32.THREAD_PRIORITY_NORMAL);
        notifyOnUi(listener::onStarted);
        try {
            if (outputRate.hertz() == 0) playNative(target, replay, offsetMs, replayZeroNanos);
            else playInterpolated(target, replay, offsetMs, outputRate.hertz(), replayZeroNanos);
            if (waiter.isStopped()) finish(StopReason.STOPPED, "Stopped. All injected inputs released.");
            else finish(StopReason.COMPLETED, "Playback complete. All injected inputs released.");
        } catch (FocusLostException exception) {
            finish(StopReason.FOCUS_LOST, "Stopped because osu! lost foreground focus. Inputs released.");
        } catch (RuntimeException exception) {
            finish(StopReason.ERROR, "Playback error: " + exception.getMessage());
        } finally {
            input.releaseAll();
            running.set(false);
        }
    }

    private void playNative(NativeWindows.TargetWindow target, ReplayData replay,
                            int offsetMs, long replayZeroNanos) {
        int previousKeys = 0;
        List<ReplayFrame> frames = replay.frames();
        int startIndex = 0;
        long relativeNow = System.nanoTime() - replayZeroNanos;
        while (startIndex + 1 < frames.size()
                && wallNanos(frames.get(startIndex + 1).timeMs(), replay.clockRate(), offsetMs) <= relativeNow)
            startIndex++;
        if (startIndex > 0) {
            ReplayFrame current = frames.get(startIndex);
            ensureForeground(target.handle());
            if (!input.sendCursor(target.handle(), current.x(), current.y())
                    || !input.sendKeyTransitions(0, current.keys()))
                throw new IllegalStateException("SendInput was rejected by Windows");
            previousKeys = current.keys();
            startIndex++;
        }
        for (int index = startIndex; index < frames.size(); index++) {
            ReplayFrame frame = frames.get(index);
            long targetNanos = wallNanos(frame.timeMs(), replay.clockRate(), offsetMs);
            if (!waitUntil(target.handle(), replayZeroNanos + targetNanos)) return;
            ensureForeground(target.handle());
            if (!input.sendCursor(target.handle(), frame.x(), frame.y())
                    || !input.sendKeyTransitions(previousKeys, frame.keys()))
                throw new IllegalStateException("SendInput was rejected by Windows");
            previousKeys = frame.keys();
            if ((index & 127) == 0) notifyProgress(index, frames.size());
        }
    }

    private void playInterpolated(NativeWindows.TargetWindow target, ReplayData replay,
                                  int offsetMs, int outputHertz, long replayZeroNanos) {
        List<ReplayFrame> frames = replay.frames();
        List<ReplayFrame> keyTransitions = new ArrayList<>();
        int lastKeys = 0;
        for (ReplayFrame frame : frames) {
            if (frame.keys() != lastKeys) {
                keyTransitions.add(frame);
                lastKeys = frame.keys();
            }
        }

        long intervalNanos = 1_000_000_000L / outputHertz;
        long finalNanos = wallNanos(frames.getLast().timeMs(), replay.clockRate(), offsetMs);
        long tickNanos = wallNanos(0, replay.clockRate(), offsetMs);
        int transitionIndex = 0;
        int previousKeys = 0;
        int segment = 0;
        long lastProgressNanos = -1;

        long relativeNow = System.nanoTime() - replayZeroNanos;
        while (transitionIndex < keyTransitions.size()
                && wallNanos(keyTransitions.get(transitionIndex).timeMs(), replay.clockRate(), offsetMs) <= relativeNow) {
            previousKeys = keyTransitions.get(transitionIndex).keys();
            transitionIndex++;
        }
        if (relativeNow > tickNanos) {
            long elapsedTicks = Math.floorDiv(relativeNow - tickNanos, intervalNanos);
            tickNanos += Math.multiplyExact(elapsedTicks, intervalNanos);
            if (tickNanos < relativeNow) tickNanos += intervalNanos;
            double sourceTimeMs = (relativeNow / 1_000_000.0 - offsetMs) * replay.clockRate();
            while (segment + 1 < frames.size() && frames.get(segment + 1).timeMs() <= sourceTimeMs) segment++;
            ReplayFrame from = frames.get(segment);
            ReplayFrame to = frames.get(Math.min(segment + 1, frames.size() - 1));
            double fraction = to.timeMs() == from.timeMs() ? 0
                    : Math.clamp((sourceTimeMs - from.timeMs()) / (to.timeMs() - from.timeMs()), 0.0, 1.0);
            ensureForeground(target.handle());
            if (!input.sendCursor(target.handle(), from.x() + (to.x() - from.x()) * fraction,
                    from.y() + (to.y() - from.y()) * fraction)
                    || !input.sendKeyTransitions(0, previousKeys))
                throw new IllegalStateException("SendInput was rejected by Windows");
        }

        while (tickNanos <= finalNanos || transitionIndex < keyTransitions.size()) {
            long transitionNanos = transitionIndex < keyTransitions.size()
                    ? wallNanos(keyTransitions.get(transitionIndex).timeMs(), replay.clockRate(), offsetMs)
                    : Long.MAX_VALUE;
            long relativeCurrent = System.nanoTime() - replayZeroNanos;
            // Catch up to the current time, but stop at the next input transition so the
            // interpolated cursor reaches that point before its key/button state changes.
            tickNanos = currentCursorTick(tickNanos, intervalNanos,
                    Math.min(relativeCurrent, transitionNanos));
            boolean isTransition = transitionNanos < tickNanos;
            long eventNanos = isTransition ? transitionNanos : tickNanos;
            if (!waitUntil(target.handle(), replayZeroNanos + eventNanos)) return;
            ensureForeground(target.handle());

            if (isTransition) {
                ReplayFrame frame = keyTransitions.get(transitionIndex++);
                // The interpolated cursor stream already covers this timestamp. Re-sending the
                // transition frame here can snap the cursor backwards when the scheduler wakes late.
                if (!input.sendKeyTransitions(previousKeys, frame.keys()))
                    throw new IllegalStateException("SendInput was rejected by Windows");
                previousKeys = frame.keys();
            } else {
                double sourceTimeMs = (eventNanos / 1_000_000.0 - offsetMs) * replay.clockRate();
                while (segment + 1 < frames.size() && frames.get(segment + 1).timeMs() <= sourceTimeMs) segment++;
                ReplayFrame from = frames.get(segment);
                ReplayFrame to = frames.get(Math.min(segment + 1, frames.size() - 1));
                double fraction = to.timeMs() == from.timeMs() ? 0
                        : Math.clamp((sourceTimeMs - from.timeMs()) / (to.timeMs() - from.timeMs()), 0.0, 1.0);
                double x = from.x() + (to.x() - from.x()) * fraction;
                double y = from.y() + (to.y() - from.y()) * fraction;
                if (!input.sendCursor(target.handle(), x, y))
                    throw new IllegalStateException("SendInput was rejected by Windows");
                tickNanos += intervalNanos;
            }

            if (eventNanos - lastProgressNanos >= 100_000_000L) {
                lastProgressNanos = eventNanos;
                int progress = finalNanos <= 0 ? 1000
                        : Math.clamp(eventNanos * 1000 / finalNanos, 0, 1000);
                int value = progress;
                notifyOnUi(() -> listener.onProgress(value));
            }
        }
    }

    /** Drops stale interpolation ticks instead of sending a burst the game can only see as a jump. */
    static long currentCursorTick(long tickNanos, long intervalNanos, long relativeCurrentNanos) {
        if (intervalNanos <= 0) throw new IllegalArgumentException("Cursor interval must be positive");
        if (tickNanos >= relativeCurrentNanos - intervalNanos) return tickNanos;
        long elapsed = Math.floorDiv(relativeCurrentNanos - tickNanos, intervalNanos);
        return tickNanos + Math.multiplyExact(elapsed, intervalNanos);
    }

    static long nextPeriodicDeadline(long deadlineNanos, long intervalNanos, long nowNanos) {
        if (intervalNanos <= 0) throw new IllegalArgumentException("Interval must be positive");
        if (deadlineNanos == 0) return Math.addExact(nowNanos, intervalNanos);
        if (deadlineNanos > nowNanos) return deadlineNanos;
        long intervals = Math.floorDiv(nowNanos - deadlineNanos, intervalNanos) + 1;
        return Math.addExact(deadlineNanos, Math.multiplyExact(intervals, intervalNanos));
    }

    private boolean waitUntil(com.sun.jna.platform.win32.WinDef.HWND target, long deadlineNanos) {
        while (true) {
            if (waiter.isStopped()) return false;
            ensureForeground(target);
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 150_000) return true;
            if (waiter.waitForNanos(remaining)) return false;
        }
    }

    private static long wallNanos(long replayTimeMs, double clockRate, int offsetMs) {
        double wallMs = replayTimeMs / clockRate + offsetMs;
        if (wallMs >= Long.MAX_VALUE / 1_000_000.0) return Long.MAX_VALUE;
        if (wallMs <= Long.MIN_VALUE / 1_000_000.0) return Long.MIN_VALUE;
        return Math.round(wallMs * 1_000_000.0);
    }

    private static void ensureForeground(com.sun.jna.platform.win32.WinDef.HWND target) {
        if (!NativeWindows.isForeground(target)) throw new FocusLostException();
    }

    private void notifyProgress(int index, int count) {
        int progress = count == 0 ? 0 : index * 1000 / count;
        notifyOnUi(() -> listener.onProgress(progress));
    }

    private void finish(StopReason reason, String detail) {
        notifyOnUi(() -> listener.onStopped(reason, detail));
    }

    private static void notifyOnUi(Runnable action) {
        SwingUtilities.invokeLater(action);
    }

    private void joinFinishedWorker() {
        if (worker == null || worker.isAlive()) return;
        try {
            worker.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        worker = null;
    }

    @Override
    public void close() {
        stop();
        waiter.close();
    }

    private static final class FocusLostException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
