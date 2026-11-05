package io.duckyplayer;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

record ReplayAnalysis(List<Result> results, List<SliderCheckpoint> sliderCheckpoints,
                      int count300, int count100, int count50, int misses,
                      int sliderBreaks, int maxCombo, double accuracy) {
    private static final double SLIDER_FOLLOW_AREA = 2.4;
    private static final long SLIDER_TAIL_LENIENCY_MS = 36;

    enum Judgement { HIT_300, HIT_100, HIT_50, MISS }

    record Result(BeatmapData.HitObject object, Judgement judgement, long errorMs,
                  boolean headHit) { }

    record SliderCheckpoint(BeatmapData.HitObject object, int spanIndex, long time,
                            double x, double y, boolean repeat, boolean hit) { }

    private record Press(long time, double x, double y, boolean used) {
        Press use() { return new Press(time, x, y, true); }
    }

    private record ComboEvent(long time, boolean hit) { }
    private record SliderEvent(int spanIndex, long time, boolean repeat) { }

    int comboAt(long time) {
        int combo = 0;
        for (ComboEvent event : comboEvents()) {
            if (event.time() > time) break;
            combo = event.hit() ? combo + 1 : 0;
        }
        return combo;
    }

    static ReplayAnalysis evaluate(BeatmapData beatmap, List<ReplayFrame> frames) {
        return evaluate(beatmap, frames, 0, 0);
    }

    static ReplayAnalysis evaluate(BeatmapData beatmap, List<ReplayFrame> frames, int mods) {
        return evaluate(beatmap, frames, mods, 0);
    }

    static ReplayAnalysis evaluate(BeatmapData beatmap, List<ReplayFrame> frames,
                                   int mods, int timingSafetyMarginMs) {
        if (timingSafetyMarginMs < 0)
            throw new IllegalArgumentException("Timing safety margin cannot be negative");
        List<Press> presses = findPresses(frames);
        List<Result> results = new ArrayList<>(beatmap.hitObjects().size());
        List<SliderCheckpoint> checkpoints = new ArrayList<>();
        double radius = beatmap.circleRadius();
        double window300 = Math.max(0,
                Math.floor(80 - 6 * beatmap.overallDifficulty()) - 0.5 - timingSafetyMarginMs);
        double window100 = Math.max(0,
                Math.floor(140 - 8 * beatmap.overallDifficulty()) - 0.5 - timingSafetyMarginMs);
        double window50 = Math.max(0,
                Math.floor(200 - 10 * beatmap.overallDifficulty()) - 0.5 - timingSafetyMarginMs);
        boolean relax = (mods & ReplayData.MOD_RELAX) != 0;
        boolean spunOut = (mods & ReplayData.MOD_SPUN_OUT) != 0;
        int firstAvailablePress = 0;
        int sliderBreaks = 0;
        long previousBlockingStart = Long.MIN_VALUE;

        for (BeatmapData.HitObject object : beatmap.hitObjects()) {
            if (object.type() == BeatmapData.ObjectType.SPINNER) {
                Judgement judgement = spinnerJudgement(object, frames,
                        beatmap.overallDifficulty(), mods, spunOut);
                results.add(new Result(object, judgement, 0, judgement != Judgement.MISS));
                continue;
            }

            while (firstAvailablePress < presses.size()
                    && presses.get(firstAvailablePress).time() < object.startTime() - window50)
                firstAvailablePress++;

            int best = -1;
            long absoluteError = 0;
            long signedError = 0;
            long earliestAllowed = object.startTime() > previousBlockingStart
                    ? previousBlockingStart : Long.MIN_VALUE;
            Long relaxHitTime = relax
                    ? findRelaxHit(object, frames, window50, radius, earliestAllowed) : null;
            if (relaxHitTime != null) {
                signedError = relaxHitTime - object.startTime();
                absoluteError = Math.abs(signedError);
            } else if (!relax) {
                for (int index = firstAvailablePress; index < presses.size(); index++) {
                    Press press = presses.get(index);
                    if (press.used()) continue;
                    if (press.time() < earliestAllowed) continue;
                    long error = Math.abs(press.time() - object.startTime());
                    if (press.time() > object.startTime() + window50) break;
                    if (error > window50) continue;
                    if (Math.hypot(press.x() - object.x(), press.y() - object.y()) > radius) continue;
                    best = index;
                    absoluteError = error;
                    signedError = press.time() - object.startTime();
                    break;
                }
            }

            boolean headHit = relax ? relaxHitTime != null : best >= 0;
            if (!relax && headHit) {
                presses.set(best, presses.get(best).use());
                firstAvailablePress = best + 1;
            }

            Judgement finalJudgement = timingJudgement(headHit, absoluteError,
                    window300, window100);
            if (object.type() == BeatmapData.ObjectType.SLIDER) {
                List<SliderCheckpoint> sliderCheckpoints = evaluateSlider(
                        object, frames, radius,
                        headHit ? object.startTime() + signedError
                                : object.startTime() + (long) Math.ceil(window50),
                        relax);
                checkpoints.addAll(sliderCheckpoints);
                int hitParts = headHit ? 1 : 0;
                for (SliderCheckpoint checkpoint : sliderCheckpoints) {
                    if (checkpoint.hit()) hitParts++;
                    else sliderBreaks++;
                }
                int totalParts = sliderCheckpoints.size() + 1;
                if (hitParts == totalParts) finalJudgement = Judgement.HIT_300;
                else if (hitParts == 0) finalJudgement = Judgement.MISS;
                else if (hitParts * 2 >= totalParts) finalJudgement = Judgement.HIT_100;
                else finalJudgement = Judgement.HIT_50;
            }
            results.add(new Result(object, finalJudgement, signedError, headHit));
            previousBlockingStart = object.startTime();
        }

        int count300 = 0;
        int count100 = 0;
        int count50 = 0;
        int misses = 0;
        for (Result result : results) {
            switch (result.judgement()) {
                case HIT_300 -> count300++;
                case HIT_100 -> count100++;
                case HIT_50 -> count50++;
                case MISS -> misses++;
            }
        }

        int total = results.size();
        double accuracy = total == 0 ? 0
                : (count300 * 300.0 + count100 * 100.0 + count50 * 50.0) / (total * 300.0);
        ReplayAnalysis partial = new ReplayAnalysis(List.copyOf(results), List.copyOf(checkpoints),
                count300, count100, count50, misses, sliderBreaks, 0, accuracy);
        int combo = 0;
        int maximum = 0;
        for (ComboEvent event : partial.comboEvents()) {
            combo = event.hit() ? combo + 1 : 0;
            maximum = Math.max(maximum, combo);
        }
        return new ReplayAnalysis(partial.results(), partial.sliderCheckpoints(), count300, count100,
                count50, misses, sliderBreaks, maximum, accuracy);
    }

    private static Judgement timingJudgement(boolean hit, long absoluteError,
                                              double window300, double window100) {
        if (!hit) return Judgement.MISS;
        if (absoluteError <= window300) return Judgement.HIT_300;
        if (absoluteError <= window100) return Judgement.HIT_100;
        return Judgement.HIT_50;
    }

    private static Long findRelaxHit(BeatmapData.HitObject object, List<ReplayFrame> frames,
                                     double window50, double radius, long earliestAllowed) {
        long earliest = Math.max(object.startTime() - 12, earliestAllowed);
        long latest = Math.round(object.startTime() + window50);
        List<Long> candidates = new ArrayList<>();
        candidates.add(earliest);
        candidates.add(object.startTime());
        for (ReplayFrame frame : frames) {
            if (frame.timeMs() >= earliest && frame.timeMs() <= latest)
                candidates.add(frame.timeMs());
        }
        candidates.sort(Long::compare);
        for (long time : candidates) {
            ReplayFrame frame = frameAt(frames, time);
            if (Math.hypot(frame.x() - object.x(), frame.y() - object.y()) <= radius)
                return time;
        }
        return null;
    }

    private static List<SliderCheckpoint> evaluateSlider(BeatmapData.HitObject slider,
                                                          List<ReplayFrame> frames,
                                                          double radius, long headJudgedTime,
                                                          boolean relax) {
        List<SliderEvent> events = sliderEvents(slider);
        List<SliderCheckpoint> checkpoints = new ArrayList<>(events.size());
        boolean tracking = false;
        int frameIndex = firstFrameAtOrAfter(frames, slider.startTime());

        for (SliderEvent event : events) {
            while (frameIndex < frames.size() && frames.get(frameIndex).timeMs() <= event.time()) {
                ReplayFrame frame = frames.get(frameIndex++);
                tracking = sliderTrackingAt(slider, frames, frame.timeMs(), radius,
                        tracking, relax);
            }
            tracking = sliderTrackingAt(slider, frames, event.time(), radius, tracking, relax);
            Point2D.Double point = sliderPositionAt(slider, event.time());
            checkpoints.add(new SliderCheckpoint(slider, event.spanIndex(), event.time(),
                    point.x, point.y, event.repeat(),
                    event.time() >= headJudgedTime && tracking));
        }
        return List.copyOf(checkpoints);
    }

    private static boolean sliderTrackingAt(BeatmapData.HitObject slider,
                                            List<ReplayFrame> frames, long time,
                                            double radius, boolean tracking,
                                            boolean relax) {
        ReplayFrame frame = frameAt(frames, time);
        boolean held = relax || (frame.keys() & 0x0f) != 0;
        if (!held) return false;
        Point2D.Double ball = sliderPositionAt(slider, time);
        double allowed = tracking ? radius * SLIDER_FOLLOW_AREA : radius;
        return Math.hypot(frame.x() - ball.x, frame.y() - ball.y) <= allowed;
    }

    private static List<SliderEvent> sliderEvents(BeatmapData.HitObject slider) {
        List<SliderEvent> events = new ArrayList<>();
        long duration = slider.endTime() - slider.startTime();
        int spans = Math.max(1, slider.repeatCount());
        double spanDuration = duration / (double) spans;
        double tickInterval = slider.tickIntervalMs();
        for (int span = 0; span < spans; span++) {
            if (Double.isFinite(tickInterval) && tickInterval > 0) {
                for (double elapsed = tickInterval; elapsed < spanDuration - 10; elapsed += tickInterval) {
                    double timeProgress = (span & 1) == 0
                            ? elapsed / spanDuration : 1 - elapsed / spanDuration;
                    long time = Math.round(slider.startTime() + span * spanDuration
                            + timeProgress * spanDuration);
                    events.add(new SliderEvent(span, time, false));
                }
            }
            if (span < spans - 1) {
                long time = Math.round(slider.startTime() + (span + 1) * spanDuration);
                events.add(new SliderEvent(span, time, true));
            }
        }
        events.add(new SliderEvent(spans - 1,
                Math.max(slider.startTime(), slider.endTime() - SLIDER_TAIL_LENIENCY_MS), false));
        events.sort(Comparator.comparingLong(SliderEvent::time));
        return events;
    }

    private static Point2D.Double sliderPositionAt(BeatmapData.HitObject slider, long time) {
        long duration = slider.endTime() - slider.startTime();
        if (duration <= 0 || slider.path().isEmpty())
            return new Point2D.Double(slider.x(), slider.y());
        int spans = Math.max(1, slider.repeatCount());
        double total = Math.max(0, Math.min(spans,
                (time - slider.startTime()) / (double) duration * spans));
        int span = Math.min(spans - 1, (int) Math.floor(total));
        double progress = total - span;
        if (time >= slider.endTime()) progress = 1;
        if ((span & 1) != 0) progress = 1 - progress;
        return pointAlongPath(slider.path(), progress);
    }

    private static Point2D.Double pointAlongPath(List<Point2D.Double> path, double progress) {
        if (path.size() <= 1) return path.isEmpty() ? new Point2D.Double() : path.getFirst();
        double length = 0;
        for (int index = 1; index < path.size(); index++)
            length += path.get(index - 1).distance(path.get(index));
        double target = Math.max(0, Math.min(1, progress)) * length;
        double travelled = 0;
        for (int index = 1; index < path.size(); index++) {
            Point2D.Double from = path.get(index - 1);
            Point2D.Double to = path.get(index);
            double segment = from.distance(to);
            if (segment > 0 && travelled + segment >= target) {
                double ratio = (target - travelled) / segment;
                return new Point2D.Double(from.x + (to.x - from.x) * ratio,
                        from.y + (to.y - from.y) * ratio);
            }
            travelled += segment;
        }
        return path.getLast();
    }

    private static Judgement spinnerJudgement(BeatmapData.HitObject spinner,
                                               List<ReplayFrame> frames, double od,
                                               int mods, boolean spunOut) {
        double durationSeconds = (spinner.endTime() - spinner.startTime()) / 1000.0;
        double requiredRpm = od < 5 ? 90 + 12 * od : 150 + 15 * (od - 5);
        int required = (int) (requiredRpm / 60 * durationSeconds + 0.0001);
        if (required == 0 || spunOut) return Judgement.HIT_300;
        boolean relax = (mods & ReplayData.MOD_RELAX) != 0;
        double progress = spinnerRotation(spinner, frames, clockRate(mods), relax) / 360 / required;
        if (progress >= 1) return Judgement.HIT_300;
        if (progress > 0.9) return Judgement.HIT_100;
        if (progress > 0.75) return Judgement.HIT_50;
        return Judgement.MISS;
    }

    private static double spinnerRotation(BeatmapData.HitObject spinner,
                                          List<ReplayFrame> frames, double clockRate,
                                          boolean automaticHold) {
        Double previousAngle = null;
        double accumulated = 0;
        double completionBase = 0;
        double currentMaximum = 0;
        int completed = 0;
        for (ReplayFrame frame : frames) {
            double angle = Math.toDegrees(Math.atan2(frame.y() - 192, frame.x() - 256));
            if (previousAngle != null && frame.timeMs() >= spinner.startTime()
                    && frame.timeMs() < spinner.endTime()
                    && (automaticHold || (frame.keys() & 0x0f) != 0)) {
                double delta = angle - previousAngle;
                if (delta > 180) delta -= 360;
                if (delta < -180) delta += 360;
                accumulated += delta * clockRate;
                double current = accumulated - completionBase;
                currentMaximum = Math.max(currentMaximum, Math.abs(current));
                while (currentMaximum >= 360) {
                    int direction = current < 0 ? -1 : 1;
                    completed++;
                    completionBase += direction * 360;
                    current = accumulated - completionBase;
                    currentMaximum = Math.abs(current);
                }
            }
            previousAngle = angle;
            if (frame.timeMs() >= spinner.endTime()) break;
        }
        return completed * 360.0 + currentMaximum;
    }

    private List<ComboEvent> comboEvents() {
        List<ComboEvent> events = new ArrayList<>(results.size() + sliderCheckpoints.size());
        for (Result result : results) {
            long time = result.object().type() == BeatmapData.ObjectType.SPINNER
                    ? result.object().endTime() : result.object().startTime();
            events.add(new ComboEvent(time, result.object().type() == BeatmapData.ObjectType.SPINNER
                    ? result.judgement() != Judgement.MISS : result.headHit()));
        }
        for (SliderCheckpoint checkpoint : sliderCheckpoints)
            events.add(new ComboEvent(checkpoint.time(), checkpoint.hit()));
        events.sort(Comparator.comparingLong(ComboEvent::time));
        return events;
    }

    private static List<Press> findPresses(List<ReplayFrame> frames) {
        List<Press> presses = new ArrayList<>();
        int previous = 0;
        for (ReplayFrame frame : frames) {
            int current = frame.keys();
            boolean firstWasDown = (previous & 5) != 0;
            boolean firstIsDown = (current & 5) != 0;
            boolean secondWasDown = (previous & 10) != 0;
            boolean secondIsDown = (current & 10) != 0;
            if (!firstWasDown && firstIsDown)
                presses.add(new Press(frame.timeMs(), frame.x(), frame.y(), false));
            if (!secondWasDown && secondIsDown)
                presses.add(new Press(frame.timeMs(), frame.x(), frame.y(), false));
            previous = current;
        }
        return presses;
    }

    private static ReplayFrame frameAt(List<ReplayFrame> frames, long time) {
        int low = 0;
        int high = frames.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (frames.get(middle).timeMs() <= time) low = middle + 1;
            else high = middle - 1;
        }
        int firstIndex = Math.max(0, high);
        ReplayFrame first = frames.get(firstIndex);
        if (firstIndex + 1 >= frames.size()) return first;
        ReplayFrame second = frames.get(firstIndex + 1);
        long span = second.timeMs() - first.timeMs();
        if (span <= 0 || time <= first.timeMs()) return first;
        double rate = Math.max(0, Math.min(1, (time - first.timeMs()) / (double) span));
        return new ReplayFrame(time, first.x() + (second.x() - first.x()) * rate,
                first.y() + (second.y() - first.y()) * rate, first.keys());
    }

    private static int firstFrameAtOrAfter(List<ReplayFrame> frames, long time) {
        int index = 0;
        while (index < frames.size() && frames.get(index).timeMs() < time) index++;
        return index;
    }

    private static double clockRate(int mods) {
        if ((mods & (ReplayData.MOD_DOUBLE_TIME | ReplayData.MOD_NIGHTCORE)) != 0) return 1.5;
        if ((mods & ReplayData.MOD_HALF_TIME) != 0) return 0.75;
        return 1;
    }
}
