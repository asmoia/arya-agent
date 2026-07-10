// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.tool;

/** Java-friendly event wait for existing Java tools. */
public final class UiWait {
    public interface Condition { boolean test(); }
    private UiWait() {}

    public static boolean until(long timeoutMs, long pollMs, Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 0L);
        while (System.currentTimeMillis() < deadline) {
            if (condition.test()) return true;
            Thread.sleep(Math.max(20L, Math.min(pollMs, 250L)));
        }
        return condition.test();
    }
}
