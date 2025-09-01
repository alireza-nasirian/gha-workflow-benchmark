package app.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class GitSupport {

    /** Clone if missing, otherwise open existing repo. */
    public static Git cloneOrOpen(Path workdir, String httpsUrl, String token) throws Exception {
        // For a bare repo, the repo directory itself is the .git dir (no working tree)
        if (Files.exists(workdir.resolve("HEAD"))) {          // simple bare-repo heuristic
            return Git.open(workdir.toFile());
        }

        CredentialsProvider cp = (token == null || token.isBlank())
                ? CredentialsProvider.getDefault()
                : new UsernamePasswordCredentialsProvider(token, ""); // GitHub: token as password

        // Bare clone avoids checking out files with Windows-illegal names
        return Git.cloneRepository()
                .setDirectory(workdir.toFile())
                .setURI(httpsUrl)
                .setCredentialsProvider(cp)
                .setBare(true)           // << key: no working tree is created
                // .setNoCheckout(true)  // (alternative if you keep non-bare; bare is simpler/safer here)
                .call();
    }

    /** List workflow files under .github/workflows at HEAD. */
    public static List<String> listWorkflowFilesAtHead(Git git) throws IOException {
        var repo = git.getRepository();
        var headId = repo.resolve("HEAD^{tree}");
        if (headId == null) return List.of();

        List<String> result = new ArrayList<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(headId);
            tw.setRecursive(true);
            tw.setFilter(PathFilter.create(".github/workflows"));
            while (tw.next()) {
                String path = tw.getPathString();
                if (path.startsWith(".github/workflows/")
                        && (path.endsWith(".yml") || path.endsWith(".yaml"))) {
                    result.add(path);
                }
            }
        }
        return result;
    }

    /** All commits that touched a given path (newest first). */
    public static List<RevCommit> commitsForPath(Git git, String path) throws Exception {
        LogCommand log = git.log().addPath(path);
        List<RevCommit> commits = new ArrayList<>();
        for (RevCommit c : log.call()) commits.add(c);
        return commits;
    }

    /** Read file contents at a given commit; returns null if file missing at that commit. */
    public static String readFileAtCommit(Git git, String path, RevCommit commit) throws Exception {
        var repo = git.getRepository();
        var tree = commit.getTree();

        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(tree);
            tw.setRecursive(true);
            tw.setFilter(PathFilter.create(path));
            if (!tw.next()) return null; // file not present in this commit

            var blobId = tw.getObjectId(0);
            try (ObjectReader reader = repo.newObjectReader()) {
                var loader = reader.open(blobId);
                return new String(loader.getBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
