package app.core;

import java.io.InputStream;
import java.util.Properties;

public record Config(
        String token,
        String baseUrl,
        String outDir,
        int maxWorkers,
        int perPage,
        int httpCallTimeoutSeconds,
        int httpConnectTimeoutSeconds,
        int httpReadTimeoutSeconds,
        int taskPollTimeoutSeconds,
        int taskTimeoutDefaultMinutes,
        int httpRetryBackoffBaseMs,
        int httpRetryServerErrorBaseMs,
        int httpRateLimitBufferMs
) {
    public static Config load() {
        try (InputStream in = Config.class.getClassLoader().getResourceAsStream("application.properties")) {
            Properties props = new Properties();
            props.load(in);

            var token = System.getenv().getOrDefault(
                    "s", props.getProperty("github.token", "")
            );
            if (token.isBlank()) {
                throw new IllegalStateException("GitHub token required (env GITHUB_TOKEN or github.token in properties).");
            }

            return new Config(
                    token,
                    props.getProperty("github.baseUrl", "https://api.github.com"),
                    props.getProperty("out.dir", "data"),
                    Integer.parseInt(props.getProperty("max.workers", "12")),
                    Integer.parseInt(props.getProperty("per.page", "100")),
                    Integer.parseInt(props.getProperty("http.call.timeout", "60")),
                    Integer.parseInt(props.getProperty("http.connect.timeout", "20")),
                    Integer.parseInt(props.getProperty("http.read.timeout", "60")),
                    Integer.parseInt(props.getProperty("task.poll.timeout.seconds", "15")),
                    Integer.parseInt(props.getProperty("task.timeout.default.minutes", "120")),
                    Integer.parseInt(props.getProperty("http.retry.backoff.base.ms", "1000")),
                    Integer.parseInt(props.getProperty("http.retry.server.error.base.ms", "500")),
                    Integer.parseInt(props.getProperty("http.ratelimit.buffer.ms", "1000"))
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to load application.properties", e);
        }
    }
}
