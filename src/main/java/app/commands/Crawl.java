package app.commands;

import app.core.Config;
import app.core.Http;
import app.github.GitHubClient;
import app.logic.History;
import app.logic.Repos;
import app.logic.Workflows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "crawl", description = "Crawl top orgs and collect workflow histories")
public class Crawl implements Callable<Integer> {

    @CommandLine.Option(names="--orgs-file", required = true, description = "Path to JSON array of org logins")
    Path orgsFile;

    @Override
    public Integer call() throws Exception {
        var cfg = Config.load();
        var http = new Http(cfg);
        var gh = new GitHubClient(cfg, http);
        var repos = new Repos(gh);
        var wfs = new Workflows(gh);
        var history = new History(cfg, gh);

        var orgs = readOrgs(orgsFile);
        for (String org : orgs) {
            var repoList = repos.list(org);
            for (JsonNode r : repoList) {
                if (r.path("archived").asBoolean(false)) continue;
                if (r.path("fork").asBoolean(false)) continue;

                String repo = r.path("name").asText();
                String branch = Repos.defaultBranch(r);

                List<JsonNode> contents;
                try {
                    contents = wfs.list(org, repo);
                } catch (Exception e) {
                    continue; // no workflows dir or 404
                }
                for (JsonNode f : contents) {
                    if (!Workflows.isYaml(f)) continue;
                    String path = f.path("path").asText(); // e.g., ".github/workflows/ci.yml"
                    try {
                        history.collectWorkflowHistory(org, repo, path, branch);
                    } catch (Exception ex) {
                        // log and continue
                    }
                }
            }
        }
        return 0;
    }

    private static List<String> readOrgs(Path p) throws Exception {
        var om = new ObjectMapper();
        var arr = om.readTree(Files.readAllBytes(p));
        return om.convertValue(arr, om.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
