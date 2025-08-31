package app.logic;

import app.github.GitHubClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public class Repos {
    private final GitHubClient gh;
    public Repos(GitHubClient gh) { this.gh = gh; }

    public List<JsonNode> list(String org) throws Exception {
        return gh.listOrgRepos(org);
    }

    public static String defaultBranch(JsonNode repo) {
        return repo.path("default_branch").asText("main");
    }
}
