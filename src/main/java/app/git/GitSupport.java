package app.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FilterSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Git helpers optimized for fast, Windows-safe cloning:
 *  - bare (no working tree) → avoids illegal filename issues
 *  - single branch (default) → small
 *  - no tags → smaller
 *  - partial clone blob:none → fetch blobs lazily (fallback if unsupported)
 */
public class GitSupport {

    /**
     * Clone/open a bare repo at repoDir for the given httpsUrl and defaultBranch.
     * If it already exists (bare repo heuristic: contains HEAD), it is opened.
     */
    public static Git cloneOrOpen(Path repoDir, String httpsUrl, String token, String defaultBranch) throws Exception {
        if (Files.exists(repoDir.resolve("HEAD"))) { // bare-repo heuristic
            return Git.open(repoDir.toFile());
        }
        Files.createDirectories(repoDir);

        CredentialsProvider cp = (token == null || token.isBlank())
                ? CredentialsProvider.getDefault()
                : new UsernamePasswordCredentialsProvider(token, ""); // GitHub: token as password

        String branch = (defaultBranch == null || defaultBranch.isBlank()) ? "main" : defaultBranch;
        String branchRef = "refs/heads/" + branch;

        try {
            // Preferred: partial clone (blob:none) → minimal transfer
            return Git.cloneRepository()
                    .setURI(httpsUrl)
                    .setDirectory(repoDir.toFile())
                    .setBare(true)
                    .setCloneAllBranches(false)
                    .setBranchesToClone(List.of(branchRef))
                    .setBranch(branchRef)
                    .setNoTags()
                    .setCredentialsProvider(cp)
                    .setTimeout(300)
                    .call();

        } catch (TransportException te) {
            // Fallback: some servers may not support partial clone
            return Git.cloneRepository()
                    .setURI(httpsUrl)
                    .setDirectory(repoDir.toFile())
                    .setBare(true)
                    .setCloneAllBranches(false)
                    .setBranchesToClone(List.of(branchRef))
                    .setBranch(branchRef)
                    .setNoTags()
                    .setCredentialsProvider(cp)
                    .setTimeout(300)
                    .call();
        }
    }

    /** Utility to check if a path is a YAML workflow under .github/workflows/ */
    public static boolean isWorkflowPath(String path) {
        return path != null
                && path.startsWith(".github/workflows/")
                && (path.endsWith(".yml") || path.endsWith(".yaml"));
    }
}
