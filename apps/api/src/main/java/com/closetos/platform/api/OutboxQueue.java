package com.closetos.platform.api;

import java.time.Duration;
import java.util.List;
import java.util.Set;

public interface OutboxQueue {
    List<OutboxEntry> claim();

    List<OutboxEntry> claim(Set<String> eventTypes);

    void defer(OutboxEntry event, Duration delay);

    void published(OutboxEntry event);

    void failed(OutboxEntry event, String detail, boolean permanent);
}
