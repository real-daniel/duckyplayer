package io.duckyplayer;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

final class Mp3ReferenceDecoder {
    record DecodedAudio(float[] monoSamples, int sampleRate) {
    }

    private Mp3ReferenceDecoder() {
    }

    static DecodedAudio decode(Path path, double maximumSeconds) throws IOException {
        if (maximumSeconds <= 0) throw new IllegalArgumentException("maximumSeconds must be positive");
        FloatBuilder samples = new FloatBuilder(1 << 20);
        int sampleRate = 0;
        try (InputStream file = new BufferedInputStream(Files.newInputStream(path))) {
            Bitstream stream = new Bitstream(file);
            Decoder decoder = new Decoder();
            while (true) {
                Header header = stream.readFrame();
                if (header == null) break;
                try {
                    SampleBuffer decoded = (SampleBuffer) decoder.decodeFrame(header, stream);
                    if (sampleRate == 0) sampleRate = decoded.getSampleFrequency();
                    if (decoded.getSampleFrequency() != sampleRate)
                        throw new IOException("MP3 changes sample rate during playback");
                    int channels = decoded.getChannelCount();
                    short[] pcm = decoded.getBuffer();
                    int length = decoded.getBufferLength();
                    for (int index = 0; index + channels <= length; index += channels) {
                        double sum = 0;
                        for (int channel = 0; channel < channels; channel++) sum += pcm[index + channel] / 32768.0;
                        samples.add((float) (sum / channels));
                        if (samples.size >= maximumSeconds * sampleRate)
                            return new DecodedAudio(samples.toArray(), sampleRate);
                    }
                } finally {
                    stream.closeFrame();
                }
            }
        } catch (BitstreamException | DecoderException exception) {
            throw new IOException("Could not decode MP3 audio", exception);
        }
        if (sampleRate == 0 || samples.size == 0) throw new IOException("MP3 contains no audio samples");
        return new DecodedAudio(samples.toArray(), sampleRate);
    }

    private static final class FloatBuilder {
        private float[] values;
        private int size;

        FloatBuilder(int capacity) {
            values = new float[capacity];
        }

        void add(float value) {
            if (size == values.length) values = Arrays.copyOf(values, Math.multiplyExact(values.length, 2));
            values[size++] = value;
        }

        float[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
