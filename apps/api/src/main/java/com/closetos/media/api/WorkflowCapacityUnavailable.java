package com.closetos.media.api;

public final class WorkflowCapacityUnavailable extends RuntimeException {
    public WorkflowCapacityUnavailable() {
        super("Media processing capacity is currently occupied.");
    }
}
