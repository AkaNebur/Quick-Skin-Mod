package com.quickskin.mod.common.data;

public enum SkinSortMode {
    LATEST_LAST("Latest Last", "↓"),
    LATEST_FIRST("Latest First", "↑"),
    ALPHABETICAL("Alphabetical", "ABC");

    private final String displayName;
    private final String icon;

    SkinSortMode(String displayName, String icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getIcon() {
        return icon;
    }

    public SkinSortMode next() {
        SkinSortMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
