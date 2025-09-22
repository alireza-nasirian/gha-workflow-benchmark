
package app.github;

import app.core.Config;
import app.core.Http;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GitHubClientTest {

    private MockWebServer mockServer;
    private GitHubClient gitHubClient;
    private Config config;
    private Http http;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
        config = new Config(
                "test-token-123", baseUrl, "test-data",
                2, 10, 30, 10, 30, 5, 10, 100, 50, 100
        );
        http = new Http(config);
        gitHubClient = new GitHubClient(config, http);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void shouldListOrganizationRepositories() throws Exception {
        // Given
        String org = "testorg";
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[{\"name\": \"repo1\", \"id\": 1}, {\"name\": \"repo2\", \"id\": 2}]")
                .addHeader("Content-Type", "application/json"));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[]")
                .addHeader("Content-Type", "application/json"));

        // When
        List<JsonNode> repos = gitHubClient.listOrgRepos(org);

        // Then
        assertThat(repos).hasSize(2);
        assertThat(repos.get(0).get("name").asText()).isEqualTo("repo1");
        assertThat(repos.get(1).get("name").asText()).isEqualTo("repo2");

        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).startsWith("/orgs/testorg/repos?type=public&per_page=10&page=1");
    }

    @Test
    void shouldHandlePaginationForRepositories() throws Exception {
        // Given
        String org = "testorg";
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[{\"name\": \"repo1\", \"id\": 1}]"));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[{\"name\": \"repo2\", \"id\": 2}]"));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[]")); // Empty response to end pagination

        // When
        List<JsonNode> repos = gitHubClient.listOrgRepos(org);

        // Then
        assertThat(repos).hasSize(2);
        assertThat(mockServer.getRequestCount()).isEqualTo(3); // Two pages + empty page
    }

    @Test
    void shouldListWorkflowDirectory() throws Exception {
        // Given
        String org = "testorg";
        String repo = "testrepo";
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[{\"name\": \"ci.yml\", \"path\": \".github/workflows/ci.yml\", \"type\": \"file\"}]")
                .addHeader("Content-Type", "application/json"));

        // When
        List<JsonNode> workflows = gitHubClient.listWorkflowDir(org, repo);

        // Then
        assertThat(workflows).hasSize(1);
        assertThat(workflows.get(0).get("name").asText()).isEqualTo("ci.yml");

        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).contains("/repos/testorg/testrepo/contents/.github%2Fworkflows");
    }

    @Test
    void shouldReturnEmptyListWhenWorkflowDirectoryNotFound() throws Exception {
        // Given
        String org = "testorg";
        String repo = "testrepo";
        mockServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"message\": \"Not Found\"}"));

        // When
        List<JsonNode> workflows = gitHubClient.listWorkflowDir(org, repo);

        // Then - should return empty list for 404 (directory not found)
        assertThat(workflows).isEmpty();
    }

    @Test
    void shouldListCommitsForPath() throws Exception {
        // Given
        String org = "testorg";
        String repo = "testrepo";
        String path = ".github/workflows/ci.yml";
        String branch = "main";
        
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[{\"sha\": \"abc123\", \"commit\": {\"message\": \"Add CI\"}}]"));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[]")); // End pagination

        // When
        List<JsonNode> commits = gitHubClient.listCommitsForPath(org, repo, path, branch);

        // Then
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).get("sha").asText()).isEqualTo("abc123");

        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).contains("/repos/testorg/testrepo/commits");
        assertThat(request.getPath()).contains("path=.github%2Fworkflows%2Fci.yml");
        assertThat(request.getPath()).contains("sha=main");
    }

    @Test
    void shouldGetFileAtCommit() throws Exception {
        // Given
        String org = "testorg";
        String repo = "testrepo";
        String path = ".github/workflows/ci.yml";
        String ref = "abc123";
        
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"name\": \"ci.yml\", \"content\": \"bmFtZTogQ0k=\", \"encoding\": \"base64\"}"));

        // When
        JsonNode file = gitHubClient.getFileAtCommit(org, repo, path, ref);

        // Then
        assertThat(file.get("name").asText()).isEqualTo("ci.yml");
        assertThat(file.get("encoding").asText()).isEqualTo("base64");

        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).contains("/repos/testorg/testrepo/contents/.github%2Fworkflows%2Fci.yml");
        assertThat(request.getPath()).contains("ref=abc123");
    }

    @Test
    void shouldHandleSpecialCharactersInPaths() throws Exception {
        // Given
        String org = "test-org";
        String repo = "test repo";
        String path = ".github/workflows/ci test.yml";
        String branch = "feature/test-branch";
        
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[]"));

        // When
        gitHubClient.listCommitsForPath(org, repo, path, branch);

        // Then
        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).contains("test-org");
        assertThat(request.getPath()).contains("test%20repo");
        // URL encoding may use + for spaces in query parameters
        assertThat(request.getPath()).containsAnyOf(".github%2Fworkflows%2Fci%20test.yml", ".github%2Fworkflows%2Fci+test.yml");
        assertThat(request.getPath()).contains("feature%2Ftest-branch");
    }

    @Test
    void shouldHandleEmptyArrayResponse() throws Exception {
        // Given
        String org = "testorg";
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[]"));

        // When
        List<JsonNode> repos = gitHubClient.listOrgRepos(org);

        // Then
        assertThat(repos).isEmpty();
    }

    @Test
    void shouldHandleNonArrayResponse() throws Exception {
        // Given
        String org = "testorg";
        String repo = "testrepo";
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"message\": \"Not a directory\"}"));

        // When
        List<JsonNode> workflows = gitHubClient.listWorkflowDir(org, repo);

        // Then
        assertThat(workflows).isEmpty();
    }
}
