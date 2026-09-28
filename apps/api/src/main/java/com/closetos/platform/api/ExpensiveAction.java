package com.closetos.platform.api;

public enum ExpensiveAction {
    PHOTO_UPLOAD("photo upload"),
    PROCESSING_RETRY("photo processing retry"),
    SEMANTIC_QUERY("semantic search"),
    GARMENT_EMBEDDING("wardrobe indexing"),
    DATA_EXPORT("data download");

    private final String description;

    ExpensiveAction(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
