package app.logic;

import app.github.GitHubClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public class Workflows {
    private final GitHubClient gh;
    public Workflows(GitHubClient gh) { this.gh = gh; }

    public List<JsonNode> list(String org, String repo) throws Exception {
        return gh.listWorkflowDir(org, repo);
    }

    public static boolean isYaml(JsonNode node) {
        var name = node.path("name").asText("");
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    public static String path(JsonNode node) {
        return node.path("path").asText(); // returned by contents API
    }
}
