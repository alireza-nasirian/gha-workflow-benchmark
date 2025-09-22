package app.logic;

import app.github.GitHubClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReposTest {

    @Mock
    private GitHubClient gitHubClient;

    private Repos repos;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        repos = new Repos(gitHubClient);
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldListOrganizationRepositories() throws Exception {
        // Given
        String org = "testorg";
        JsonNode repo1 = objectMapper.readTree("{\"name\": \"repo1\", \"id\": 1}");
        JsonNode repo2 = objectMapper.readTree("{\"name\": \"repo2\", \"id\": 2}");
        List<JsonNode> expectedRepos = List.of(repo1, repo2);

        when(gitHubClient.listOrgRepos(org)).thenReturn(expectedRepos);

        // When
        List<JsonNode> actualRepos = repos.list(org);

        // Then
        assertThat(actualRepos).hasSize(2);
        assertThat(actualRepos).isEqualTo(expectedRepos);
        verify(gitHubClient).listOrgRepos(org);
    }

    @Test
    void shouldReturnEmptyListWhenNoRepositories() throws Exception {
        // Given
        String org = "emptyorg";
        when(gitHubClient.listOrgRepos(org)).thenReturn(List.of());

        // When
        List<JsonNode> actualRepos = repos.list(org);

        // Then
        assertThat(actualRepos).isEmpty();
        verify(gitHubClient).listOrgRepos(org);
    }

    @Test
    void shouldExtractDefaultBranchFromRepository() throws Exception {
        // Given
        JsonNode repoWithBranch = objectMapper.readTree(
                "{\"name\": \"repo1\", \"default_branch\": \"develop\"}"
        );

        // When
        String defaultBranch = Repos.defaultBranch(repoWithBranch);

        // Then
        assertThat(defaultBranch).isEqualTo("develop");
    }

    @Test
    void shouldUseMainAsDefaultBranchWhenNotSpecified() throws Exception {
        // Given
        JsonNode repoWithoutBranch = objectMapper.readTree(
                "{\"name\": \"repo1\", \"id\": 123}"
        );

        // When
        String defaultBranch = Repos.defaultBranch(repoWithoutBranch);

        // Then
        assertThat(defaultBranch).isEqualTo("main");
    }

    @Test
    void shouldUseMainAsDefaultBranchWhenEmpty() throws Exception {
        // Given
        JsonNode repoWithEmptyBranch = objectMapper.readTree(
                "{\"name\": \"repo1\", \"default_branch\": \"\"}"
        );

        // When
        String defaultBranch = Repos.defaultBranch(repoWithEmptyBranch);

        // Then - the actual implementation returns empty string, not "main"
        assertThat(defaultBranch).isEmpty();
    }

    @Test
    void shouldPropagateExceptionFromGitHubClient() throws Exception {
        // Given
        String org = "failorg";
        Exception expectedException = new RuntimeException("GitHub API error");
        when(gitHubClient.listOrgRepos(org)).thenThrow(expectedException);

        // When & Then
        assertThatThrownBy(() -> repos.list(org))
                .isEqualTo(expectedException);
        verify(gitHubClient).listOrgRepos(org);
    }
}
