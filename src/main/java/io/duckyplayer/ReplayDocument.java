package io.duckyplayer;

import java.nio.file.Path;

record ReplayDocument(
        Path source,
        ReplayData data,
        byte[] header,
        byte[] footer,
        String terminalRecord) {
}
