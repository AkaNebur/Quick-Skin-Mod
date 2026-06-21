package com.quickskin.mod.e2e;

/** Minimal tagged logging so harness output is greppable in the game log ({@code [QS-E2E]}). */
public final class E2ELog {
    private E2ELog() {}

    public static void info(String msg) { System.out.println("[QS-E2E] " + msg); }
    public static void warn(String msg) { System.out.println("[QS-E2E][WARN] " + msg); }
    public static void error(String msg, Throwable t) {
        System.out.println("[QS-E2E][ERROR] " + msg);
        if (t != null) t.printStackTrace(System.out);
    }
}
