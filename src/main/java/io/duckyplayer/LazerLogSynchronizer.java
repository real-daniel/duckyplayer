package io.duckyplayer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class LazerLogSynchronizer implements AutoCloseable {
    interface Listener {
        void onStatus(String status);
        void onSynchronized(SyncPoint point);
        void onError(String error);
    }

    record SyncPoint(double initialClockMs, long detectedNanos, Path logFile) {
        long replayZeroNanos(double clockRate) {
            double delayNanos = -initialClockMs / clockRate * 1_000_000.0;
            if (delayNanos >= Long.MAX_VALUE || delayNanos <= Long.MIN_VALUE)
                throw new IllegalArgumentException("Initial gameplay clock is outside the supported range");
            return detectedNanos + Math.round(delayNanos);
        }
    }

    private static final Pattern SEEK_PATTERN = Pattern.compile(
            "GameplayClockContainer seeking to (-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+))");
    private static final String PLAYER_ENTERED = "entered SoloPlayer#";
    private static final String PLAYER_EXITED = "exit from SoloPlayer#";
    private static final String CLOCK_STARTED = "GameplayClockContainer started via call to StartGameplayClock";
    private final Path logsDirectory;
    private final AtomicBoolean armed = new AtomicBoolean();
    private Thread worker;

    LazerLogSynchronizer() {
        this(defaultLogsDirectory());
    }

    LazerLogSynchronizer(Path logsDirectory) {
        this.logsDirectory = logsDirectory;
    }

    synchronized void arm(Listener listener) {
        cancel();
        armed.set(true);
        worker = Thread.ofPlatform().name("lazer-log-sync").priority(Thread.NORM_PRIORITY)
                .start(() -> watch(listener));
    }

    synchronized void cancel() {
        armed.set(false);
        Thread current = worker;
        if (current != null) {
            current.interrupt();
            if (current != Thread.currentThread()) {
                try {
                    current.join(2000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        worker = null;
    }

    boolean isArmed() {
        return armed.get();
    }

    private void watch(Listener listener) {
        LogSequence sequence = new LogSequence();
        Path activeLog = null;
        FileChannel channel = null;
        StringBuilder pending = new StringBuilder();
        long nextLogScan = 0;
        try {
            if (!Files.isDirectory(logsDirectory))
                throw new IOException("lazer logs directory not found: " + logsDirectory);
            listener.onStatus("Watching lazer runtime log. Start the matching map normally.");
            while (armed.get()) {
                long now = System.nanoTime();
                if (activeLog == null || now >= nextLogScan) {
                    nextLogScan = now + 250_000_000L;
                    Path newest = newestRuntimeLog();
                    if (newest != null && !newest.equals(activeLog)) {
                        if (channel != null) channel.close();
                        activeLog = newest;
                        channel = FileChannel.open(activeLog, StandardOpenOption.READ);
                        channel.position(channel.size());
                        pending.setLength(0);
                        sequence.reset();
                        listener.onStatus("Watching " + activeLog.getFileName() + ". Start the matching map normally.");
                    }
                }

                if (channel != null) {
                    if (channel.size() < channel.position()) {
                        channel.position(0);
                        pending.setLength(0);
                        sequence.reset();
                    }
                    readAvailable(channel, pending);
                    int newline;
                    while ((newline = pending.indexOf("\n")) >= 0) {
                        String line = pending.substring(0, newline).stripTrailing();
                        pending.delete(0, newline + 1);
                        SequenceEvent event = sequence.accept(line);
                        if (event == SequenceEvent.PLAYER_ENTERED)
                            listener.onStatus("Gameplay screen detected; waiting for lazer's initial clock seek.");
                        else if (event == SequenceEvent.SEEK_DETECTED)
                            listener.onStatus(String.format("Initial gameplay clock: %.3f ms; waiting for clock start.", sequence.seekMs));
                        else if (event == SequenceEvent.CLOCK_STARTED) {
                            long detected = System.nanoTime();
                            armed.set(false);
                            listener.onSynchronized(new SyncPoint(sequence.seekMs, detected, activeLog));
                            return;
                        }
                    }
                }
                Thread.sleep(2);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            if (armed.getAndSet(false)) listener.onError("Automatic sync failed: " + exception.getMessage());
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Nothing useful can be done during watcher shutdown.
                }
            }
        }
    }

    private static void readAvailable(FileChannel channel, StringBuilder pending) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (channel.position() < channel.size()) {
            buffer.clear();
            int read = channel.read(buffer);
            if (read <= 0) return;
            buffer.flip();
            pending.append(StandardCharsets.ISO_8859_1.decode(buffer));
        }
    }

    private Path newestRuntimeLog() throws IOException {
        try (Stream<Path> files = Files.list(logsDirectory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".runtime.log"))
                    .max(Comparator.comparingLong(this::lastModified))
                    .orElse(null);
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private static Path defaultLogsDirectory() {
        String override = System.getProperty("duckyplayer.logs");
        if (override != null && !override.isBlank()) return Path.of(override);
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) return Path.of("logs");
        return Path.of(appData, "osu", "logs");
    }

    @Override
    public void close() {
        cancel();
    }

    private enum SequenceEvent { NONE, PLAYER_ENTERED, SEEK_DETECTED, CLOCK_STARTED }

    static final class LogSequence {
        private boolean playerEntered;
        private Double seekMs;

        SequenceEvent accept(String line) {
            if (line.contains(PLAYER_ENTERED)) {
                playerEntered = true;
                seekMs = null;
                return SequenceEvent.PLAYER_ENTERED;
            }
            if (line.contains(PLAYER_EXITED)) {
                reset();
                return SequenceEvent.NONE;
            }
            if (!playerEntered) return SequenceEvent.NONE;
            Matcher matcher = SEEK_PATTERN.matcher(line);
            if (matcher.find()) {
                seekMs = Double.parseDouble(matcher.group(1));
                return SequenceEvent.SEEK_DETECTED;
            }
            if (seekMs != null && line.contains(CLOCK_STARTED)) return SequenceEvent.CLOCK_STARTED;
            return SequenceEvent.NONE;
        }

        void reset() {
            playerEntered = false;
            seekMs = null;
        }

        Optional<Double> completedSeek(String line) {
            return accept(line) == SequenceEvent.CLOCK_STARTED ? Optional.of(seekMs) : Optional.empty();
        }
    }
}
