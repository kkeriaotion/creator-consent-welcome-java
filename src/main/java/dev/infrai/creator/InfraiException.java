package dev.infrai.creator;

import java.util.Map;

public final class InfraiException extends RuntimeException {
    private final String code;
    private final int statusCode;
    private final Map<String, Object> detail;

    InfraiException(String code, int statusCode, Map<String, Object> detail) {
        super(String.valueOf(detail.getOrDefault("message", code)));
        this.code = code;
        this.statusCode = statusCode;
        this.detail = Map.copyOf(detail);
    }

    public String code() { return code; }
    public int statusCode() { return statusCode; }
    public Map<String, Object> detail() { return detail; }
}
