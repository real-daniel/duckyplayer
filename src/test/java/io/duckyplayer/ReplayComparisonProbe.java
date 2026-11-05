package io.duckyplayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

/** Development-only probe for comparing a source replay with a replay recorded by lazer. */
public final class ReplayComparisonProbe {
    private ReplayComparisonProbe() {
    }

    private record Event(long timeMs, int mask, double x, double y) {
    }

    private record Match(Event source, Event recorded) {
        long deltaMs() {
            return recorded.timeMs - source.timeMs;
        }
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("expected source and recorded replay paths");
        ReplayData source = ReplayReader.load(Path.of(arguments[0]));
        ReplayData recorded = ReplayReader.load(Path.of(arguments[1]));
        System.out.printf("source score %s; recorded score %s%n", header(Path.of(arguments[0])), header(Path.of(arguments[1])));
        List<Event> a = transitions(source);
        List<Event> b = transitions(recorded);
        System.out.printf("source transitions=%d recorded transitions=%d hashesEqual=%s mods=%d/%d%n",
                a.size(), b.size(), source.beatmapHash().equals(recorded.beatmapHash()), source.mods(), recorded.mods());
        System.out.printf("source masks=%s recorded masks=%s%n", histogram(a), histogram(b));
        List<Match> matches = align(a, b);
        List<Long> deltas = new ArrayList<>();
        for (Match match : matches) deltas.add(match.deltaMs());
        List<Double> distances = new ArrayList<>();
        double dx = 0, dy = 0;
        for (Match match : matches) {
            double x = match.recorded().x() - match.source().x();
            double y = match.recorded().y() - match.source().y();
            dx += x; dy += y;
            distances.add(Math.hypot(x, y));
        }
        distances.sort(Double::compare);
        if (!distances.isEmpty())
            System.out.printf("cursor at aligned transitions: mean dx=%.2f dy=%.2f; distance median=%.2f p95=%.2f p99=%.2f max=%.2f px%n",
                    dx / matches.size(), dy / matches.size(), percentileDouble(distances, .5),
                    percentileDouble(distances, .95), percentileDouble(distances, .99), distances.getLast());
        for (int band = 0; band < 8; band++) {
            int from = band * matches.size() / 8, to = (band + 1) * matches.size() / 8;
            double sumX = 0, sumY = 0;
            for (int i = from; i < to; i++) {
                sumX += matches.get(i).recorded().x() - matches.get(i).source().x();
                sumY += matches.get(i).recorded().y() - matches.get(i).source().y();
            }
            System.out.printf("cursor band %d mean dx=%.2f dy=%.2f%n", band + 1, sumX / (to - from), sumY / (to - from));
        }
        deltas.sort(Long::compare);
        System.out.printf("aligned transitions=%d source-only=%d recorded-only=%d%n",
                matches.size(), a.size() - matches.size(), b.size() - matches.size());
        var matchedRecorded = new java.util.IdentityHashMap<Event, Boolean>();
        for (Match match : matches) matchedRecorded.put(match.recorded(), Boolean.TRUE);
        for (Event event : b)
            if (!matchedRecorded.containsKey(event))
                System.out.printf("recorded-only transition: time=%d ms mask=%d%n", event.timeMs(), event.mask());
        if (!deltas.isEmpty()) {
            long outside8 = deltas.stream().filter(delta -> Math.abs(delta - 4) > 8).count();
            System.out.printf("aligned transitions more than 8 ms from +4 ms: %d%n", outside8);
            System.out.printf("delta ms: min=%d p05=%d p25=%d median=%d p75=%d p95=%d max=%d mean=%.3f%n",
                    deltas.getFirst(), percentile(deltas, .05), percentile(deltas, .25), percentile(deltas, .5),
                    percentile(deltas, .75), percentile(deltas, .95), deltas.getLast(),
                    deltas.stream().mapToLong(Long::longValue).average().orElseThrow());
            int bands = 8;
            for (int band = 0; band < bands; band++) {
                List<Long> slice = new ArrayList<>();
                int from = band * matches.size() / bands;
                int to = (band + 1) * matches.size() / bands;
                for (int index = from; index < to; index++) slice.add(matches.get(index).deltaMs());
                slice.sort(Long::compare);
                if (!slice.isEmpty())
                    System.out.printf("band %d median=%d n=%d sourceTime=%d..%d%n", band + 1,
                            percentile(slice, .5), slice.size(), matches.get(from).source.timeMs,
                            matches.get(to - 1).source.timeMs);
            }
        }
    }

    private static List<Match> align(List<Event> source, List<Event> recorded) {
        int rows = source.size() + 1;
        int columns = recorded.size() + 1;
        byte[] directions = new byte[Math.multiplyExact(rows, columns)];
        int[] previous = new int[columns];
        int[] current = new int[columns];
        for (int column = 1; column < columns; column++) {
            previous[column] = column;
            directions[column] = 2;
        }
        for (int row = 1; row < rows; row++) {
            current[0] = row;
            directions[row * columns] = 1;
            for (int column = 1; column < columns; column++) {
                boolean same = source.get(row - 1).mask == recorded.get(column - 1).mask;
                int diagonal = previous[column - 1] + (same ? 0 : 3);
                int up = previous[column] + 1;
                int left = current[column - 1] + 1;
                int index = row * columns + column;
                if (same && diagonal <= up && diagonal <= left) {
                    current[column] = diagonal;
                    directions[index] = 0;
                } else if (up <= left && up <= diagonal) {
                    current[column] = up;
                    directions[index] = 1;
                } else if (left <= diagonal) {
                    current[column] = left;
                    directions[index] = 2;
                } else {
                    current[column] = diagonal;
                    directions[index] = 0;
                }
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }

        List<Match> reversed = new ArrayList<>();
        int row = source.size();
        int column = recorded.size();
        while (row > 0 || column > 0) {
            byte direction = directions[row * columns + column];
            if (direction == 0) {
                Event a = source.get(--row);
                Event b = recorded.get(--column);
                if (a.mask == b.mask) reversed.add(new Match(a, b));
            } else if (direction == 1) {
                row--;
            } else {
                column--;
            }
        }
        return reversed.reversed();
    }

    private static long percentile(List<Long> sorted, double fraction) {
        return sorted.get((int) Math.round((sorted.size() - 1) * fraction));
    }

    private static double percentileDouble(List<Double> sorted, double fraction) {
        return sorted.get((int)Math.round((sorted.size() - 1) * fraction));
    }

    private static String header(Path path) throws Exception {
        ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
        data.get(); data.getInt();
        for (int i = 0; i < 3; i++) skipString(data);
        int n300 = Short.toUnsignedInt(data.getShort());
        int n100 = Short.toUnsignedInt(data.getShort());
        int n50 = Short.toUnsignedInt(data.getShort());
        data.getShort(); data.getShort();
        int misses = Short.toUnsignedInt(data.getShort());
        return "300=" + n300 + " 100=" + n100 + " 50=" + n50 + " miss=" + misses;
    }

    private static void skipString(ByteBuffer data) {
        int marker = Byte.toUnsignedInt(data.get());
        if (marker == 0) return;
        if (marker != 0x0b) throw new IllegalArgumentException("Invalid replay string");
        int size = 0, shift = 0, next;
        do { next = Byte.toUnsignedInt(data.get()); size |= (next & 127) << shift; shift += 7; }
        while ((next & 128) != 0);
        data.position(data.position() + size);
    }

    private static String histogram(List<Event> events) {
        int[] counts = new int[16];
        for (Event event : events) counts[event.mask & 15]++;
        StringBuilder result = new StringBuilder();
        for (int mask = 0; mask < counts.length; mask++) {
            if (counts[mask] > 0) {
                if (!result.isEmpty()) result.append(' ');
                result.append(mask).append(':').append(counts[mask]);
            }
        }
        return result.toString();
    }

    private static List<Event> transitions(ReplayData replay) {
        List<Event> result = new ArrayList<>();
        int previous = 0;
        for (ReplayFrame frame : replay.frames()) {
            int current = outputMask(frame.keys());
            if (current != previous) {
                result.add(new Event(frame.timeMs(), current, frame.x(), frame.y()));
                previous = current;
            }
        }
        return result;
    }

    private static int outputMask(int replayMask) {
        int result = 0;
        // Stable and lazer encode the same physical channels differently in exported replays.
        if ((replayMask & (1 | 4)) != 0) result |= 1;
        if ((replayMask & (2 | 8)) != 0) result |= 2;
        return result;
    }
}
