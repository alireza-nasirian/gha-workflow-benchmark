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
class WorkflowsTest {

    @Mock
    private GitHubClient gitHubClient;

    private Workflows workflows;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        workflows = new Workflows(gitHubClient);
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldListWorkflowsFromRepository() throws Exception {
        // Given
        String org = "testorg";
        String repo = "testrepo";
        JsonNode workflow1 = objectMapper.readTree("{\"name\": \"ci.yml\", \"path\": \".github/workflows/ci.yml\"}");
        JsonNode workflow2 = objectMapper.readTree("{\"name\": \"deploy.yaml\", \"path\": \".github/workflows/deploy.yaml\"}");
        List<JsonNode> expectedWorkflows = List.of(workflow1, workflow2);

        when(gitHubClient.listWorkflowDir(org, repo)).thenReturn(expectedWorkflows);

        // When
        List<JsonNode> actualWorkflows = workflows.list(org, repo);

        // Then
        assertThat(actualWorkflows).hasSize(2);
        assertThat(actualWorkflows).isEqualTo(expectedWorkflows);
        verify(gitHubClient).listWorkflowDir(org, repo);
    }

    @Test
    void shouldReturnEmptyListWhenNoWorkflows() throws Exception {
        // Given
        String org = "testorg";
        String repo = "emptyrepo";
        when(gitHubClient.listWorkflowDir(org, repo)).thenReturn(List.of());

        // When
        List<JsonNode> actualWorkflows = workflows.list(org, repo);

        // Then
        assertThat(actualWorkflows).isEmpty();
        verify(gitHubClient).listWorkflowDir(org, repo);
    }

    @Test
    void shouldIdentifyYmlFilesAsYaml() throws Exception {
        // Given
        JsonNode ymlFile = objectMapper.readTree("{\"name\": \"ci.yml\", \"type\": \"file\"}");

        // When
        boolean isYaml = Workflows.isYaml(ymlFile);

        // Then
        assertThat(isYaml).isTrue();
    }

    @Test
    void shouldIdentifyYamlFilesAsYaml() throws Exception {
        // Given
        JsonNode yamlFile = objectMapper.readTree("{\"name\": \"deploy.yaml\", \"type\": \"file\"}");

        // When
        boolean isYaml = Workflows.isYaml(yamlFile);

        // Then
        assertThat(isYaml).isTrue();
    }

    @Test
    void shouldNotIdentifyNonYamlFilesAsYaml() throws Exception {
        // Given
        JsonNode txtFile = objectMapper.readTree("{\"name\": \"README.txt\", \"type\": \"file\"}");
        JsonNode jsFile = objectMapper.readTree("{\"name\": \"script.js\", \"type\": \"file\"}");
        JsonNode noExtFile = objectMapper.readTree("{\"name\": \"Dockerfile\", \"type\": \"file\"}");

        // When & Then
        assertThat(Workflows.isYaml(txtFile)).isFalse();
        assertThat(Workflows.isYaml(jsFile)).isFalse();
        assertThat(Workflows.isYaml(noExtFile)).isFalse();
    }

    @Test
    void shouldHandleEmptyFileName() throws Exception {
        // Given
        JsonNode emptyNameFile = objectMapper.readTree("{\"name\": \"\", \"type\": \"file\"}");

        // When
        boolean isYaml = Workflows.isYaml(emptyNameFile);

        // Then
        assertThat(isYaml).isFalse();
    }

    @Test
    void shouldExtractPathFromWorkflowNode() throws Exception {
        // Given
        JsonNode workflowNode = objectMapper.readTree(
                "{\"name\": \"ci.yml\", \"path\": \".github/workflows/ci.yml\", \"type\": \"file\"}"
        );

        // When
        String path = Workflows.path(workflowNode);

        // Then
        assertThat(path).isEqualTo(".github/workflows/ci.yml");
    }

    @Test
    void shouldReturnEmptyStringWhenPathMissing() throws Exception {
        // Given
        JsonNode nodeWithoutPath = objectMapper.readTree("{\"name\": \"ci.yml\", \"type\": \"file\"}");

        // When
        String path = Workflows.path(nodeWithoutPath);

        // Then
        assertThat(path).isEmpty();
    }

    @Test
    void shouldPropagateExceptionFromGitHubClient() throws Exception {
        // Given
        String org = "testorg";
        String repo = "failrepo";
        Exception expectedException = new RuntimeException("GitHub API error");
        when(gitHubClient.listWorkflowDir(org, repo)).thenThrow(expectedException);

        // When & Then
        assertThatThrownBy(() -> workflows.list(org, repo))
                .isEqualTo(expectedException);
        verify(gitHubClient).listWorkflowDir(org, repo);
    }
}
