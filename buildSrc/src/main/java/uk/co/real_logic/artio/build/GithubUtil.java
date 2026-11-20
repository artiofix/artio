package uk.co.real_logic.artio.build;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;

// Only used from build.gradle, which CodeQL doesn't analyse.
// codeql[java/unused-reference-type]
final class GithubUtil
{
    private GithubUtil()
    {
    }

    static String currentGitHash(final String projectDir) throws IOException, GitAPIException
    {
        final FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder().findGitDir(new File(projectDir));
        if (repositoryBuilder.getGitDir() == null)
        {
            // No .git directory. That will be the case when people download the repo as a zip file.
            return "unknown";
        }

        try (Repository repository = repositoryBuilder.build();
             ObjectReader reader = repository.newObjectReader();
             Git git = new Git(repository))
        {
            final RevCommit commit = git.log().setMaxCount(1).call().iterator().next();
            final ObjectId commitId = commit.toObjectId();
            final String commitSha = reader.abbreviate(commitId, 10).name();
            final Status status = git.status().call();

            return status.hasUncommittedChanges() ? commitSha + "+guilty" : commitSha;
        }
    }
}