package com.closetos.collection.api;

import java.util.List;

public record CollectionPage(List<CollectionDetails> items, String nextCursor) {}
