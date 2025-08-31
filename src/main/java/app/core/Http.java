package app.core;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class Http {
    private static final Logger log = LoggerFactory.getLogger(Http.class);
    private final OkHttpClient client;
    private final Config cfg;

    public Http(Config cfg) {
        this.cfg = cfg;
        this.client = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(60))
                .connectTimeout(Duration.ofSeconds(20))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    public Response get(String url) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + cfg.token())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build();

        for (int attempt = 1; attempt <= 6; attempt++) {
            Response resp = client.newCall(req).execute();
            if (resp.code() == 403 && resp.header("X-RateLimit-Remaining", "1").equals("0")) {
                long reset = Long.parseLong(resp.header("X-RateLimit-Reset", "0"));
                long waitMs = Math.max(0, (reset * 1000) - System.currentTimeMillis()) + 1000;
                log.warn("Primary rate limit hit. Sleeping {} ms", waitMs);
                Thread.sleep(waitMs);
                resp.close();
                continue;
            }
            if (resp.code() == 403 && "true".equals(resp.header("X-RateLimit-Used"))) { // vague; fallback
                Thread.sleep(1000L * attempt);
                resp.close();
                continue;
            }
            if (resp.code() >= 500) {
                Thread.sleep(500L * attempt);
                resp.close();
                continue;
            }
            return resp; // caller must close
        }
        throw new RuntimeException("Exhausted retries for " + url);
    }
}
