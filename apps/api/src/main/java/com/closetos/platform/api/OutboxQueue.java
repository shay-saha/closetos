package com.closetos.platform.api;

import java.util.List;

public interface OutboxQueue {
    List<OutboxEntry> claim();

    void published(OutboxEntry event);

    void failed(OutboxEntry event, String detail, boolean permanent);
}
