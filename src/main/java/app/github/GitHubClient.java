package app.github;

import app.core.Config;
import app.core.Http;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class GitHubClient {
    private final Config cfg;
    private final Http http;
    private final ObjectMapper om = new ObjectMapper();

    public GitHubClient(Config cfg, Http http) {
        this.cfg = cfg; this.http = http;
    }

    public List<JsonNode> listOrgRepos(String org) throws Exception {
        var items = new ArrayList<JsonNode>();
        int page = 1;
        while (true) {
            String url = "%s/orgs/%s/repos?type=public&per_page=%d&page=%d"
                    .formatted(cfg.baseUrl(), org, cfg.perPage(), page);
            try (Response r = http.get(url)) {
                var arr = om.readTree(r.body().byteStream());
                if (!arr.isArray() || arr.isEmpty()) break;
                arr.forEach(items::add);
            }
            page++;
        }
        return items;
    }

    public List<JsonNode> listWorkflowDir(String org, String repo) throws Exception {
        String url = "%s/repos/%s/%s/contents/%s"
                .formatted(cfg.baseUrl(), org, repo, encode(".github/workflows"));
        try (Response r = http.get(url)) {
            var arr = om.readTree(r.body().byteStream());
            var res = new ArrayList<JsonNode>();
            if (arr.isArray()) arr.forEach(res::add);
            return res;
        }
    }

    public List<JsonNode> listCommitsForPath(String org, String repo, String path, String branch) throws Exception {
        var items = new ArrayList<JsonNode>();
        int page = 1;
        while (true) {
            String url = "%s/repos/%s/%s/commits?path=%s&sha=%s&per_page=%d&page=%d"
                    .formatted(cfg.baseUrl(), org, repo, encode(path), encode(branch), cfg.perPage(), page);
            try (Response r = http.get(url)) {
                var arr = om.readTree(r.body().byteStream());
                if (!arr.isArray() || arr.isEmpty()) break;
                arr.forEach(items::add);
            }
            page++;
        }
        return items;
    }

    public JsonNode getFileAtCommit(String org, String repo, String path, String ref) throws Exception {
        String url = "%s/repos/%s/%s/contents/%s?ref=%s"
                .formatted(cfg.baseUrl(), org, repo, encode(path), encode(ref));
        try (Response r = http.get(url)) {
            return new ObjectMapper().readTree(r.body().byteStream());
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
