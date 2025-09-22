package app.core;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

class HttpTest {

    private MockWebServer mockServer;
    private Http http;
    private Config config;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
        config = new Config(
                "test-token-123",
                baseUrl,
                "test-data",
                2, 10, 30, 10, 30, 5, 10, 100, 50, 100
        );
        http = new Http(config);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void shouldMakeSuccessfulGetRequest() throws Exception {
        // Given
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"message\": \"success\"}")
                .addHeader("Content-Type", "application/json"));

        String url = mockServer.url("/test").toString();

        // When
        try (Response response = http.get(url)) {
            // Then
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("{\"message\": \"success\"}");

            RecordedRequest request = mockServer.takeRequest();
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-token-123");
            assertThat(request.getHeader("Accept")).isEqualTo("application/vnd.github+json");
            assertThat(request.getHeader("X-GitHub-Api-Version")).isEqualTo("2022-11-28");
        }
    }

    @Test
    void shouldRetryOnServerError() throws Exception {
        // Given - first request fails with 500, second succeeds
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"message\": \"success after retry\"}"));

        String url = mockServer.url("/test").toString();

        // When
        try (Response response = http.get(url)) {
            // Then
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("{\"message\": \"success after retry\"}");

            // Verify both requests were made
            assertThat(mockServer.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void shouldHandleRateLimitWithRetryAfter() throws Exception {
        // Given - rate limit response with reset time
        long resetTime = (System.currentTimeMillis() / 1000) + 1; // 1 second from now
        mockServer.enqueue(new MockResponse()
                .setResponseCode(403)
                .addHeader("X-RateLimit-Remaining", "0")
                .addHeader("X-RateLimit-Reset", String.valueOf(resetTime)));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"message\": \"success after rate limit\"}"));

        String url = mockServer.url("/test").toString();

        // When
        long startTime = System.currentTimeMillis();
        try (Response response = http.get(url)) {
            long endTime = System.currentTimeMillis();

            // Then
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("{\"message\": \"success after rate limit\"}");
            
            // Should have waited at least some time for rate limit
            assertThat(endTime - startTime).isGreaterThan(100); // at least 100ms
            assertThat(mockServer.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void shouldThrowExceptionAfterMaxRetries() throws Exception {
        // Given - all requests fail with 500
        for (int i = 0; i < 7; i++) { // More than max retries (6)
            mockServer.enqueue(new MockResponse().setResponseCode(500));
        }

        String url = mockServer.url("/test").toString();

        // When & Then
        assertThatThrownBy(() -> http.get(url))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Exhausted retries");

        // Should have made exactly 6 attempts
        assertThat(mockServer.getRequestCount()).isEqualTo(6);
    }

    @Test
    void shouldRetryOnGenericRateLimit() throws Exception {
        // Given - generic rate limit response
        mockServer.enqueue(new MockResponse()
                .setResponseCode(403)
                .addHeader("X-RateLimit-Used", "true"));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"message\": \"success after generic rate limit\"}"));

        String url = mockServer.url("/test").toString();

        // When
        try (Response response = http.get(url)) {
            // Then
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("{\"message\": \"success after generic rate limit\"}");
            assertThat(mockServer.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void shouldNotRetryOnClientError() throws Exception {
        // Given - 404 error (client error, should not retry)
        mockServer.enqueue(new MockResponse().setResponseCode(404));

        String url = mockServer.url("/test").toString();

        // When
        try (Response response = http.get(url)) {
            // Then
            assertThat(response.code()).isEqualTo(404);
            assertThat(mockServer.getRequestCount()).isEqualTo(1); // No retries
        }
    }

    @Test
    void shouldConfigureTimeouts() {
        // Given
        Config customConfig = new Config(
                "token", "http://example.com", "data",
                1, 1, 5, 2, 3, 1, 1, 1, 1, 1
        );

        // When
        Http customHttp = new Http(customConfig);

        // Then - verify that Http was created without throwing exceptions
        // (timeout configuration is internal to OkHttp client)
        assertThat(customHttp).isNotNull();
    }
}
