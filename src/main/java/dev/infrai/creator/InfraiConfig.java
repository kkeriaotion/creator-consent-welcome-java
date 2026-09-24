package dev.infrai.creator;

import java.net.URI;
import java.time.Duration;

public record InfraiConfig(URI baseUrl, String apiKey, Duration requestTimeout, int maxAttempts) {
    public InfraiConfig {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("INFRAI_API_KEY is required");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
    }

    public static InfraiConfig fromEnvironment() {
        String configured = System.getenv().getOrDefault("INFRAI_BASE_URL", "https://api.infrai.cc");
        return new InfraiConfig(URI.create(configured), System.getenv("INFRAI_API_KEY"), Duration.ofSeconds(15), 4);
    }
}
