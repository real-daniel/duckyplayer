package io.duckyplayer;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

record BeatmapData(double circleSize, double approachRate, double overallDifficulty,
                   List<HitObject> hitObjects, int formatVersion, double stackLeniency) {
    private static final double PLAYFIELD_HEIGHT = 384.0;
    private static final double STACK_DISTANCE = 3.0;
    private static final double STACK_OFFSET = -6.4;
    enum ObjectType { CIRCLE, SLIDER, SPINNER }

    record HitObject(ObjectType type, double x, double y, long startTime, long endTime,
                     List<Point2D.Double> path, int repeatCount, int stackHeight,
                     double tickIntervalMs) {
        HitObject(ObjectType type, double x, double y, long startTime, long endTime,
                  List<Point2D.Double> path, int repeatCount) {
            this(type, x, y, startTime, endTime, path, repeatCount, 0,
                    Double.POSITIVE_INFINITY);
        }

        HitObject(ObjectType type, double x, double y, long startTime, long endTime,
                  List<Point2D.Double> path, int repeatCount, int stackHeight) {
            this(type, x, y, startTime, endTime, path, repeatCount, stackHeight,
                    Double.POSITIVE_INFINITY);
        }
    }

    BeatmapData(double circleSize, double approachRate, double overallDifficulty,
                List<HitObject> hitObjects) {
        this(circleSize, approachRate, overallDifficulty, hitObjects, 14, 0.7);
    }

    private record TimingPoint(long time, double beatLength, boolean uninherited) {
    }

    static BeatmapData load(Path path) throws IOException {
        return parse(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    static BeatmapData load(byte[] bytes) throws IOException {
        return parse(new String(bytes, StandardCharsets.UTF_8).lines().toList());
    }

    private static BeatmapData parse(List<String> lines) throws IOException {
        double circleSize = 5;
        double approachRate = 5;
        double overallDifficulty = 5;
        double sliderMultiplier = 1.4;
        double sliderTickRate = 1;
        double stackLeniency = 0.7;
        int formatVersion = 14;
        boolean hasApproachRate = false;
        String section = "";
        List<TimingPoint> timingPoints = new ArrayList<>();
        List<String> objectLines = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            int comment = line.indexOf("//");
            if (comment > 0) line = line.substring(0, comment).stripTrailing();
            if (line.startsWith("osu file format v")) {
                try {
                    formatVersion = Integer.parseInt(line.substring("osu file format v".length()));
                } catch (NumberFormatException ignored) {
                    // Keep the current format default for a malformed version declaration.
                }
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line;
                continue;
            }
            if ("[General]".equals(section)) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                if ("StackLeniency".equals(line.substring(0, colon).strip())) {
                    try {
                        stackLeniency = Double.parseDouble(line.substring(colon + 1).strip());
                    } catch (NumberFormatException ignored) {
                        // Keep the format default for an invalid optional value.
                    }
                }
            } else if ("[Difficulty]".equals(section)) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String name = line.substring(0, colon).strip();
                String value = line.substring(colon + 1).strip();
                try {
                    if ("CircleSize".equals(name)) circleSize = Double.parseDouble(value);
                    else if ("ApproachRate".equals(name)) {
                        approachRate = Double.parseDouble(value);
                        hasApproachRate = true;
                    } else if ("OverallDifficulty".equals(name)) {
                        overallDifficulty = Double.parseDouble(value);
                        if (!hasApproachRate) approachRate = overallDifficulty;
                    }
                    else if ("SliderMultiplier".equals(name)) sliderMultiplier = Double.parseDouble(value);
                    else if ("SliderTickRate".equals(name)) sliderTickRate = Double.parseDouble(value);
                } catch (NumberFormatException ignored) {
                    // Keep format defaults for an invalid optional difficulty value.
                }
            } else if ("[TimingPoints]".equals(section)) {
                String[] fields = line.split(",");
                if (fields.length < 2) continue;
                try {
                    long time = Math.round(Double.parseDouble(fields[0]))
                            + (formatVersion < 5 ? 24 : 0);
                    double beatLength = Double.parseDouble(fields[1]);
                    boolean uninherited = fields.length < 7 || !"0".equals(fields[6].strip());
                    if (Double.isFinite(beatLength) && beatLength != 0)
                        timingPoints.add(new TimingPoint(time, beatLength, uninherited));
                } catch (NumberFormatException ignored) {
                    // Ignore one malformed timing point without losing the map.
                }
            } else if ("[HitObjects]".equals(section)) {
                objectLines.add(line);
            }
        }
        circleSize = clamp(circleSize, 0, 10);
        approachRate = clamp(approachRate, 0, 10);
        overallDifficulty = clamp(overallDifficulty, 0, 10);
        sliderMultiplier = clamp(sliderMultiplier, 0.4, 3.6);
        sliderTickRate = clamp(sliderTickRate, 0.5, 8);
        timingPoints.sort(Comparator.comparingLong(TimingPoint::time));
        List<HitObject> objects = new ArrayList<>(objectLines.size());
        for (String line : objectLines) {
            HitObject object = parseObject(line, timingPoints, sliderMultiplier,
                    sliderTickRate, formatVersion);
            if (object != null) objects.add(object);
        }
        objects.sort(Comparator.comparingLong(HitObject::startTime));
        if (objects.isEmpty()) throw new IOException("Beatmap contains no supported osu!standard hit objects");
        BeatmapData raw = new BeatmapData(circleSize, approachRate, overallDifficulty,
                List.copyOf(objects), formatVersion, stackLeniency);
        return new BeatmapData(circleSize, approachRate, overallDifficulty,
                stackObjects(objects, raw.preemptMs(), raw.circleRadius(), formatVersion, stackLeniency),
                formatVersion, stackLeniency);
    }

    private static HitObject parseObject(String line, List<TimingPoint> timingPoints,
                                         double sliderMultiplier, double sliderTickRate,
                                         int formatVersion) {
        String[] fields = line.split(",");
        if (fields.length < 5) return null;
        try {
            double x = clamp(Double.parseDouble(fields[0]), 0, 512);
            double y = clamp(Double.parseDouble(fields[1]), 0, 512);
            if (formatVersion < 128) {
                x = (int) x;
                y = (int) y;
            }
            long time = Math.round(Double.parseDouble(fields[2]))
                    + (formatVersion < 5 ? 24 : 0);
            int flags = Integer.parseInt(fields[3]);
            if ((flags & 1) != 0)
                return new HitObject(ObjectType.CIRCLE, x, y, time, time, List.of(), 1);
            if ((flags & 2) != 0 && fields.length >= 8) {
                int repeats = Math.max(1, Integer.parseInt(fields[6]));
                if (repeats > 9000) return null;
                double pixelLength = Math.max(0, Double.parseDouble(fields[7]));
                List<Point2D.Double> points = parseSliderPath(x, y, fields[5], pixelLength,
                        formatVersion);
                SliderTiming timing = sliderTiming(time, repeats, pixelLength,
                        sliderMultiplier, sliderTickRate, formatVersion, timingPoints);
                return new HitObject(ObjectType.SLIDER, x, y, time, time + timing.durationMs(),
                        List.copyOf(points), repeats, 0, timing.tickIntervalMs());
            }
            if ((flags & 8) != 0 && fields.length >= 6) {
                long end = Math.max(time, Math.round(Double.parseDouble(fields[5]))
                        + (formatVersion < 5 ? 24 : 0));
                return new HitObject(ObjectType.SPINNER, 256, 192, time, end, List.of(), 1);
            }
        } catch (NumberFormatException ignored) {
            // A malformed object is skipped; other objects remain useful for the overlay.
        }
        return null;
    }

    private static List<Point2D.Double> parseSliderPath(double x, double y, String value,
                                                        double pixelLength, int formatVersion) {
        List<Point2D.Double> controls = new ArrayList<>();
        controls.add(new Point2D.Double(x, y));
        String[] components = value.split("\\|");
        for (int index = 1; index < components.length; index++) {
            String[] pair = components[index].split(":");
            if (pair.length != 2) continue;
            try {
                double pointX = Double.parseDouble(pair[0]);
                double pointY = Double.parseDouble(pair[1]);
                if (formatVersion < 128) {
                    pointX = (int) pointX;
                    pointY = (int) pointY;
                }
                controls.add(new Point2D.Double(pointX, pointY));
            } catch (NumberFormatException ignored) {
                // Skip a malformed control point.
            }
        }
        if (controls.size() < 2) return controls;
        char type = components[0].isEmpty() ? 'B' : components[0].charAt(0);
        List<Point2D.Double> sampled = switch (type) {
            case 'L' -> controls;
            case 'C' -> sampleCatmull(controls);
            case 'P' -> controls.size() == 3 ? samplePerfectCircle(controls) : sampleBezier(controls);
            default -> sampleBezier(controls);
        };
        return fitToLength(sampled, pixelLength);
    }

    private static List<Point2D.Double> sampleBezier(List<Point2D.Double> controls) {
        List<Point2D.Double> result = new ArrayList<>();
        List<Point2D.Double> segment = new ArrayList<>();
        segment.add(controls.getFirst());
        for (int index = 1; index < controls.size(); index++) {
            Point2D.Double point = controls.get(index);
            Point2D.Double previous = controls.get(index - 1);
            if (index < controls.size() - 1 && samePoint(point, previous)) {
                appendBezierSegment(result, segment);
                segment = new ArrayList<>();
                segment.add(point);
            } else {
                segment.add(point);
            }
        }
        appendBezierSegment(result, segment);
        return result;
    }

    private static void appendBezierSegment(List<Point2D.Double> output,
                                            List<Point2D.Double> controls) {
        if (controls.size() < 2) return;
        int steps = Math.max(24, controls.size() * 32);
        for (int step = output.isEmpty() ? 0 : 1; step <= steps; step++) {
            double t = step / (double) steps;
            List<Point2D.Double> work = new ArrayList<>(controls.size());
            for (Point2D.Double point : controls) work.add(new Point2D.Double(point.x, point.y));
            for (int level = work.size() - 1; level > 0; level--) {
                for (int index = 0; index < level; index++) {
                    Point2D.Double first = work.get(index);
                    Point2D.Double second = work.get(index + 1);
                    first.x += (second.x - first.x) * t;
                    first.y += (second.y - first.y) * t;
                }
            }
            output.add(work.getFirst());
        }
    }

    private static List<Point2D.Double> sampleCatmull(List<Point2D.Double> controls) {
        List<Point2D.Double> result = new ArrayList<>();
        for (int index = 0; index < controls.size() - 1; index++) {
            Point2D.Double p0 = controls.get(Math.max(0, index - 1));
            Point2D.Double p1 = controls.get(index);
            Point2D.Double p2 = controls.get(index + 1);
            Point2D.Double p3 = controls.get(Math.min(controls.size() - 1, index + 2));
            for (int step = index == 0 ? 0 : 1; step <= 32; step++) {
                double t = step / 32.0;
                double t2 = t * t;
                double t3 = t2 * t;
                double px = 0.5 * ((2 * p1.x) + (-p0.x + p2.x) * t
                        + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2
                        + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
                double py = 0.5 * ((2 * p1.y) + (-p0.y + p2.y) * t
                        + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2
                        + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3);
                result.add(new Point2D.Double(px, py));
            }
        }
        return result;
    }

    private static List<Point2D.Double> samplePerfectCircle(List<Point2D.Double> controls) {
        Point2D.Double a = controls.get(0);
        Point2D.Double b = controls.get(1);
        Point2D.Double c = controls.get(2);
        double determinant = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y));
        if (Math.abs(determinant) < 0.001) return controls;
        double a2 = a.x * a.x + a.y * a.y;
        double b2 = b.x * b.x + b.y * b.y;
        double c2 = c.x * c.x + c.y * c.y;
        double centerX = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / determinant;
        double centerY = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / determinant;
        double start = Math.atan2(a.y - centerY, a.x - centerX);
        double middle = Math.atan2(b.y - centerY, b.x - centerX);
        double end = Math.atan2(c.y - centerY, c.x - centerX);
        boolean counterClockwise = angleBetweenCounterClockwise(start, end, middle);
        double sweep = normalizedAngle(end - start);
        if (!counterClockwise) sweep -= Math.PI * 2;
        double radius = Point2D.distance(a.x, a.y, centerX, centerY);
        int steps = Math.max(32, (int) Math.ceil(Math.abs(sweep) * radius / 3.0));
        List<Point2D.Double> result = new ArrayList<>(steps + 1);
        for (int step = 0; step <= steps; step++) {
            double angle = start + sweep * step / steps;
            result.add(new Point2D.Double(centerX + Math.cos(angle) * radius,
                    centerY + Math.sin(angle) * radius));
        }
        return result;
    }

    private static boolean angleBetweenCounterClockwise(double start, double end, double value) {
        return normalizedAngle(value - start) <= normalizedAngle(end - start);
    }

    private static double normalizedAngle(double value) {
        double result = value % (Math.PI * 2);
        return result < 0 ? result + Math.PI * 2 : result;
    }

    private static List<Point2D.Double> fitToLength(List<Point2D.Double> sampled,
                                                     double pixelLength) {
        if (sampled.size() < 2 || pixelLength <= 0) return sampled;
        List<Point2D.Double> result = new ArrayList<>();
        result.add(sampled.getFirst());
        double accumulated = 0;
        for (int index = 1; index < sampled.size(); index++) {
            Point2D.Double from = sampled.get(index - 1);
            Point2D.Double to = sampled.get(index);
            double segment = from.distance(to);
            if (segment <= 0) continue;
            if (accumulated + segment >= pixelLength) {
                double rate = (pixelLength - accumulated) / segment;
                result.add(new Point2D.Double(from.x + (to.x - from.x) * rate,
                        from.y + (to.y - from.y) * rate));
                return result;
            }
            result.add(to);
            accumulated += segment;
        }
        Point2D.Double last = result.getLast();
        Point2D.Double previous = result.get(result.size() - 2);
        double segment = previous.distance(last);
        if (segment > 0 && accumulated < pixelLength) {
            double extension = (pixelLength - accumulated) / segment;
            result.add(new Point2D.Double(last.x + (last.x - previous.x) * extension,
                    last.y + (last.y - previous.y) * extension));
        }
        return result;
    }

    private static boolean samePoint(Point2D.Double first, Point2D.Double second) {
        return first.x == second.x && first.y == second.y;
    }

    private record SliderTiming(long durationMs, double tickIntervalMs) {
    }

    private static SliderTiming sliderTiming(long time, int repeats, double pixelLength,
                                             double sliderMultiplier, double sliderTickRate,
                                             int formatVersion, List<TimingPoint> points) {
        double beatLength = 500;
        double velocity = 1;
        for (TimingPoint point : points) {
            if (point.time() > time) break;
            if (point.uninherited() && point.beatLength() > 0) {
                beatLength = point.beatLength();
                velocity = 1;
            } else if (!point.uninherited() && point.beatLength() < 0) {
                velocity = Math.max(0.1, Math.min(10, -100.0 / point.beatLength()));
            }
        }
        double scoringDistance = Math.max(0.01, 100.0 * sliderMultiplier * velocity);
        long duration = Math.max(0,
                Math.round(pixelLength * repeats / scoringDistance * beatLength));
        double tickInterval = beatLength / sliderTickRate;
        if (formatVersion < 8) tickInterval /= velocity;
        return new SliderTiming(duration, tickInterval);
    }

    long preemptMs() {
        if (approachRate < 5) return Math.round(1200 + 600 * (5 - approachRate) / 5);
        if (approachRate > 5) return Math.round(1200 - 750 * (approachRate - 5) / 5);
        return 1200;
    }

    double circleRadius() {
        // osu! retains this tiny legacy playfield-rounding allowance for replay
        // compatibility; it can decide hits right on the circle boundary.
        return Math.max(8, 54.4 - 4.48 * circleSize) * 1.00041;
    }

    long fadeInMs() {
        return Math.round(400 * Math.min(1, preemptMs() / 450.0));
    }

    BeatmapData withMods(ReplayData replay) {
        if (!replay.hasHardRock() && !replay.hasEasy()) return this;

        List<HitObject> transformed = new ArrayList<>(hitObjects.size());
        for (HitObject object : unstackObjects(hitObjects, circleRadius())) {
            List<Point2D.Double> path = object.path().stream()
                    .map(point -> new Point2D.Double(point.x,
                            replay.hasHardRock() ? PLAYFIELD_HEIGHT - point.y : point.y))
                    .toList();
            transformed.add(new HitObject(object.type(), object.x(),
                    replay.hasHardRock() ? PLAYFIELD_HEIGHT - object.y() : object.y(),
                    object.startTime(), object.endTime(), path, object.repeatCount(), 0,
                    object.tickIntervalMs()));
        }
        double modifiedCircleSize = replay.hasHardRock() ? Math.min(10, circleSize * 1.3)
                : circleSize * 0.5;
        double modifiedApproachRate = replay.hasHardRock() ? Math.min(10, approachRate * 1.4)
                : approachRate * 0.5;
        double modifiedOverallDifficulty = replay.hasHardRock() ? Math.min(10, overallDifficulty * 1.4)
                : overallDifficulty * 0.5;
        BeatmapData raw = new BeatmapData(modifiedCircleSize, modifiedApproachRate,
                modifiedOverallDifficulty, List.copyOf(transformed), formatVersion, stackLeniency);
        return new BeatmapData(modifiedCircleSize, modifiedApproachRate, modifiedOverallDifficulty,
                stackObjects(transformed, raw.preemptMs(), raw.circleRadius(), formatVersion, stackLeniency),
                formatVersion, stackLeniency);
    }

    private static List<HitObject> stackObjects(List<HitObject> objects, long preempt,
                                                 double radius, int version,
                                                 double stackLeniency) {
        int[] heights = new int[objects.size()];
        double threshold = preempt * stackLeniency;
        if (version >= 6) applyModernStacking(objects, heights, threshold);
        else applyLegacyStacking(objects, heights, threshold);

        double scale = radius / 64.0;
        List<HitObject> stacked = new ArrayList<>(objects.size());
        for (int index = 0; index < objects.size(); index++) {
            HitObject object = objects.get(index);
            double offset = heights[index] * scale * STACK_OFFSET;
            stacked.add(translate(object, offset, heights[index]));
        }
        return List.copyOf(stacked);
    }

    private static void applyModernStacking(List<HitObject> objects, int[] heights,
                                             double threshold) {
        for (int index = objects.size() - 1; index > 0; index--) {
            HitObject objectI = objects.get(index);
            if (heights[index] != 0 || objectI.type() == ObjectType.SPINNER) continue;

            int currentIndex = index;
            if (objectI.type() == ObjectType.CIRCLE) {
                for (int previousIndex = index - 1; previousIndex >= 0; previousIndex--) {
                    HitObject objectN = objects.get(previousIndex);
                    if (objectN.type() == ObjectType.SPINNER) continue;
                    if (objectI.startTime() - objectN.endTime() > threshold) break;

                    if (objectN.type() == ObjectType.SLIDER
                            && distance(endPosition(objectN), position(objectI)) < STACK_DISTANCE) {
                        int offset = heights[currentIndex] - heights[previousIndex] + 1;
                        Point2D.Double sliderEnd = endPosition(objectN);
                        for (int stackedIndex = previousIndex + 1; stackedIndex <= index; stackedIndex++) {
                            if (distance(sliderEnd, position(objects.get(stackedIndex))) < STACK_DISTANCE)
                                heights[stackedIndex] -= offset;
                        }
                        break;
                    }

                    if (distance(position(objectN), position(objectI)) < STACK_DISTANCE) {
                        heights[previousIndex] = heights[currentIndex] + 1;
                        objectI = objectN;
                        currentIndex = previousIndex;
                    }
                }
            } else if (objectI.type() == ObjectType.SLIDER) {
                for (int previousIndex = index - 1; previousIndex >= 0; previousIndex--) {
                    HitObject objectN = objects.get(previousIndex);
                    if (objectN.type() == ObjectType.SPINNER) continue;
                    if (objectI.startTime() - objectN.startTime() > threshold) break;
                    if (distance(endPosition(objectN), position(objectI)) < STACK_DISTANCE) {
                        heights[previousIndex] = heights[currentIndex] + 1;
                        objectI = objectN;
                        currentIndex = previousIndex;
                    }
                }
            }
        }
    }

    private static void applyLegacyStacking(List<HitObject> objects, int[] heights,
                                             double threshold) {
        for (int index = 0; index < objects.size(); index++) {
            HitObject current = objects.get(index);
            if (heights[index] != 0 && current.type() != ObjectType.SLIDER) continue;
            long startTime = current.endTime();
            int sliderStack = 0;
            for (int nextIndex = index + 1; nextIndex < objects.size(); nextIndex++) {
                HitObject next = objects.get(nextIndex);
                if (next.startTime() - threshold > startTime) break;
                if (distance(position(next), position(current)) < STACK_DISTANCE) {
                    heights[index]++;
                    startTime = next.startTime();
                } else if (distance(position(next), endPosition(current)) < STACK_DISTANCE) {
                    heights[nextIndex] -= ++sliderStack;
                    startTime = next.startTime();
                }
            }
        }
    }

    private static List<HitObject> unstackObjects(List<HitObject> objects, double radius) {
        double scale = radius / 64.0;
        return objects.stream()
                .map(object -> translate(object,
                        -object.stackHeight() * scale * STACK_OFFSET, 0))
                .toList();
    }

    private static HitObject translate(HitObject object, double offset, int stackHeight) {
        List<Point2D.Double> path = object.path().stream()
                .map(point -> new Point2D.Double(point.x + offset, point.y + offset))
                .toList();
        return new HitObject(object.type(), object.x() + offset, object.y() + offset,
                object.startTime(), object.endTime(), path, object.repeatCount(), stackHeight,
                object.tickIntervalMs());
    }

    private static Point2D.Double position(HitObject object) {
        return new Point2D.Double(object.x(), object.y());
    }

    private static Point2D.Double endPosition(HitObject object) {
        return object.type() == ObjectType.SLIDER && !object.path().isEmpty()
                ? object.path().getLast() : position(object);
    }

    private static double distance(Point2D.Double first, Point2D.Double second) {
        return first.distance(second);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
