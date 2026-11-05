package io.duckyplayer;

import java.util.ArrayList;
import java.util.List;

final class ReplayEdits {
    private static final int M1 = 1;
    private static final int M2 = 2;
    private static final int K1 = 4;
    private static final int K2 = 8;
    private static final int INPUT_LANES = M1 | M2 | K1 | K2;

    private ReplayEdits() {
    }

    static List<ReplayFrame> applyInputBlock(List<ReplayFrame> source, long startTime,
                                             long durationMs, int bit, boolean pressed) {
        if (source.isEmpty()) throw new IllegalArgumentException("Replay has no frames");
        if (durationMs <= 0) throw new IllegalArgumentException("Input block duration must be positive");
        long endTime = Math.addExact(startTime, durationMs);
        List<ReplayFrame> result = new ArrayList<>(source);
        upsertBoundary(result, frameAt(source, startTime));
        upsertBoundary(result, frameAt(source, endTime));
        for (int index = 0; index < result.size(); index++) {
            ReplayFrame frame = result.get(index);
            if (frame.timeMs() < startTime) continue;
            if (frame.timeMs() >= endTime) break;
            int keys = pressed ? frame.keys() | bit : frame.keys() & ~bit;
            result.set(index, new ReplayFrame(frame.timeMs(), frame.x(), frame.y(), keys));
        }
        return result;
    }

    static List<ReplayFrame> applyCenteredInputBlock(List<ReplayFrame> source, long centerTime,
                                                     long durationMs, int bit, boolean pressed) {
        long startTime = Math.subtractExact(centerTime, durationMs / 2);
        return applyInputBlock(source, startTime, durationMs, bit, pressed);
    }

    static List<ReplayFrame> swapInputLanes(List<ReplayFrame> source,
                                            long startTime, long endTime) {
        if (source.isEmpty()) throw new IllegalArgumentException("Replay has no frames");
        if (endTime <= startTime) throw new IllegalArgumentException("Selection must have a duration");
        List<ReplayFrame> result = new ArrayList<>(source);
        upsertBoundary(result, frameAt(source, startTime));
        upsertBoundary(result, frameAt(source, endTime));
        for (int index = 0; index < result.size(); index++) {
            ReplayFrame frame = result.get(index);
            if (frame.timeMs() < startTime) continue;
            if (frame.timeMs() >= endTime) break;
            int keys = frame.keys();
            int swapped = keys & ~INPUT_LANES;
            if ((keys & M1) != 0) swapped |= M2;
            if ((keys & M2) != 0) swapped |= M1;
            if ((keys & K1) != 0) swapped |= K2;
            if ((keys & K2) != 0) swapped |= K1;
            result.set(index, new ReplayFrame(frame.timeMs(), frame.x(), frame.y(), swapped));
        }
        return result;
    }

    private static ReplayFrame frameAt(List<ReplayFrame> frames, long time) {
        int low = 0;
        int high = frames.size() - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (frames.get(middle).timeMs() < time) low = middle + 1;
            else high = middle;
        }
        ReplayFrame after = frames.get(low);
        if (after.timeMs() == time) return after;
        if (low == 0) return new ReplayFrame(time, after.x(), after.y(), after.keys());
        ReplayFrame before = frames.get(low - 1);
        if (time > after.timeMs() && low == frames.size() - 1)
            return new ReplayFrame(time, after.x(), after.y(), after.keys());
        long span = after.timeMs() - before.timeMs();
        double rate = span <= 0 ? 0 : (time - before.timeMs()) / (double) span;
        return new ReplayFrame(time,
                before.x() + (after.x() - before.x()) * rate,
                before.y() + (after.y() - before.y()) * rate,
                before.keys());
    }

    private static void upsertBoundary(List<ReplayFrame> frames, ReplayFrame boundary) {
        int low = 0;
        int high = frames.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (frames.get(middle).timeMs() < boundary.timeMs()) low = middle + 1;
            else high = middle;
        }
        if (low < frames.size() && frames.get(low).timeMs() == boundary.timeMs()) return;
        frames.add(low, boundary);
    }
}
