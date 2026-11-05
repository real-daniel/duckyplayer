package io.duckyplayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class AudioStartSynchronizer implements AutoCloseable {
    interface Listener {
        void onStatus(String message);
        void onSynchronized(SyncPoint point);
        void onError(String message);
    }

    record SyncPoint(long audioZeroNanos, double envelopeCorrelation, double waveformCorrelation,
                     BeatmapAudioResolver.BeatmapAudio beatmapAudio) {
    }

    private static final int MATCH_RATE = 8000;
    private static final int TEMPLATE_MILLIS = 250;
    private static final int MAX_TEMPLATE_START_MILLIS = 1500;
    private static final int LIVE_WINDOW_SECONDS = 6;
    private static final int ENVELOPE_BLOCK = 40; // 5 ms at 8 kHz.
    private static final double ENVELOPE_THRESHOLD = 0.90;
    private static final double WAVEFORM_THRESHOLD = 0.72;

    private final BeatmapAudioResolver resolver;
    private final WasapiLoopbackCapture capture = new WasapiLoopbackCapture();
    private final AtomicBoolean armed = new AtomicBoolean();
    private Thread worker;

    AudioStartSynchronizer() {
        this(new BeatmapAudioResolver());
    }

    AudioStartSynchronizer(BeatmapAudioResolver resolver) {
        this.resolver = resolver;
    }

    synchronized void arm(ReplayData replay, Listener listener) {
        cancel();
        armed.set(true);
        worker = Thread.ofPlatform().name("audio-start-sync").priority(Thread.NORM_PRIORITY)
                .start(() -> run(replay, listener));
    }

    synchronized void cancel() {
        armed.set(false);
        capture.stop();
        Thread current = worker;
        if (current != null && current != Thread.currentThread()) {
            current.interrupt();
            try {
                current.join(2000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
    }

    boolean isArmed() {
        return armed.get();
    }

    private void run(ReplayData replay, Listener listener) {
        try {
            listener.onStatus("Resolving the matching beatmap audio…");
            BeatmapAudioResolver.BeatmapAudio audio = resolver.resolve(replay.beatmapHash())
                    .orElseThrow(() -> new IOException("Matching .osu file was not found in the stable Songs directory"));
            Mp3ReferenceDecoder.DecodedAudio decoded = Mp3ReferenceDecoder.decode(audio.audio(), 3.0);
            float[] reference = resample(decoded.monoSamples(), decoded.sampleRate(), MATCH_RATE);
            Template template = chooseTemplate(reference);
            ArrayBlockingQueue<WasapiLoopbackCapture.AudioChunk> chunks = new ArrayBlockingQueue<>(64);
            capture.start(chunk -> {
                if (!chunks.offer(chunk)) armed.set(false);
            });
            listener.onStatus("Audio sync ready. Start the map normally; listening for its opening audio.");

            LiveSamples live = new LiveSamples(MATCH_RATE, LIVE_WINDOW_SECONDS * MATCH_RATE);
            while (armed.get()) {
                WasapiLoopbackCapture.AudioChunk chunk = chunks.poll(100, TimeUnit.MILLISECONDS);
                RuntimeException captureFailure = capture.failure();
                if (captureFailure != null) throw captureFailure;
                if (chunk == null) continue;
                live.append(chunk);
                Match match = live.find(template);
                if (match != null) {
                    armed.set(false);
                    long templateOffsetNanos = template.startSample * 1_000_000_000L / MATCH_RATE;
                    long zero = match.liveStartNanos - templateOffsetNanos;
                    listener.onSynchronized(new SyncPoint(zero, match.envelopeCorrelation,
                            match.waveformCorrelation, audio));
                    return;
                }
            }
            if (!chunks.isEmpty()) listener.onError("Audio sync stopped because capture could not keep up.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            if (armed.getAndSet(false)) listener.onError("Audio sync failed: " + exception.getMessage());
        } finally {
            capture.stop();
        }
    }

    private static Template chooseTemplate(float[] reference) throws IOException {
        int length = TEMPLATE_MILLIS * MATCH_RATE / 1000;
        int maximumStart = Math.min(MAX_TEMPLATE_START_MILLIS * MATCH_RATE / 1000,
                reference.length - length);
        for (int start = 0; start <= maximumStart; start += ENVELOPE_BLOCK) {
            float[] waveform = Arrays.copyOfRange(reference, start, start + length);
            double rms = rms(waveform);
            float[] envelope = envelope(waveform);
            if (rms >= 0.01 && standardDeviation(envelope) >= 0.002)
                return new Template(start, waveform, envelope);
        }
        throw new IOException("The opening audio is too quiet or uniform for reliable synchronization");
    }

    static float[] resample(float[] source, int sourceRate, int targetRate) {
        if (sourceRate <= 0 || targetRate <= 0) throw new IllegalArgumentException("Invalid sample rate");
        int length = (int) ((long) source.length * targetRate / sourceRate);
        float[] result = new float[length];
        double ratio = sourceRate / (double) targetRate;
        for (int index = 0; index < length; index++) {
            double position = index * ratio;
            int from = Math.min((int) position, source.length - 1);
            int to = Math.min(from + 1, source.length - 1);
            double fraction = position - from;
            result[index] = (float) (source[from] + (source[to] - source[from]) * fraction);
        }
        return result;
    }

    private record Template(int startSample, float[] waveform, float[] envelope) {
    }

    private record Match(long liveStartNanos, double envelopeCorrelation, double waveformCorrelation) {
    }

    private static final class LiveSamples {
        private final int targetRate;
        private final int capacity;
        private final List<Float> samples = new ArrayList<>();
        private long firstSampleNanos;
        private long nextOutputNanos = Long.MIN_VALUE;
        private long lastMatchAt;

        LiveSamples(int targetRate, int capacity) {
            this.targetRate = targetRate;
            this.capacity = capacity;
        }

        void append(WasapiLoopbackCapture.AudioChunk chunk) {
            if (chunk.monoSamples().length == 0) return;
            long chunkEnd = chunk.firstSampleNanos()
                    + chunk.monoSamples().length * 1_000_000_000L / chunk.sampleRate();
            if (nextOutputNanos == Long.MIN_VALUE
                    || Math.abs(chunk.firstSampleNanos() - nextOutputNanos) > 5_000_000L) {
                samples.clear();
                firstSampleNanos = chunk.firstSampleNanos();
                nextOutputNanos = firstSampleNanos;
                lastMatchAt = 0;
            }
            long interval = 1_000_000_000L / targetRate;
            while (nextOutputNanos < chunkEnd) {
                double sourcePosition = (nextOutputNanos - chunk.firstSampleNanos())
                        * chunk.sampleRate() / 1_000_000_000.0;
                int from = Math.clamp((int) Math.floor(sourcePosition), 0, chunk.monoSamples().length - 1);
                int to = Math.min(from + 1, chunk.monoSamples().length - 1);
                double fraction = Math.clamp(sourcePosition - from, 0, 1);
                samples.add((float) (chunk.monoSamples()[from]
                        + (chunk.monoSamples()[to] - chunk.monoSamples()[from]) * fraction));
                nextOutputNanos += interval;
            }
            if (samples.size() > capacity) {
                int remove = samples.size() - capacity;
                samples.subList(0, remove).clear();
                firstSampleNanos += remove * interval;
                lastMatchAt = Math.max(0, lastMatchAt - remove);
            }
        }

        Match find(Template template) {
            if (samples.size() < template.waveform.length || samples.size() - lastMatchAt < targetRate / 20)
                return null;
            lastMatchAt = samples.size();
            float[] live = new float[samples.size()];
            for (int index = 0; index < live.length; index++) live[index] = samples.get(index);
            float[] liveEnvelope = envelope(live);
            int templateBlocks = template.envelope.length;
            double bestEnvelope = -1;
            int bestBlock = -1;
            for (int block = 0; block + templateBlocks <= liveEnvelope.length; block++) {
                double correlation = correlation(template.envelope, 0, liveEnvelope, block, templateBlocks);
                if (correlation > bestEnvelope) {
                    bestEnvelope = correlation;
                    bestBlock = block;
                }
            }
            if (bestEnvelope < ENVELOPE_THRESHOLD) return null;

            int coarse = bestBlock * ENVELOPE_BLOCK;
            int radius = targetRate / 100; // +/- 10 ms.
            double bestWaveform = -1;
            int bestSample = -1;
            for (int sample = Math.max(0, coarse - radius);
                 sample <= Math.min(live.length - template.waveform.length, coarse + radius); sample++) {
                double correlation = correlation(template.waveform, 0, live, sample, template.waveform.length);
                if (correlation > bestWaveform) {
                    bestWaveform = correlation;
                    bestSample = sample;
                }
            }
            if (bestWaveform < WAVEFORM_THRESHOLD) return null;
            long matchedNanos = firstSampleNanos + bestSample * 1_000_000_000L / targetRate;
            return new Match(matchedNanos, bestEnvelope, bestWaveform);
        }
    }

    private static float[] envelope(float[] samples) {
        int blocks = samples.length / ENVELOPE_BLOCK;
        float[] result = new float[blocks];
        for (int block = 0; block < blocks; block++) {
            double sum = 0;
            int start = block * ENVELOPE_BLOCK;
            for (int index = start; index < start + ENVELOPE_BLOCK; index++)
                sum += samples[index] * samples[index];
            result[block] = (float) Math.sqrt(sum / ENVELOPE_BLOCK);
        }
        return result;
    }

    private static double rms(float[] samples) {
        double sum = 0;
        for (float sample : samples) sum += sample * sample;
        return Math.sqrt(sum / samples.length);
    }

    private static double standardDeviation(float[] values) {
        double mean = 0;
        for (float value : values) mean += value;
        mean /= values.length;
        double variance = 0;
        for (float value : values) variance += (value - mean) * (value - mean);
        return Math.sqrt(variance / values.length);
    }

    private static double correlation(float[] first, int firstOffset, float[] second, int secondOffset, int length) {
        double firstMean = 0;
        double secondMean = 0;
        for (int index = 0; index < length; index++) {
            firstMean += first[firstOffset + index];
            secondMean += second[secondOffset + index];
        }
        firstMean /= length;
        secondMean /= length;
        double product = 0;
        double firstEnergy = 0;
        double secondEnergy = 0;
        for (int index = 0; index < length; index++) {
            double a = first[firstOffset + index] - firstMean;
            double b = second[secondOffset + index] - secondMean;
            product += a * b;
            firstEnergy += a * a;
            secondEnergy += b * b;
        }
        double denominator = Math.sqrt(firstEnergy * secondEnergy);
        return denominator == 0 ? -1 : product / denominator;
    }

    @Override
    public void close() {
        cancel();
    }
}
