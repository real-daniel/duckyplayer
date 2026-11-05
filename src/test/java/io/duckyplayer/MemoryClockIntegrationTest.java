package io.duckyplayer;

/** Opt-in live bridge smoke test. Does not construct an input sender or inject anything. */
public final class MemoryClockIntegrationTest {
    private MemoryClockIntegrationTest() { }
    public static void main(String[] args) throws Exception {
        try (var clock = new MemoryClock(Long.parseLong(args[0]))) {
            clock.awaitReady();
            for (int i = 0; i < 5; i++) {
                System.out.println(clock.sample());
                Thread.sleep(20);
            }
        }
    }
}
