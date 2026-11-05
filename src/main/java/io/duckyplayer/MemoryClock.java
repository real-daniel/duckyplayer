package io.duckyplayer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Request/response bridge: no queued historical samples are used for playback. */
final class MemoryClock implements AutoCloseable {
    record Sample(long identity, double timeMs, boolean paused, long receivedNanos, long readNanos) { }
    private final Process process;
    private final BufferedWriter commands;
    // A linked queue allocates a node for every ~1 kHz clock reply. The fixed queue keeps
    // request/response hand-off allocation-free apart from the line created by BufferedReader.
    private final ArrayBlockingQueue<String> replies = new ArrayBlockingQueue<>(4);
    private volatile boolean closed;

    MemoryClock(long pid) throws IOException {
        Path base;
        try { base = Path.of(MemoryClock.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath(); }
        catch (java.net.URISyntaxException e) { throw new IOException(e); }
        if (!Files.isDirectory(base)) base = base.getParent();
        Path helper = base.resolve("clock-probe/ClockProbe.exe");
        if (!Files.isRegularFile(helper)) helper = Path.of("build/jar/clock-probe/ClockProbe.exe").toAbsolutePath();
        if (!Files.isRegularFile(helper)) throw new IOException("Clock helper not found. Rebuild the application.");
        process = new ProcessBuilder(helper.toString(), "--serve-active", Long.toString(pid))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofPlatform().daemon().name("clock-helper-output").start(() -> {
            try (var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = output.readLine()) != null && !closed) {
                    if (!replies.offer(line)) break;
                }
            } catch (IOException ignored) { /* Closing the owned helper interrupts pipe reads. */ }
            finally { replies.offer("ERR\tClock helper exited."); }
        });
    }

    void awaitReady() throws IOException {
        String reply = receive(30_000);
        if (!reply.equals("READY")) throw new IOException(reply);
    }

    Sample sample() throws IOException {
        long start = System.nanoTime();
        commands.write("SAMPLE\n"); commands.flush();
        String reply = receive(250);
        long end = System.nanoTime();
        return parse(reply, end, end - start);
    }

    static Sample parse(String line, long received, long duration) throws IOException {
        int first = line.indexOf('\t');
        int second = first < 0 ? -1 : line.indexOf('\t', first + 1);
        int third = second < 0 ? -1 : line.indexOf('\t', second + 1);
        if (first != 2 || second <= first + 1 || third <= second + 1
                || third + 2 != line.length() || line.charAt(0) != 'O' || line.charAt(1) != 'K')
            throw new IOException(line);
        try {
            long identity = Long.parseUnsignedLong(line, first + 1, second, 10);
            double time = parseDouble(line, second + 1, third);
            char paused = line.charAt(third + 1);
            if (!Double.isFinite(time) || (paused != '0' && paused != '1'))
                throw new IllegalArgumentException("Invalid time or pause flag");
            return new Sample(identity, time, paused == '1', received, duration);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid clock reply", e); }
    }

    /** Parses the finite decimal/exponent form emitted by .NET's round-trip formatter. */
    private static double parseDouble(CharSequence value, int start, int end) {
        if (start >= end) throw new NumberFormatException();
        int index = start;
        boolean negative = value.charAt(index) == '-';
        if (negative || value.charAt(index) == '+') index++;
        if (index >= end) throw new NumberFormatException();
        double result = 0;
        int digits = 0;
        while (index < end) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') break;
            result = result * 10 + character - '0';
            index++; digits++;
        }
        if (index < end && value.charAt(index) == '.') {
            index++;
            double scale = .1;
            while (index < end) {
                char character = value.charAt(index);
                if (character < '0' || character > '9') break;
                result += (character - '0') * scale;
                scale *= .1;
                index++; digits++;
            }
        }
        if (digits == 0) throw new NumberFormatException();
        if (index < end && (value.charAt(index) == 'e' || value.charAt(index) == 'E')) {
            index++;
            boolean exponentNegative = index < end && value.charAt(index) == '-';
            if (exponentNegative || (index < end && value.charAt(index) == '+')) index++;
            int exponent = 0, exponentDigits = 0;
            while (index < end) {
                char character = value.charAt(index++);
                if (character < '0' || character > '9') throw new NumberFormatException();
                exponent = Math.addExact(Math.multiplyExact(exponent, 10), character - '0');
                exponentDigits++;
            }
            if (exponentDigits == 0) throw new NumberFormatException();
            result *= Math.pow(10, exponentNegative ? -exponent : exponent);
        }
        if (index != end) throw new NumberFormatException();
        return negative ? -result : result;
    }

    static boolean isAdvancing(Sample previous, Sample current) {
        return previous != null && current.identity() != 0 && previous.identity() == current.identity()
                && !previous.paused() && !current.paused() && current.timeMs() > previous.timeMs();
    }

    static boolean isFreshStart(boolean sawZeroOrLeadIn, Sample sample) {
        return sawZeroOrLeadIn && sample.identity() != 0 && !sample.paused() && sample.timeMs() <= 100;
    }

    private String receive(long timeoutMs) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (!closed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Clock helper timed out.");
                String reply = replies.poll(Math.min(remaining, 50_000_000L), TimeUnit.NANOSECONDS);
                if (reply != null) return reply;
            }
            throw new IOException("Clock connection stopped.");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }

    @Override public void close() {
        closed = true;
        process.destroyForcibly(); // Only the helper started by this instance, never lazer.
    }
}
