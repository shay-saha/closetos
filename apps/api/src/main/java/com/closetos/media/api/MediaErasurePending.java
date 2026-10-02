package com.closetos.media.api;

public final class MediaErasurePending extends RuntimeException {
    public MediaErasurePending() {
        super("Photo cleanup awaits storage confirmation.");
    }
}
