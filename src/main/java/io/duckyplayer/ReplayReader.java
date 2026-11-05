package io.duckyplayer;

import org.tukaani.xz.LZMAInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ReplayReader {
    private static final int MAX_FILE_SIZE = 512 * 1024 * 1024;
    private static final int MAX_REPLAY_SIZE = 256 * 1024 * 1024;

    private ReplayReader() {
    }

    public static ReplayData load(Path path) throws IOException {
        return loadDocument(path).data();
    }

    static ReplayDocument loadDocument(Path path) throws IOException {
        long size = Files.size(path);
        if (size <= 0 || size > MAX_FILE_SIZE) throw new IOException("Replay file size is invalid");
        Cursor cursor = new Cursor(Files.readAllBytes(path));

        int mode = cursor.readUnsignedByte();
        int gameVersion = cursor.readInt();
        String beatmapHash = cursor.readString();
        String playerName = cursor.readString();
        cursor.readString(); // replay hash
        for (int index = 0; index < 6; index++) cursor.readShort();
        cursor.readInt();
        cursor.readShort();
        cursor.readUnsignedByte();
        int mods = cursor.readInt();
        cursor.readString();
        cursor.readLong();
        int headerLength = cursor.position();
        int compressedLength = cursor.readInt();
        if (compressedLength <= 0 || compressedLength > MAX_REPLAY_SIZE)
            throw new IOException("Compressed replay length is invalid");

        String replayText = decompress(cursor.readBytes(compressedLength));
        List<ReplayFrame> frames = parseFrames(replayText);
        if (mode != 0) throw new IOException("Only osu!standard replays are supported");
        if (frames.isEmpty()) throw new IOException("Replay contains no playable frames");
        ReplayData data = new ReplayData(mode, gameVersion, beatmapHash, playerName, mods, List.copyOf(frames));
        byte[] bytes = cursor.bytes();
        byte[] header = java.util.Arrays.copyOfRange(bytes, 0, headerLength);
        byte[] footer = java.util.Arrays.copyOfRange(bytes, cursor.position(), bytes.length);
        return new ReplayDocument(path.toAbsolutePath(), data, header, footer, terminalRecord(replayText));
    }

    private static String terminalRecord(String text) {
        for (String record : text.split(",")) {
            if (record.startsWith("-12345|")) return record;
        }
        return "-12345|0|0|0";
    }

    static List<ReplayFrame> parseFrames(String text) throws IOException {
        List<ReplayFrame> legacyFrames = new ArrayList<>();
        long timeline = 0;
        for (String record : text.split(",")) {
            if (record.isEmpty()) continue;
            String[] fields = record.split("\\|", -1);
            if (fields.length != 4) throw new IOException("Malformed replay frame");
            try {
                long delta = Long.parseLong(fields[0]);
                if (delta == -12345) continue;
                timeline = Math.addExact(timeline, delta);
                double x = Double.parseDouble(fields[1]);
                double y = Double.parseDouble(fields[2]);
                long unsignedKeys = Long.parseLong(fields[3]);
                if (unsignedKeys < 0 || unsignedKeys > 0xffff_ffffL)
                    throw new NumberFormatException("key mask outside uint32 range");
                if (x >= -1024 && x <= 1536 && y >= -1024 && y <= 1408)
                    legacyFrames.add(new ReplayFrame(timeline, x, y, (int) unsignedKeys));
            } catch (ArithmeticException | NumberFormatException exception) {
                throw new IOException("Invalid value in replay frame", exception);
            }
        }

        if (legacyFrames.size() >= 2 && legacyFrames.get(1).timeMs() < legacyFrames.get(0).timeMs()) {
            ReplayFrame first = legacyFrames.get(0);
            ReplayFrame second = legacyFrames.get(1);
            legacyFrames.set(0, new ReplayFrame(0, first.x(), first.y(), first.keys()));
            legacyFrames.set(1, new ReplayFrame(first.timeMs(), second.x(), second.y(), second.keys()));
        }
        if (legacyFrames.size() >= 3 && legacyFrames.get(0).timeMs() > legacyFrames.get(2).timeMs()) {
            long corrected = legacyFrames.get(2).timeMs();
            for (int index = 0; index < 2; index++) {
                ReplayFrame frame = legacyFrames.get(index);
                legacyFrames.set(index, new ReplayFrame(corrected, frame.x(), frame.y(), frame.keys()));
            }
        }
        if (legacyFrames.size() >= 2 && isStartupFrame(legacyFrames.get(1))) legacyFrames.remove(1);
        if (!legacyFrames.isEmpty() && isStartupFrame(legacyFrames.getFirst())) legacyFrames.removeFirst();

        List<ReplayFrame> result = new ArrayList<>(legacyFrames.size());
        for (ReplayFrame frame : legacyFrames) {
            if (!result.isEmpty() && frame.timeMs() < result.getLast().timeMs()) continue;
            result.add(frame);
        }
        return result;
    }

    private static boolean isStartupFrame(ReplayFrame frame) {
        return frame.x() == 256.0 && frame.y() == -500.0;
    }

    private static String decompress(byte[] compressed) throws IOException {
        try (LZMAInputStream input = new LZMAInputStream(new ByteArrayInputStream(compressed), 256 * 1024);
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(compressed.length * 8, 16 * 1024 * 1024))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = input.read(buffer)) >= 0; ) {
                total += read;
                if (total > MAX_REPLAY_SIZE) throw new IOException("Replay expands beyond the safety limit");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class Cursor {
        private final ByteBuffer buffer;

        private Cursor(byte[] bytes) {
            buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }

        private int readUnsignedByte() throws IOException {
            require(1);
            return Byte.toUnsignedInt(buffer.get());
        }

        private short readShort() throws IOException {
            require(Short.BYTES);
            return buffer.getShort();
        }

        private int readInt() throws IOException {
            require(Integer.BYTES);
            return buffer.getInt();
        }

        private long readLong() throws IOException {
            require(Long.BYTES);
            return buffer.getLong();
        }

        private String readString() throws IOException {
            int marker = readUnsignedByte();
            if (marker == 0) return "";
            if (marker != 0x0b) throw new IOException("Invalid replay string marker");
            long length = 0;
            int shift = 0;
            while (true) {
                int value = readUnsignedByte();
                length |= (long) (value & 0x7f) << shift;
                if ((value & 0x80) == 0) break;
                shift += 7;
                if (shift >= 64) throw new IOException("Invalid replay string length");
            }
            if (length > buffer.remaining() || length > Integer.MAX_VALUE)
                throw new IOException("Replay string exceeds file size");
            return new String(readBytes((int) length), StandardCharsets.UTF_8);
        }

        private byte[] readBytes(int length) throws IOException {
            require(length);
            byte[] result = new byte[length];
            buffer.get(result);
            return result;
        }

        private void require(int length) throws IOException {
            if (length < 0 || buffer.remaining() < length) throw new IOException("Replay file is truncated");
        }

        private int position() {
            return buffer.position();
        }

        private byte[] bytes() {
            return buffer.array();
        }
    }
}
