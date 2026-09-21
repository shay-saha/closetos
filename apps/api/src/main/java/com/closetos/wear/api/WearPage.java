package com.closetos.wear.api;

import java.util.List;

public record WearPage(List<WearDetails> items, String nextCursor) {}
