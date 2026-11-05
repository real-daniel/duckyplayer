package io.duckyplayer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

final class BeatmapAudioResolver {
    record BeatmapAudio(Path beatmap, Path audio, int audioLeadInMs) {
    }

    private final Path songsDirectory;

    BeatmapAudioResolver() {
        this(defaultSongsDirectory());
    }

    BeatmapAudioResolver(Path songsDirectory) {
        this.songsDirectory = songsDirectory;
    }

    Optional<BeatmapAudio> resolve(String beatmapHash) throws IOException {
        Optional<Path> beatmap = findBeatmap(beatmapHash);
        return beatmap.map(path -> {
            try {
                return readAudio(path);
            } catch (IOException exception) {
                throw new ResolverException(exception);
            }
        });
    }

    Optional<Path> findBeatmap(String beatmapHash) throws IOException {
        if (!Files.isDirectory(songsDirectory)) return Optional.empty();
        String expected = beatmapHash.toLowerCase(Locale.ROOT);
        try (Stream<Path> paths = Files.walk(songsDirectory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".osu"))
                    .filter(path -> hasHash(path, expected))
                    .findFirst();
        }
    }

    private static boolean hasHash(Path path, String expected) {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0; ) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest()).equals(expected);
        } catch (IOException | NoSuchAlgorithmException exception) {
            return false;
        }
    }

    private static BeatmapAudio readAudio(Path beatmap) throws IOException {
        String audioFilename = null;
        int leadIn = 0;
        for (String line : Files.readAllLines(beatmap, StandardCharsets.UTF_8)) {
            if (line.startsWith("AudioFilename:")) audioFilename = line.substring("AudioFilename:".length()).trim();
            else if (line.startsWith("AudioLeadIn:")) {
                try {
                    leadIn = Integer.parseInt(line.substring("AudioLeadIn:".length()).trim());
                } catch (NumberFormatException ignored) {
                    leadIn = 0;
                }
            }
        }
        if (audioFilename == null || audioFilename.isBlank()) throw new IOException("Beatmap has no AudioFilename");
        Path directory = beatmap.toAbsolutePath().normalize().getParent();
        Path audio = directory.resolve(audioFilename).normalize();
        if (!audio.startsWith(directory) || !Files.isRegularFile(audio))
            throw new IOException("Beatmap audio file was not found: " + audioFilename);
        return new BeatmapAudio(beatmap, audio, leadIn);
    }

    private static Path defaultSongsDirectory() {
        String override = System.getProperty("duckyplayer.songs");
        if (override != null && !override.isBlank()) return Path.of(override);
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData == null ? Path.of("Songs") : Path.of(localAppData, "osu!", "Songs");
    }

    private static final class ResolverException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final IOException cause;

        ResolverException(IOException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
