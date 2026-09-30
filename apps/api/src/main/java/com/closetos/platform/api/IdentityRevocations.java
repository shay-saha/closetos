package com.closetos.platform.api;

public interface IdentityRevocations {
    boolean revoked(String subject);
}
