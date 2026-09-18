package com.closetos.platform.api;

public class DomainException extends RuntimeException {
    private final int status;
    private final String code;

    public DomainException(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static DomainException notFound(String resource) {
        return new DomainException(404, "NOT_FOUND", resource + " was not found.");
    }

    public static DomainException conflict() {
        return new DomainException(
                409, "CONFLICT", "This item changed. Refresh before saving again.");
    }

    public static DomainException invalid(String detail) {
        return new DomainException(400, "VALIDATION", detail);
    }
}
