package com.closetos.media.api;

public record AssetDescriptor(
        String key, String checksumSha256, long size, int width, int height) {}
