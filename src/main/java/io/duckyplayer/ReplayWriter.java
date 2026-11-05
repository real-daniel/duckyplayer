package io.duckyplayer;

import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class ReplayWriter {
    private static final int RESULT_FIELD_COUNT = 6;

    private ReplayWriter() {
    }

    static void save(ReplayDocument document, List<ReplayFrame> frames, Path destination) throws IOException {
        save(document, frames, null, destination);
    }

    static void save(ReplayDocument document, List<ReplayFrame> frames,
                     ReplayAnalysis analysis, Path destination) throws IOException {
        validate(frames);
        byte[] replayBytes = encodeFrames(frames, document.terminalRecord()).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options();
        try (LZMAOutputStream output = new LZMAOutputStream(compressed, options, replayBytes.length)) {
            output.write(replayBytes);
        }

        byte[] payload = compressed.toByteArray();
        byte[] header = analysis == null ? document.header() : headerWithEstimatedResults(document.header(), analysis);
        ByteArrayOutputStream file = new ByteArrayOutputStream(
                header.length + Integer.BYTES + payload.length + document.footer().length);
        file.write(header);
        file.write(ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(payload.length).array());
        file.write(payload);
        file.write(document.footer());
        Files.write(destination, file.toByteArray());
    }

    private static byte[] headerWithEstimatedResults(byte[] source, ReplayAnalysis analysis) throws IOException {
        byte[] header = source.clone();
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        requireRemaining(buffer, Byte.BYTES + Integer.BYTES);
        buffer.position(Byte.BYTES + Integer.BYTES);
        skipString(buffer);
        skipString(buffer);
        skipString(buffer);

        int[] results = {
                analysis.count300(), analysis.count100(), analysis.count50(),
                0, 0, analysis.misses()
        };
        requireRemaining(buffer, RESULT_FIELD_COUNT * Short.BYTES + Integer.BYTES
                + Short.BYTES + Byte.BYTES);
        for (int result : results) {
            if (result < 0 || result > 0xffff)
                throw new IOException("Estimated replay result exceeds the .osr header limit");
            buffer.putShort((short) result);
        }

        buffer.position(buffer.position() + Integer.BYTES); // Score cannot be reconstructed from replay frames alone.
        if (analysis.maxCombo() < 0 || analysis.maxCombo() > 0xffff)
            throw new IOException("Estimated maximum combo exceeds the .osr header limit");
        buffer.putShort((short) analysis.maxCombo());
        buffer.put((byte) (analysis.misses() == 0 && analysis.count100() == 0
                && analysis.count50() == 0 ? 1 : 0));
        return header;
    }

    private static void skipString(ByteBuffer buffer) throws IOException {
        requireRemaining(buffer, Byte.BYTES);
        int marker = Byte.toUnsignedInt(buffer.get());
        if (marker == 0) return;
        if (marker != 0x0b) throw new IOException("Invalid replay string marker in header");
        long length = 0;
        int shift = 0;
        while (true) {
            requireRemaining(buffer, Byte.BYTES);
            int value = Byte.toUnsignedInt(buffer.get());
            length |= (long) (value & 0x7f) << shift;
            if ((value & 0x80) == 0) break;
            shift += 7;
            if (shift >= 64) throw new IOException("Invalid replay string length in header");
        }
        if (length > Integer.MAX_VALUE) throw new IOException("Replay string is too large");
        requireRemaining(buffer, (int) length);
        buffer.position(buffer.position() + (int) length);
    }

    private static void requireRemaining(ByteBuffer buffer, int length) throws IOException {
        if (length < 0 || buffer.remaining() < length) throw new IOException("Replay header is truncated");
    }

    static void validate(List<ReplayFrame> frames) throws IOException {
        if (frames.isEmpty()) throw new IOException("A replay must contain at least one frame");
        long previous = Long.MIN_VALUE;
        for (int index = 0; index < frames.size(); index++) {
            ReplayFrame frame = frames.get(index);
            if (frame.timeMs() < previous)
                throw new IOException("Frame " + (index + 1) + " occurs before the previous frame");
            if (index > 0) {
                try {
                    Math.subtractExact(frame.timeMs(), previous);
                } catch (ArithmeticException exception) {
                    throw new IOException("Frame " + (index + 1) + " has an unrepresentable time delta", exception);
                }
            }
            if (!Double.isFinite(frame.x()) || !Double.isFinite(frame.y()))
                throw new IOException("Frame " + (index + 1) + " has a non-finite coordinate");
            if (frame.x() < -1024 || frame.x() > 1536 || frame.y() < -1024 || frame.y() > 1408)
                throw new IOException("Frame " + (index + 1) + " is outside the supported coordinate range");
            previous = frame.timeMs();
        }
    }

    static String encodeFrames(List<ReplayFrame> frames, String terminalRecord) {
        StringBuilder text = new StringBuilder(frames.size() * 24);
        long previous = 0;
        for (ReplayFrame frame : frames) {
            long delta = Math.subtractExact(frame.timeMs(), previous);
            text.append(delta).append('|')
                    .append(Double.toString(frame.x())).append('|')
                    .append(Double.toString(frame.y())).append('|')
                    .append(Integer.toUnsignedString(frame.keys())).append(',');
            previous = frame.timeMs();
        }
        String terminal = terminalRecord == null || terminalRecord.isBlank()
                ? "-12345|0|0|0" : terminalRecord;
        return text.append(terminal).append(',').toString();
    }
}
