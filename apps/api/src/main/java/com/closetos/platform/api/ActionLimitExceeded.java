package com.closetos.platform.api;

import java.time.Duration;

public final class ActionLimitExceeded extends DomainException {
    private final Duration retryAfter;

    public ActionLimitExceeded(ExpensiveAction action, long retryAfterSeconds) {
        super(429, "ACTION_LIMIT", detail(action, retryAfterSeconds));
        this.retryAfter = Duration.ofSeconds(Math.max(1, retryAfterSeconds));
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    private static String detail(ExpensiveAction action, long seconds) {
        String wait =
                seconds < 60
                        ? duration(Math.max(1, seconds), "second")
                        : seconds < 3600
                                ? duration(Math.ceilDiv(seconds, 60), "minute")
                                : duration(Math.ceilDiv(seconds, 3600), "hour");
        return "You've reached the " + action.description() + " limit. Try again in " + wait + ".";
    }

    private static String duration(long amount, String unit) {
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }
}
