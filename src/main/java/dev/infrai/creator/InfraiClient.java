package dev.infrai.creator;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class InfraiClient implements CreatorOnboardingService.Gateway {
    private final InfraiConfig config;
    private final HttpClient http;

    public InfraiClient(InfraiConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build());
    }

    InfraiClient(InfraiConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    @Override public String createUser(String email, String name, String signupChannel, String processingState, String requestId) {
        Map<String, Object> data = call("POST", "/v1/auth/user/create", Json.object(Map.of(
                "email", email, "name", name,
                "metadata", Map.of("signup_channel", signupChannel, "asset_processing_state", processingState),
                "idempotency_key", requestId)), requestId);
        Object value = data.containsKey("user_id") ? data.get("user_id") : data.get("id");
        if (!(value instanceof String userId) || userId.isBlank()) throw new IllegalStateException("Missing created user identifier");
        return userId;
    }

    @Override public boolean emailSuppressed(String email) {
        Map<String, Object> data = call("GET", "/v1/email/suppression/check/" + segment(email), null, null);
        return Boolean.TRUE.equals(data.get("suppressed"));
    }

    @Override public String sendEmail(String email, String subject, String html, String requestId) {
        Map<String, Object> data = call("POST", "/v1/email/send",
                Json.object(Map.of("to", email, "subject", subject, "html", html)), requestId);
        return required(data, "message_id");
    }

    @Override public boolean smsSuppressed(String phone) {
        Map<String, Object> data = call("POST", "/v1/sms/suppression/check",
                Json.object(Map.of("phone", phone)), null);
        return Boolean.TRUE.equals(data.get("suppressed"));
    }

    @Override public String sendSms(String phone, String message, String requestId) {
        Map<String, Object> data = call("POST", "/v1/sms/send",
                Json.object(Map.of("to", phone, "body", message)), requestId);
        return required(data, "message_id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, String path, String body, String idempotencyKey) {
        for (int attempt = 0; attempt < config.maxAttempts(); attempt++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(resolve(path))
                    .timeout(config.requestTimeout())
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Accept", "application/json");
            if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));

            HttpResponse<String> response;
            try {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new IllegalStateException("Infrai transport error", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Infrai request interrupted", e);
            }

            Object decoded = Json.parse(response.body());
            if (!(decoded instanceof Map<?, ?> raw)) throw new IllegalStateException("Response is not an envelope");
            Map<String, Object> envelope = (Map<String, Object>) raw;
            if (response.statusCode() == 429 && attempt + 1 < config.maxAttempts()) {
                pause(retryDelay(response, attempt));
                continue;
            }
            if (!Boolean.TRUE.equals(envelope.get("ok"))) {
                Map<String, Object> error = envelope.get("error") instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : Map.of();
                throw new InfraiException(String.valueOf(error.getOrDefault("code", "REQUEST_REJECTED")), response.statusCode(), error);
            }
            if (response.statusCode() >= 500) throw new IllegalStateException("Unexpected HTTP status: " + response.statusCode());
            return envelope.get("data") instanceof Map<?, ?> data ? (Map<String, Object>) data : Map.of();
        }
        throw new IllegalStateException("Retry budget exhausted");
    }

    private URI resolve(String path) {
        String root = config.baseUrl().toString().replaceAll("/+$", "");
        return URI.create(root + path);
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String required(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalStateException("Missing response field: " + key);
        return text;
    }

    private static Duration retryDelay(HttpResponse<?> response, int attempt) {
        String value = response.headers().firstValue("Retry-After").orElse("");
        try { return Duration.ofSeconds(Math.max(1, Long.parseLong(value))); }
        catch (NumberFormatException ignored) { return Duration.ofMillis(250L * (1L << attempt)); }
    }

    private static void pause(Duration duration) {
        try { Thread.sleep(duration.toMillis()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Retry interrupted", e); }
    }
}
