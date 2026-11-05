package io.duckyplayer;

import java.util.ArrayList;
import java.util.List;

public record ReplayData(
        int mode,
        int gameVersion,
        String beatmapHash,
        String playerName,
        int mods,
        List<ReplayFrame> frames) {

    public static final int MOD_HARD_ROCK = 1 << 4;
    public static final int MOD_DOUBLE_TIME = 1 << 6;
    public static final int MOD_RELAX = 1 << 7;
    public static final int MOD_HALF_TIME = 1 << 8;
    public static final int MOD_NIGHTCORE = 1 << 9;
    public static final int MOD_HIDDEN = 1 << 3;
    public static final int MOD_SPUN_OUT = 1 << 12;

    public boolean hasEasy() {
        return (mods & (1 << 1)) != 0;
    }

    public boolean hasHardRock() {
        return (mods & MOD_HARD_ROCK) != 0;
    }

    public boolean hasHidden() {
        return (mods & MOD_HIDDEN) != 0;
    }

    public boolean hasRelax() {
        return (mods & MOD_RELAX) != 0;
    }

    public boolean hasSpunOut() {
        return (mods & MOD_SPUN_OUT) != 0;
    }

    public boolean hasDoubleTime() {
        return (mods & (MOD_DOUBLE_TIME | MOD_NIGHTCORE)) != 0;
    }

    public double clockRate() {
        if (hasDoubleTime()) return 1.5;
        if ((mods & MOD_HALF_TIME) != 0) return 0.75;
        return 1.0;
    }

    public String modSummary() {
        List<String> names = new ArrayList<>();
        addMod(names, 0, "NF");
        addMod(names, 1, "EZ");
        addMod(names, 2, "TD");
        addMod(names, 3, "HD");
        addMod(names, 4, "HR");
        if ((mods & (1 << 14)) == 0) addMod(names, 5, "SD");
        if ((mods & MOD_NIGHTCORE) == 0) addMod(names, 6, "DT");
        addMod(names, 7, "RX");
        addMod(names, 8, "HT");
        addMod(names, 9, "NC");
        addMod(names, 10, "FL");
        addMod(names, 11, "AT");
        addMod(names, 12, "SO");
        addMod(names, 13, "AP");
        addMod(names, 14, "PF");
        return names.isEmpty() ? "NM" : String.join(" + ", names);
    }

    private void addMod(List<String> names, int bit, String name) {
        if ((mods & (1 << bit)) != 0) names.add(name);
    }
}
