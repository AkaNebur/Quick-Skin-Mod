package com.quickskin.mod.e2e;

import java.util.function.BooleanSupplier;

/**
 * One step of an E2E scenario, driven by the tick state machine.
 *
 * <p>Lifecycle per step: run {@link #action} once -> poll {@link #ready} every tick (but at least
 * {@link #minTicks} ticks after the action so a frame renders with the new state) -> capture
 * {@link #screenshot} (if set) -> run {@link #assertion} -> advance. If {@code ready} never becomes
 * true within {@link #timeoutTicks}, the step fails with a timeout.</p>
 *
 * <p>All callbacks run on the client/render thread (the tick listener), so service and GL calls are
 * safe.</p>
 */
public final class Step {

    /** Result of a step assertion. */
    public record Result(boolean pass, String message) {
        public static Result pass(String message) { return new Result(true, message); }
        public static Result fail(String message) { return new Result(false, message); }
    }

    /** Assertion callback; may throw (a thrown exception is recorded as a failure). */
    @FunctionalInterface
    public interface Check { Result run() throws Exception; }

    final String name;
    Runnable action;                 // nullable: nothing to do
    BooleanSupplier ready;           // nullable: ready immediately (after minTicks)
    int minTicks = 5;                // wait at least this many ticks after the action
    int timeoutTicks = 200;          // ~10s at 20 tps
    String screenshot;               // nullable: no capture
    Check assertion;                 // nullable: no assertion (records pass)

    private Step(String name) { this.name = name; }

    public static Step of(String name) { return new Step(name); }

    public Step action(Runnable action) { this.action = action; return this; }
    public Step ready(BooleanSupplier ready) { this.ready = ready; return this; }
    public Step minTicks(int t) { this.minTicks = t; return this; }
    public Step timeoutTicks(int t) { this.timeoutTicks = t; return this; }
    public Step screenshot(String name) { this.screenshot = name; return this; }
    public Step assertion(Check check) { this.assertion = check; return this; }
}
