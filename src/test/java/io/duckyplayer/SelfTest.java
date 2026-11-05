package io.duckyplayer;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SelfTest {
    private SelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        parseSimpleFrames();
        encodeEditedFrames();
        roundTripEditedReplay();
        exportUpdatesEstimatedResults();
        parseBeatmapOverlay();
        applyLegacyDecoderDefaults();
        applyLegacyCoordinateRules();
        applyEarlyBeatmapTimingOffset();
        applyBeatmapStacking();
        applyHardRockToBeatmap();
        applyEasyToBeatmap();
        sliderGeometryHonorsPixelLength();
        evaluateReplayHits();
        applyAccuracySafetyMargin();
        evaluateReplayHitsChronologically();
        enforceStartTimeNoteLock();
        evaluateFirstValidPress();
        evaluateSliderTracking();
        evaluateGeneratedSliderTicks();
        evaluateSpinnerRotation();
        editInputBlocks();
        swapSelectedInputLanes();
        loadBeatmapsetArchive();
        removeStartupFramesAndBackwardsTime();
        verifyClockRates();
        collapseLateCursorTicks();
        keepPeriodicDeadlinesStable();
        verifyReplayKeyBindings();
        verifyMemoryClockProtocol();
        resampleAudioReference();
        verifyNativeWaiter();
        parseLazerLogSequence();
        tailSyntheticLazerLog();
        System.out.println("Self-tests passed.");
    }

    private static void verifyMemoryClockProtocol() throws Exception {
        var sample = MemoryClock.parse("OK\t123\t-40.5\t0", 100, 20);
        require(sample.identity() == 123 && sample.timeMs() == -40.5 && !sample.paused(), "memory clock protocol");
        require(MemoryClock.parse("OK\t123\t0\t1", 100, 20).paused(), "pause flag");
        var zero = MemoryClock.parse("OK\t123\t0\t0", 100, 20);
        require(!MemoryClock.isAdvancing(null, zero), "first initialized sample must not start measurement");
        require(!MemoryClock.isAdvancing(zero, zero), "stationary startup clock must not start measurement");
        var advancing = MemoryClock.parse("OK\t123\t1\t0", 200, 20);
        require(MemoryClock.isFreshStart(true, advancing), "zero then advancing must permit playback without negative lead-in");
        require(!MemoryClock.isFreshStart(false, advancing), "unobserved start must not join mid-map");
        require(!MemoryClock.isFreshStart(true, MemoryClock.parse("OK\t123\t101\t0", 200, 20)), "late starts rejected");
        require(MemoryClock.isAdvancing(zero, MemoryClock.parse("OK\t123\t1\t0", 200, 20)), "advancing clock starts measurement");
        require(!MemoryClock.isAdvancing(zero, MemoryClock.parse("OK\t124\t1\t0", 200, 20)), "changed identity must not start measurement");
        require(!MemoryClock.isAdvancing(zero, MemoryClock.parse("OK\t123\t1\t1", 200, 20)), "paused clock must not start measurement");
        List<ReplayFrame> introFrames = List.of(
                new ReplayFrame(0, 256, 192, 0),
                new ReplayFrame(1000, 300, 190, 0),
                new ReplayFrame(2000, 320, 180, 0),
                new ReplayFrame(2500, 320, 180, 4));
        require(PlaybackController.safeSkipIndex(introFrames, 0, 0, 1800).index() == 2,
                "intro skip must advance to the first future cursor frame");
        require(PlaybackController.safeSkipIndex(introFrames, 0, 0, 2500) == null,
                "intro skip must not cross the first pressed input");
        require(PlaybackController.safeSkipIndex(introFrames, 3, 4, 2800).index() == 4,
                "clock jump after a held input may skip cursor-only frames");
        require(PlaybackController.safeSkipIndex(introFrames, 3, 0, 2800) == null,
                "clock jump must not skip an input transition");
        List<ReplayFrame> releaseDuringSkip = List.of(
                new ReplayFrame(1000, 100, 100, 4),
                new ReplayFrame(1010, 110, 100, 0),
                new ReplayFrame(1500, 200, 100, 0));
        PlaybackController.SkipPoint released = PlaybackController.safeSkipIndex(
                releaseDuringSkip, 1, 4, 1500);
        require(released != null && released.index() == 3 && released.keys() == 0,
                "clock jump may collapse a release after the input that caused it");
        for (String invalid : List.of("ERR\tstale", "OK\t1\tNaN\t0", "OK\t1\t0\t2", "garbage")) {
            boolean rejected = false;
            try { MemoryClock.parse(invalid, 100, 20); } catch (java.io.IOException e) { rejected = true; }
            require(rejected, "invalid memory clock sample must fail closed");
        }
    }

    private static void resampleAudioReference() {
        float[] result = AudioStartSynchronizer.resample(new float[]{0, 1, 0, -1}, 4, 8);
        require(result.length == 8, "resampled length");
        require(result[0] == 0 && result[2] == 1 && result[4] == 0 && result[6] == -1,
                "resampled positions");
    }

    private static void parseSimpleFrames() throws Exception {
        List<ReplayFrame> frames = ReplayReader.parseFrames(
                "0|256|192|0,16|300.5|200.25|5,-12345|0|0|123,");
        require(frames.size() == 2, "expected two playable frames");
        require(frames.get(0).timeMs() == 0, "first timestamp");
        require(frames.get(1).timeMs() == 16, "second timestamp");
        require(frames.get(1).keys() == 5, "key mask");
    }

    private static void encodeEditedFrames() throws Exception {
        List<ReplayFrame> original = List.of(
                new ReplayFrame(0, 256.5, 192.25, 0),
                new ReplayFrame(16, 300, 200, -1));
        String encoded = ReplayWriter.encodeFrames(original, "-12345|0|0|9876");
        require(encoded.endsWith("-12345|0|0|9876,"), "replay seed must be preserved");
        require(ReplayReader.parseFrames(encoded).equals(original), "edited frames must round-trip");
        ReplayWriter.validate(original);
    }

    private static void roundTripEditedReplay() throws Exception {
        Path file = Files.createTempFile("ducky-player-editor-test-", ".osr");
        try {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            header.write(0);
            writeLittleEndian(header, 20261002, Integer.BYTES);
            writeReplayString(header, "beatmap-md5");
            writeReplayString(header, "editor-test");
            writeReplayString(header, "original-replay-md5");
            for (int index = 0; index < 6; index++) writeLittleEndian(header, 0, Short.BYTES);
            writeLittleEndian(header, 0, Integer.BYTES);
            writeLittleEndian(header, 0, Short.BYTES);
            header.write(0);
            writeLittleEndian(header, 0, Integer.BYTES);
            writeReplayString(header, "");
            writeLittleEndian(header, 0, Long.BYTES);

            List<ReplayFrame> edited = List.of(
                    new ReplayFrame(0, 256, 192, 0),
                    new ReplayFrame(12, 280.25, 190.75, 5),
                    new ReplayFrame(25, 300, 210, 0));
            byte[] footer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(42).array();
            ReplayData data = new ReplayData(0, 20261002, "beatmap-md5", "editor-test", 0, edited);
            ReplayDocument document = new ReplayDocument(file, data, header.toByteArray(), footer,
                    "-12345|0|0|456789");
            ReplayWriter.save(document, edited, file);
            ReplayDocument loaded = ReplayReader.loadDocument(file);
            require(loaded.data().frames().equals(edited), "exported replay frames must round-trip");
            require(java.util.Arrays.equals(loaded.footer(), footer), "replay footer must be preserved");
            require(loaded.terminalRecord().endsWith("456789"), "replay seed must survive compression");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void exportUpdatesEstimatedResults() throws Exception {
        Path file = Files.createTempFile("ducky-player-result-header-test-", ".osr");
        try {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            header.write(0);
            writeLittleEndian(header, 20261002, Integer.BYTES);
            writeReplayString(header, "beatmap-md5");
            writeReplayString(header, "editor-test");
            writeReplayString(header, "original-replay-md5");
            for (int value : List.of(9, 8, 7, 6, 5, 4))
                writeLittleEndian(header, value, Short.BYTES);
            writeLittleEndian(header, 123456, Integer.BYTES);
            writeLittleEndian(header, 321, Short.BYTES);
            header.write(1);
            writeLittleEndian(header, 0, Integer.BYTES);
            writeReplayString(header, "");
            writeLittleEndian(header, 0, Long.BYTES);

            List<ReplayFrame> frames = List.of(new ReplayFrame(0, 100, 100, 0));
            ReplayData data = new ReplayData(0, 20261002, "beatmap-md5", "editor-test", 0, frames);
            ReplayDocument document = new ReplayDocument(file, data, header.toByteArray(), new byte[0],
                    "-12345|0|0|1");
            ReplayAnalysis analysis = new ReplayAnalysis(List.of(), List.of(),
                    10, 11, 12, 13, 0, 222, 0.5);
            ReplayWriter.save(document, frames, analysis, file);

            ByteBuffer exported = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
            exported.position(1 + Integer.BYTES);
            skipReplayString(exported);
            skipReplayString(exported);
            skipReplayString(exported);
            require(Short.toUnsignedInt(exported.getShort()) == 10, "exported 300 count");
            require(Short.toUnsignedInt(exported.getShort()) == 11, "exported 100 count");
            require(Short.toUnsignedInt(exported.getShort()) == 12, "exported 50 count");
            require(Short.toUnsignedInt(exported.getShort()) == 0, "exported geki count");
            require(Short.toUnsignedInt(exported.getShort()) == 0, "exported katu count");
            require(Short.toUnsignedInt(exported.getShort()) == 13, "exported miss count");
            require(exported.getInt() == 123456, "score must remain unchanged");
            require(Short.toUnsignedInt(exported.getShort()) == 222, "exported maximum combo");
            require(exported.get() == 0, "non-perfect export flag");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void skipReplayString(ByteBuffer buffer) {
        int marker = Byte.toUnsignedInt(buffer.get());
        if (marker == 0) return;
        require(marker == 0x0b, "replay string marker");
        int length = 0;
        int shift = 0;
        while (true) {
            int value = Byte.toUnsignedInt(buffer.get());
            length |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) break;
            shift += 7;
        }
        buffer.position(buffer.position() + length);
    }

    private static void parseBeatmapOverlay() throws Exception {
        Path file = Files.createTempFile("ducky-player-beatmap-test-", ".osu");
        try {
            Files.writeString(file, "osu file format v14\n"
                    + "[Difficulty]\nCircleSize:4\nApproachRate:9\nSliderMultiplier:1.4\n"
                    + "[TimingPoints]\n0,500,4,2,1,60,1,0\n"
                    + "[HitObjects]\n"
                    + "128,96,1000,1,0,0:0:0:0:\n"
                    + "256,192,1500,2,0,B|300:192|350:220,2,140\n"
                    + "256,192,3000,8,0,4000\n", StandardCharsets.UTF_8);
            BeatmapData map = BeatmapData.load(file);
            require(map.hitObjects().size() == 3, "circle, slider and spinner must parse");
            require(map.hitObjects().get(1).endTime() == 2500, "slider duration");
            require(map.preemptMs() == 600, "AR9 preempt");
            require(Math.abs(map.circleRadius() - 36.4949568) < 0.001, "CS4 circle radius");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void sliderGeometryHonorsPixelLength() throws Exception {
        Path file = Files.createTempFile("ducky-player-slider-geometry-test-", ".osu");
        try {
            Files.writeString(file, "osu file format v14\n"
                    + "[Difficulty]\nSliderMultiplier:1.4\n"
                    + "[TimingPoints]\n0,500,4,2,1,60,1,0\n"
                    + "[HitObjects]\n256,192,1000,2,0,L|400:192,1,100\n",
                    StandardCharsets.UTF_8);
            BeatmapData.HitObject slider = BeatmapData.load(file).hitObjects().getFirst();
            java.awt.geom.Point2D.Double endpoint = slider.path().getLast();
            require(Math.abs(endpoint.x - 356) < 0.001 && Math.abs(endpoint.y - 192) < 0.001,
                    "slider endpoint must be trimmed to declared pixel length");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void applyLegacyDecoderDefaults() throws Exception {
        BeatmapData map = BeatmapData.load(("osu file format v5\n"
                + "[Difficulty]\nCircleSize:20\nOverallDifficulty:8\n"
                + "SliderMultiplier:99\nSliderTickRate:99\n"
                + "[HitObjects]\n100,100,1000,1,0\n").getBytes(StandardCharsets.UTF_8));
        require(map.circleSize() == 10, "legacy decoder clamps circle size");
        require(map.overallDifficulty() == 8 && map.approachRate() == 8,
                "missing approach rate inherits overall difficulty");
    }

    private static void applyLegacyCoordinateRules() throws Exception {
        BeatmapData map = BeatmapData.load(("osu file format v14\n[HitObjects]\n"
                + "600.9,-10.4,1000,1,0\n"
                + "10.9,20.9,2000,2,0,L|110.9:20.9,1,100\n"
                + "10,20,3000,8,0,4000\n").getBytes(StandardCharsets.UTF_8));
        require(map.hitObjects().get(0).x() == 512 && map.hitObjects().get(0).y() == 0,
                "legacy top-level coordinates are clamped and truncated");
        require(map.hitObjects().get(1).x() == 10
                        && map.hitObjects().get(1).path().getLast().x == 110,
                "legacy slider coordinates are truncated before path fitting");
        require(map.hitObjects().get(2).x() == 256 && map.hitObjects().get(2).y() == 192,
                "spinner position is always the playfield centre");
    }

    private static void applyEarlyBeatmapTimingOffset() throws Exception {
        BeatmapData map = BeatmapData.load(("osu file format v4\n"
                + "[TimingPoints]\n0,500,4,2,1,60,1,0\n"
                + "[HitObjects]\n100,100,1000,1,0 // trailing comment\n"
                + "256,192,2000,8,0,3000\n").getBytes(StandardCharsets.UTF_8));
        require(map.hitObjects().get(0).startTime() == 1024,
                "v4 hit objects receive the legacy 24 ms correction");
        require(map.hitObjects().get(1).endTime() == 3024,
                "v4 spinner ends receive the legacy 24 ms correction");
    }

    private static void applyBeatmapStacking() throws Exception {
        BeatmapData circles = BeatmapData.load(("osu file format v14\n"
                + "[General]\nStackLeniency:0.7\n"
                + "[Difficulty]\nCircleSize:5\nApproachRate:5\n"
                + "[HitObjects]\n"
                + "100,100,1000,1,0,0:0:0:0:\n"
                + "100,100,1100,1,0,0:0:0:0:\n"
                + "100,100,1200,1,0,0:0:0:0:\n").getBytes(StandardCharsets.UTF_8));
        require(circles.hitObjects().get(0).stackHeight() == 2,
                "first overlapping circle must have the largest stack height");
        require(circles.hitObjects().get(1).stackHeight() == 1,
                "second overlapping circle must be offset once");
        require(Math.abs(circles.hitObjects().get(0).x() - 93.6) < 0.01,
                "CS5 stack offset must match osu's pseudo-3D position");

        List<ReplayFrame> frames = List.of(
                new ReplayFrame(0, 0, 0, 0),
                new ReplayFrame(1000, 93.6, 93.6, 4));
        require(ReplayAnalysis.evaluate(circles, frames).count300() == 1,
                "hit evaluation must use the displayed stacked position");

        BeatmapData disabled = BeatmapData.load(("osu file format v14\n"
                + "[General]\nStackLeniency:0\n"
                + "[HitObjects]\n100,100,1000,1,0\n100,100,1100,1,0\n")
                .getBytes(StandardCharsets.UTF_8));
        require(disabled.hitObjects().getFirst().stackHeight() == 0,
                "zero stack leniency must disable separated-time stacking");
    }

    private static void applyHardRockToBeatmap() {
        BeatmapData.HitObject slider = new BeatmapData.HitObject(
                BeatmapData.ObjectType.SLIDER, 128, 96, 1000, 1500,
                List.of(new java.awt.geom.Point2D.Double(128, 96),
                        new java.awt.geom.Point2D.Double(256, 200)), 1);
        BeatmapData original = new BeatmapData(4, 9, 8, List.of(slider));
        ReplayData replay = new ReplayData(0, 0, "", "", ReplayData.MOD_HARD_ROCK, List.of());
        BeatmapData modified = original.withMods(replay);

        require(Math.abs(modified.circleSize() - 5.2) < 0.000001, "HR circle size");
        require(modified.approachRate() == 10, "HR approach rate cap");
        require(modified.overallDifficulty() == 10, "HR overall difficulty cap");
        require(modified.hitObjects().getFirst().y() == 288, "HR object vertical flip");
        require(modified.hitObjects().getFirst().path().getLast().y == 184,
                "HR slider path vertical flip");
        require(original.hitObjects().getFirst().y() == 96, "HR must not mutate raw beatmap");

        List<ReplayFrame> frames = List.of(
                new ReplayFrame(0, 128, 288, 0),
                new ReplayFrame(1000, 128, 288, 4),
                new ReplayFrame(1500, 256, 184, 4));
        require(ReplayAnalysis.evaluate(modified, frames).count300() == 1,
                "HR analysis uses transformed coordinates");

        BeatmapData odFive = new BeatmapData(5, 5, 5,
                List.of(circle(128, 96, 1000))).withMods(replay);
        List<ReplayFrame> lateFrames = List.of(
                new ReplayFrame(0, 128, 288, 0),
                new ReplayFrame(1040, 128, 288, 4));
        require(ReplayAnalysis.evaluate(odFive, lateFrames).count100() == 1,
                "HR analysis uses the modified OD hit window");
    }

    private static void applyEasyToBeatmap() {
        BeatmapData original = new BeatmapData(6, 8, 7,
                List.of(circle(128, 96, 1000)));
        ReplayData replay = new ReplayData(0, 0, "", "", 1 << 1, List.of());
        BeatmapData modified = original.withMods(replay);
        require(modified.circleSize() == 3, "EZ circle size");
        require(modified.approachRate() == 4, "EZ approach rate");
        require(modified.overallDifficulty() == 3.5, "EZ overall difficulty");
        require(modified.hitObjects().getFirst().y() == 96,
                "EZ must not reflect object positions");
    }

    private static void evaluateReplayHits() {
        List<BeatmapData.HitObject> objects = List.of(
                circle(100, 100, 1000), circle(200, 200, 2000),
                circle(300, 300, 3000), circle(400, 300, 4000));
        BeatmapData beatmap = new BeatmapData(5, 5, 5, objects);
        List<ReplayFrame> replayFrames = List.of(
                new ReplayFrame(0, 0, 0, 0),
                new ReplayFrame(1000, 100, 100, 4),
                new ReplayFrame(1010, 100, 100, 0),
                new ReplayFrame(2080, 200, 200, 8),
                new ReplayFrame(2090, 200, 200, 0),
                new ReplayFrame(3140, 300, 300, 4),
                new ReplayFrame(3150, 300, 300, 0),
                new ReplayFrame(4000, 0, 0, 4));
        ReplayAnalysis analysis = ReplayAnalysis.evaluate(beatmap, replayFrames);
        require(analysis.count300() == 1, "300 evaluation");
        require(analysis.count100() == 1, "100 evaluation");
        require(analysis.count50() == 1, "50 evaluation");
        require(analysis.misses() == 1, "miss evaluation");
        require(analysis.maxCombo() == 3, "maximum combo evaluation");
        require(Math.abs(analysis.accuracy() - 0.375) < 0.000001, "accuracy evaluation");
    }

    private static void applyAccuracySafetyMargin() {
        BeatmapData beatmap = new BeatmapData(5, 5, 5,
                List.of(circle(100, 100, 1000)));
        List<ReplayFrame> frames = List.of(
                new ReplayFrame(0, 100, 100, 0),
                new ReplayFrame(1046, 100, 100, 4));
        require(ReplayAnalysis.evaluate(beatmap, frames).count300() == 1,
                "borderline hit is a 300 without an accuracy safety margin");
        require(ReplayAnalysis.evaluate(beatmap, frames, 0, 4).count100() == 1,
                "accuracy safety margin conservatively downgrades borderline hits");
        boolean rejected = false;
        try {
            ReplayAnalysis.evaluate(beatmap, frames, 0, -1);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "negative accuracy safety margin must be rejected");
    }

    private static BeatmapData.HitObject circle(double x, double y, long time) {
        return new BeatmapData.HitObject(BeatmapData.ObjectType.CIRCLE,
                x, y, time, time, List.of(), 1);
    }

    private static void evaluateReplayHitsChronologically() {
        BeatmapData beatmap = new BeatmapData(5, 5, 5, List.of(
                circle(100, 100, 1000), circle(200, 200, 1050)));
        List<ReplayFrame> replayFrames = List.of(
                new ReplayFrame(0, 0, 0, 0),
                new ReplayFrame(1040, 200, 200, 4),
                new ReplayFrame(1041, 200, 200, 0),
                new ReplayFrame(1050, 100, 100, 4));
        ReplayAnalysis analysis = ReplayAnalysis.evaluate(beatmap, replayFrames);
        require(analysis.count100() == 1 && analysis.misses() == 1,
                "presses must not be matched to hit objects out of chronological order");
    }

    private static void evaluateFirstValidPress() {
        BeatmapData beatmap = new BeatmapData(5, 5, 5,
                List.of(circle(100, 100, 1000)));
        List<ReplayFrame> replayFrames = List.of(
                new ReplayFrame(0, 100, 100, 0),
                new ReplayFrame(920, 100, 100, 4),
                new ReplayFrame(930, 100, 100, 0),
                new ReplayFrame(1000, 100, 100, 4));
        require(ReplayAnalysis.evaluate(beatmap, replayFrames).count100() == 1,
                "the first valid chronological press must determine judgement");
    }

    private static void enforceStartTimeNoteLock() {
        BeatmapData beatmap = new BeatmapData(5, 5, 5, List.of(
                circle(100, 100, 1000), circle(200, 200, 1100)));
        List<ReplayFrame> replayFrames = List.of(
                new ReplayFrame(0, 0, 0, 0),
                new ReplayFrame(950, 200, 200, 4),
                new ReplayFrame(960, 200, 200, 0));
        require(ReplayAnalysis.evaluate(beatmap, replayFrames).misses() == 2,
                "a future object cannot be hit before the prior object's start time");
    }

    private static void evaluateSliderTracking() {
        BeatmapData.HitObject slider = new BeatmapData.HitObject(
                BeatmapData.ObjectType.SLIDER, 100, 100, 1000, 2000,
                List.of(new java.awt.geom.Point2D.Double(100, 100),
                        new java.awt.geom.Point2D.Double(200, 100)), 2);
        BeatmapData beatmap = new BeatmapData(5, 5, 5, List.of(slider));
        List<ReplayFrame> clean = List.of(
                new ReplayFrame(0, 100, 100, 0),
                new ReplayFrame(1000, 100, 100, 4),
                new ReplayFrame(1500, 200, 100, 4),
                new ReplayFrame(2000, 100, 100, 4),
                new ReplayFrame(2010, 100, 100, 0));
        ReplayAnalysis cleanAnalysis = ReplayAnalysis.evaluate(beatmap, clean);
        require(cleanAnalysis.sliderCheckpoints().size() == 2,
                "repeat slider must generate repeat and tail checkpoints");
        require(cleanAnalysis.sliderBreaks() == 0, "head-only evaluation has no slider breaks");
        require(cleanAnalysis.count300() == 1, "fully tracked slider must receive 300");
        require(cleanAnalysis.maxCombo() == 3, "slider parts contribute combo events");

        List<ReplayFrame> broken = List.of(
                new ReplayFrame(0, 100, 100, 0),
                new ReplayFrame(1000, 100, 100, 4),
                new ReplayFrame(1500, 400, 300, 4),
                new ReplayFrame(2000, 100, 100, 4),
                new ReplayFrame(2010, 100, 100, 0));
        ReplayAnalysis brokenAnalysis = ReplayAnalysis.evaluate(beatmap, broken);
        require(brokenAnalysis.sliderBreaks() == 1, "leaving the follow area creates a slider break");
        require(brokenAnalysis.count100() == 1,
                "partially tracked classic slider must receive a reduced judgement");
    }

    private static void evaluateGeneratedSliderTicks() throws Exception {
        BeatmapData beatmap = BeatmapData.load(("osu file format v14\n"
                + "[Difficulty]\nCircleSize:5\nOverallDifficulty:5\n"
                + "SliderMultiplier:1.4\nSliderTickRate:1\n"
                + "[TimingPoints]\n0,500,4,2,1,60,1,0\n"
                + "[HitObjects]\n100,100,1000,2,0,L|380:100,1,280\n")
                .getBytes(StandardCharsets.UTF_8));
        List<ReplayFrame> frames = List.of(
                new ReplayFrame(0, 100, 100, 0),
                new ReplayFrame(1000, 100, 100, 4),
                new ReplayFrame(1500, 240, 100, 4),
                new ReplayFrame(2000, 380, 100, 4));
        ReplayAnalysis analysis = ReplayAnalysis.evaluate(beatmap, frames);
        require(analysis.sliderCheckpoints().size() == 2,
                "slider tick rate generates one tick plus the lenient tail");
        require(analysis.count300() == 1 && analysis.maxCombo() == 3,
                "generated slider parts affect judgement and combo");
    }

    private static void evaluateSpinnerRotation() {
        BeatmapData.HitObject spinner = new BeatmapData.HitObject(
                BeatmapData.ObjectType.SPINNER, 256, 192, 1000, 3000, List.of(), 1);
        BeatmapData beatmap = new BeatmapData(5, 5, 5, List.of(spinner));
        List<ReplayFrame> spinning = new java.util.ArrayList<>();
        spinning.add(new ReplayFrame(0, 356, 192, 0));
        int steps = 120;
        for (int step = 0; step <= steps; step++) {
            double angle = Math.PI * 2 * 6 * step / steps;
            spinning.add(new ReplayFrame(1000 + 2000L * step / steps,
                    256 + Math.cos(angle) * 100, 192 + Math.sin(angle) * 100, 4));
        }
        require(ReplayAnalysis.evaluate(beatmap, spinning).count300() == 1,
                "sufficient replay rotation clears a spinner");
        List<ReplayFrame> stationary = List.of(
                new ReplayFrame(0, 356, 192, 0),
                new ReplayFrame(1000, 356, 192, 4),
                new ReplayFrame(3000, 356, 192, 4));
        require(ReplayAnalysis.evaluate(beatmap, stationary).misses() == 1,
                "holding without rotation does not clear a spinner");
        require(ReplayAnalysis.evaluate(beatmap, stationary, ReplayData.MOD_SPUN_OUT).count300() == 1,
                "Spun Out automatically completes spinners");
    }

    private static void editInputBlocks() {
        List<ReplayFrame> source = List.of(
                new ReplayFrame(0, 0, 0, 0),
                new ReplayFrame(100, 100, 100, 0),
                new ReplayFrame(200, 200, 200, 8));
        List<ReplayFrame> added = ReplayEdits.applyInputBlock(source, 50, 60, 4, true);
        require(frameAt(added, 50).keys() == 4, "input block press boundary");
        require(frameAt(added, 100).keys() == 4, "input block contents");
        require(frameAt(added, 110).keys() == 0, "input block release boundary");
        require(frameAt(added, 200).keys() == 8, "input block preserves other input");
        List<ReplayFrame> removed = ReplayEdits.applyInputBlock(added, 70, 20, 4, false);
        require(frameAt(removed, 70).keys() == 0, "right-click removal starts gap");
        require(frameAt(removed, 90).keys() == 4, "right-click removal restores block");
        List<ReplayFrame> centered = ReplayEdits.applyCenteredInputBlock(source, 100, 50, 4, true);
        require(frameAt(centered, 75).keys() == 4, "centered block starts before cursor");
        require(frameAt(centered, 125).keys() == 0, "centered block ends after cursor");
    }

    private static void swapSelectedInputLanes() {
        int unrelatedBits = 0x4000_0000;
        List<ReplayFrame> source = List.of(
                new ReplayFrame(0, 0, 0, unrelatedBits | 1 | 4),
                new ReplayFrame(100, 100, 100, unrelatedBits | 2 | 8),
                new ReplayFrame(200, 200, 200, unrelatedBits));
        List<ReplayFrame> swapped = ReplayEdits.swapInputLanes(source, 50, 150);
        require(frameAt(swapped, 0).keys() == (unrelatedBits | 1 | 4),
                "input before selection is unchanged");
        require(frameAt(swapped, 50).keys() == (unrelatedBits | 2 | 8),
                "selected M1 and K1 blocks move to M2 and K2");
        require(frameAt(swapped, 100).keys() == (unrelatedBits | 1 | 4),
                "selected M2 and K2 blocks move to M1 and K1");
        require(frameAt(swapped, 150).keys() == (unrelatedBits | 2 | 8),
                "input after selection is unchanged");
        require(frameAt(swapped, 200).keys() == unrelatedBits, "unrelated input bits are preserved");
        require(frameAt(swapped, 50).x() == 50 && frameAt(swapped, 50).y() == 50,
                "selection boundary interpolates cursor position");
        List<ReplayFrame> restored = ReplayEdits.swapInputLanes(swapped, 50, 150);
        require(frameAt(restored, 0).keys() == (unrelatedBits | 1 | 4)
                        && frameAt(restored, 50).keys() == (unrelatedBits | 1 | 4)
                        && frameAt(restored, 100).keys() == (unrelatedBits | 2 | 8)
                        && frameAt(restored, 150).keys() == (unrelatedBits | 2 | 8),
                "selected lane swap is reversible");
    }

    private static void loadBeatmapsetArchive() throws Exception {
        Path archive = Files.createTempFile("ducky-player-beatmapset-test-", ".osz");
        byte[] matching = ("osu file format v14\n[Difficulty]\nCircleSize:5\n"
                + "ApproachRate:8\nOverallDifficulty:7\n"
                + "[HitObjects]\n256,192,1000,1,0,0:0:0:0:\n").getBytes(StandardCharsets.UTF_8);
        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
                output.putNextEntry(new ZipEntry("Wrong difficulty.osu"));
                output.write("not the replay map".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
                output.putNextEntry(new ZipEntry("Folder/Matching difficulty.osu"));
                output.write(matching);
                output.closeEntry();
                output.putNextEntry(new ZipEntry("audio.mp3"));
                output.write(new byte[]{1, 2, 3});
                output.closeEntry();
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(matching));
            BeatmapArchive.Match match = BeatmapArchive.loadMatching(archive, hash);
            require(match.entryName().equals("Folder/Matching difficulty.osu"),
                    "osz must select the replay's difficulty by MD5");
            require(match.beatmap().hitObjects().size() == 1, "osz matching difficulty must parse");
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static ReplayFrame frameAt(List<ReplayFrame> frames, long time) {
        return frames.stream().filter(frame -> frame.timeMs() == time).findFirst().orElseThrow();
    }

    private static void writeReplayString(ByteArrayOutputStream output, String value) {
        if (value.isEmpty()) {
            output.write(0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(0x0b);
        int remaining = bytes.length;
        while (remaining >= 0x80) {
            output.write((remaining & 0x7f) | 0x80);
            remaining >>>= 7;
        }
        output.write(remaining);
        output.writeBytes(bytes);
    }

    private static void writeLittleEndian(ByteArrayOutputStream output, long value, int bytes) {
        for (int index = 0; index < bytes; index++) output.write((int) (value >>> (index * 8)) & 0xff);
    }

    private static void removeStartupFramesAndBackwardsTime() throws Exception {
        List<ReplayFrame> frames = ReplayReader.parseFrames(
                "0|256|-500|0,10|256|-500|0,10|100|100|0,-5|200|200|0,10|300|300|0,");
        require(frames.size() == 2, "startup and backwards frames should be removed");
        require(frames.getFirst().x() == 100, "first playable position");
        require(frames.getLast().x() == 300, "last playable position");
    }

    private static void verifyClockRates() {
        require(new ReplayData(0, 0, "", "", 0, List.of()).clockRate() == 1.0, "normal rate");
        require(new ReplayData(0, 0, "", "", ReplayData.MOD_DOUBLE_TIME, List.of()).clockRate() == 1.5, "DT rate");
        require(new ReplayData(0, 0, "", "", ReplayData.MOD_HALF_TIME, List.of()).clockRate() == 0.75, "HT rate");
        ReplayData hrDt = new ReplayData(0, 0, "", "",
                ReplayData.MOD_HARD_ROCK | ReplayData.MOD_DOUBLE_TIME, List.of());
        require(hrDt.hasHardRock() && hrDt.hasDoubleTime(), "HR and DT flags");
        require(hrDt.modSummary().equals("HR + DT"), "HR and DT summary");
    }

    private static void collapseLateCursorTicks() {
        require(PlaybackController.currentCursorTick(10, 4, 12) == 10,
                "on-time cursor tick remains unchanged");
        require(PlaybackController.currentCursorTick(10, 4, 25) == 22,
                "late cursor ticks collapse to the latest current tick");
        require(PlaybackController.currentCursorTick(10, 4, 26) == 26,
                "exact current cursor tick is retained");
    }

    private static void keepPeriodicDeadlinesStable() {
        require(PlaybackController.nextPeriodicDeadline(0, 4, 10) == 14,
                "first periodic deadline starts one interval ahead");
        require(PlaybackController.nextPeriodicDeadline(14, 4, 12) == 14,
                "early work does not shift the periodic deadline");
        require(PlaybackController.nextPeriodicDeadline(14, 4, 14) == 18,
                "on-time work advances exactly one interval");
        require(PlaybackController.nextPeriodicDeadline(14, 4, 25) == 26,
                "late work skips stale periods without drifting phase");
    }

    private static void verifyReplayKeyBindings() {
        require(NativeWindows.PlaybackKey.Z.virtualKey() == 0x5a, "default K1 key");
        require(NativeWindows.PlaybackKey.X.virtualKey() == 0x58, "default K2 key");
        var sender = new NativeWindows.InputSender();
        sender.setKeyboardKeys(NativeWindows.PlaybackKey.A, NativeWindows.PlaybackKey.SPACE);
        boolean rejected = false;
        try {
            sender.setKeyboardKeys(NativeWindows.PlaybackKey.A, NativeWindows.PlaybackKey.A);
        } catch (IllegalArgumentException exception) {
            rejected = true;
        }
        require(rejected, "duplicate replay keys must be rejected");
    }

    private static void verifyNativeWaiter() {
        try (NativeWindows.HighResolutionWaiter waiter = new NativeWindows.HighResolutionWaiter()) {
            waiter.reset();
            long started = System.nanoTime();
            require(!waiter.waitForNanos(1_000_000), "timer wait must not report stop");
            require(System.nanoTime() - started >= 100_000, "native timer returned implausibly early");
            waiter.stop();
            require(waiter.waitForNanos(1_000_000), "stop event must interrupt wait");
        }
    }

    private static void parseLazerLogSequence() {
        LazerLogSynchronizer.LogSequence sequence = new LazerLogSynchronizer.LogSequence();
        require(sequence.completedSeek("GameplayClockContainer started via call to StartGameplayClock").isEmpty(),
                "clock start without player sequence must be ignored");
        sequence.accept("2026-10-01 [verbose]: entered SoloPlayer#123");
        sequence.accept("2026-10-01 [verbose]: GameplayClockContainer seeking to -1584.5");
        Optional<Double> seek = sequence.completedSeek(
                "2026-10-01 [verbose]: GameplayClockContainer started via call to StartGameplayClock");
        require(seek.isPresent() && seek.get() == -1584.5, "complete lazer log sequence");
    }

    private static void tailSyntheticLazerLog() throws Exception {
        Path directory = Files.createTempDirectory("ducky-player-log-test-");
        Path log = directory.resolve("123.runtime.log");
        Files.writeString(log, "existing line must be ignored\n");
        CountDownLatch watching = new CountDownLatch(1);
        CountDownLatch synchronizedLatch = new CountDownLatch(1);
        AtomicReference<LazerLogSynchronizer.SyncPoint> point = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        try (LazerLogSynchronizer synchronizer = new LazerLogSynchronizer(directory)) {
            synchronizer.arm(new LazerLogSynchronizer.Listener() {
                @Override
                public void onStatus(String status) {
                    if (status.startsWith("Watching 123.runtime.log")) watching.countDown();
                }

                @Override
                public void onSynchronized(LazerLogSynchronizer.SyncPoint syncPoint) {
                    point.set(syncPoint);
                    synchronizedLatch.countDown();
                }

                @Override
                public void onError(String message) {
                    error.set(message);
                    synchronizedLatch.countDown();
                }
            });
            require(watching.await(2, TimeUnit.SECONDS), "watcher did not open synthetic log");
            Files.writeString(log,
                    "entered SoloPlayer#42\n"
                            + "GameplayClockContainer seeking to -1500\n"
                            + "GameplayClockContainer started via call to StartGameplayClock\n",
                    StandardOpenOption.APPEND);
            require(synchronizedLatch.await(2, TimeUnit.SECONDS), "watcher did not detect clock start");
            require(error.get() == null, "watcher error: " + error.get());
            require(point.get() != null && point.get().initialClockMs() == -1500, "detected seek value");
            long expectedDelay = 1_000_000_000L;
            long actualDelay = point.get().replayZeroNanos(1.5) - point.get().detectedNanos();
            require(actualDelay == expectedDelay, "DT lead-in conversion");
        } finally {
            Files.deleteIfExists(log);
            Files.deleteIfExists(directory);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
