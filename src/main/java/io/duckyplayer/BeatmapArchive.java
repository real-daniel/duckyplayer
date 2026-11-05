package io.duckyplayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class BeatmapArchive {
    private static final int MAX_BEATMAP_BYTES = 32 * 1024 * 1024;

    record Match(String entryName, BeatmapData beatmap) {
    }

    private BeatmapArchive() {
    }

    static Match loadMatching(Path archive, String expectedHash) throws IOException {
        String normalizedHash = expectedHash.toLowerCase(Locale.ROOT);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()
                        || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".osu")) continue;
                if (entry.getSize() > MAX_BEATMAP_BYTES)
                    throw new IOException("Beatmap entry exceeds the safety limit: " + entry.getName());
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) {
                    bytes = readLimited(input, entry.getName());
                }
                if (md5(bytes).equals(normalizedHash))
                    return new Match(entry.getName(), BeatmapData.load(bytes));
            }
        }
        throw new IOException("The .osz does not contain the difficulty used by this replay");
    }

    private static byte[] readLimited(InputStream input, String name) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int count; (count = input.read(buffer)) >= 0; ) {
            total += count;
            if (total > MAX_BEATMAP_BYTES)
                throw new IOException("Beatmap entry exceeds the safety limit: " + name);
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String md5(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("MD5 is unavailable", exception);
        }
    }
}
