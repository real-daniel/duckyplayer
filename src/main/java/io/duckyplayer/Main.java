package io.duckyplayer;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class Main {
    private Main() {
    }

    public static void main(String[] arguments) {
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            System.err.println("This application requires Windows.");
            System.exit(2);
        }
        if (arguments.length == 2 && arguments[0].equals("--validate")) {
            validate(arguments[1]);
            return;
        }
        if (arguments.length == 1 && arguments[0].equals("--audio-probe")) {
            audioProbe();
            return;
        }
        if (arguments.length == 2 && arguments[0].equals("--resolve-audio")) {
            resolveAudio(arguments[1]);
            return;
        }
        if (arguments.length != 0) {
            System.err.println("Unknown arguments: " + Arrays.toString(arguments));
            System.exit(2);
        }
        MainWindow.useSystemLookAndFeel();
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }

    private static void resolveAudio(String replayFile) {
        try {
            ReplayData replay = ReplayReader.load(Path.of(replayFile));
            BeatmapAudioResolver.BeatmapAudio audio = new BeatmapAudioResolver().resolve(replay.beatmapHash())
                    .orElseThrow(() -> new IOException("Matching beatmap was not found in the stable Songs directory"));
            Mp3ReferenceDecoder.DecodedAudio decoded = Mp3ReferenceDecoder.decode(audio.audio(), 2.0);
            System.out.printf("beatmap=%s%naudio=%s%nleadInMs=%d sampleRate=%d decodedSamples=%d%n",
                    audio.beatmap(), audio.audio(), audio.audioLeadInMs(), decoded.sampleRate(), decoded.monoSamples().length);
        } catch (IOException | RuntimeException exception) {
            System.err.println("Audio resolution failed: " + exception.getMessage());
            System.exit(6);
        }
    }

    private static void audioProbe() {
        LongAdder samples = new LongAdder();
        DoubleAdder squares = new DoubleAdder();
        int[] rate = new int[1];
        AtomicLong qpcDelta = new AtomicLong(Long.MIN_VALUE);
        try (WasapiLoopbackCapture capture = new WasapiLoopbackCapture()) {
            capture.start(chunk -> {
                rate[0] = chunk.sampleRate();
                qpcDelta.compareAndSet(Long.MIN_VALUE, System.nanoTime() - chunk.firstSampleNanos());
                samples.add(chunk.monoSamples().length);
                for (float sample : chunk.monoSamples()) squares.add(sample * sample);
            });
            Thread.sleep(2000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("Audio probe interrupted");
            System.exit(5);
        } catch (RuntimeException exception) {
            System.err.println("Audio probe failed: " + exception.getMessage());
            System.exit(5);
        }
        long count = samples.sum();
        double rms = count == 0 ? 0 : Math.sqrt(squares.sum() / count);
        System.out.printf("audio: sampleRate=%d samples=%d rms=%.8f receiveMinusQpcMs=%.3f%n",
                rate[0], count, rms, qpcDelta.get() / 1_000_000.0);
    }

    private static void validate(String file) {
        try {
            ReplayData replay = ReplayReader.load(Path.of(file));
            System.out.printf("valid: frames=%d player=%s durationMs=%d mods=%d%n",
                    replay.frames().size(), replay.playerName(), replay.frames().getLast().timeMs(), replay.mods());
        } catch (IOException | RuntimeException exception) {
            System.err.println("invalid: " + exception.getMessage());
            System.exit(4);
        }
    }
}
