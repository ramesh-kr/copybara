package com.google.copybara.git;

import static com.google.copybara.util.CommandUtil.executeCommand;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.PercentEscaper;
import com.google.copybara.GeneralOptions;
import com.google.copybara.Options;
import com.google.copybara.RepoException;
import com.google.copybara.util.BadExitStatusWithOutputException;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.shell.CommandResult;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A class for manipulating Git repositories
 */
public final class GitRepository {

  private static final Pattern RefNotFoundError =
      Pattern.compile("pathspec '(.+)' did not match any file");

  private static final PercentEscaper PERCENT_ESCAPER = new PercentEscaper(
      "-_", /*plusForSpace=*/ true);

  /**
   * Git tool path. A String on pourpose so that we can use the 'git' on PATH.
   */
  private final String gitExecPath;

  /**
   * The location of the {@code .git} directory. The is also the value of the {@code --git-dir}
   * flag.
   */
  private final Path gitDir;

  /**
   * Url of the repository
   */
  private final String repoUrl;

  private final boolean verbose;

  private GitRepository(String gitExecPath, Path gitDir, String repoUrl, boolean verbose) {
    this.gitExecPath = Preconditions.checkNotNull(gitExecPath);
    this.gitDir = Preconditions.checkNotNull(gitDir);
    this.repoUrl = Preconditions.checkNotNull(repoUrl);
    this.verbose = verbose;
  }

  /**
   * Constructs a new instance which represents a clone of some repository.
   *
   * @param repoUrl the URL of the repository which this one is a clone of
   */
  public static GitRepository withRepoUrl(String repoUrl, Options options) {
    GitOptions gitConfig = options.getOption(GitOptions.class);

    Path gitRepoStorage = FileSystems.getDefault().getPath(gitConfig.gitRepoStorage);
    Path gitDir = gitRepoStorage.resolve(PERCENT_ESCAPER.escape(repoUrl));

    return new GitRepository(
        gitConfig.gitExecutable,
        gitDir,
        repoUrl,
        options.getOption(GeneralOptions.class).isVerbose());
  }

  public String getRepoUrl() {
    return repoUrl;
  }

  /**
   * Creates a worktree with the contents of the ref {@code ref} for the repository {@code repoUrl}
   *
   * <p>Any content in the workdir is removed/overwritten.
   */
  public void checkoutReference(String ref, Path workdir) throws RepoException {
    if (!Files.exists(gitDir)) {
      try {
        Files.createDirectories(gitDir);
      } catch (IOException e) {
        throw new RepoException("Cannot create git directory '" + gitDir + "': " + e.getMessage(), e);
      }

      git(gitDir, "init", "--bare");
      git(gitDir, "remote", "add", "origin", repoUrl);
    }

    git(gitDir, "fetch", "-f", "origin");
    // We don't allow creating local branches tracking remotes. This doesn't work without
    // merging.
    checkRefExists(ref);
    git(workdir, "--git-dir=" + gitDir, "--work-tree=" + workdir, "checkout", "-f", ref);
  }

  private void checkRefExists(String ref) throws RepoException {
    try {
      git(gitDir, "rev-parse", "--verify", ref);
    } catch (RepoException e) {
      if (e.getMessage().contains("Needed a single revision")) {
        throw new RepoException("Ref '" + ref + "' does not exist."
            + " If you used a ref like 'master' you should be using 'origin/master' instead");
      }
      throw e;
    }
  }

  /**
   * Invokes {@code git} in the directory given by {@code cwd} against this repository.
   *
   * @param cwd the directory in which to execute the command
   * @param params the argv to pass to Git, excluding the initial {@code git}
   */
  public CommandResult git(Path cwd, String... params) throws RepoException {
    String[] cmd = new String[params.length + 1];
    cmd[0] = gitExecPath;
    System.arraycopy(params, 0, cmd, 1, params.length);
    try {
      CommandResult result = executeCommand(
          new Command(cmd, ImmutableMap.<String, String>of(), cwd.toFile()), verbose);
      if (result.getTerminationStatus().success()) {
        return result;
      }
      throw new RepoException("Error on git command: " + new String(result.getStderr()));
    } catch (BadExitStatusWithOutputException e) {
      Matcher matcher = RefNotFoundError.matcher(e.getStdErr());
      if (matcher.find()) {
        throw new RepoException("Cannot find reference '" + matcher.group(1) + "'", e);
      }
      throw new RepoException("Error executing '" + gitExecPath + "': " + e.getMessage()
          + ". Stderr: \n" + e.getStdErr(), e);
    } catch (CommandException e) {
      throw new RepoException("Error executing '" + gitExecPath + "': " + e.getMessage(), e);
    }
  }

  @Override
  public String toString() {
    return "GitRepository{" +
        "gitExecPath='" + gitExecPath + '\'' +
        ", gitDir='" + gitDir + '\'' +
        ", verbose=" + verbose +
        ", repoUrl='" + repoUrl + '\'' +
        '}';
  }
}