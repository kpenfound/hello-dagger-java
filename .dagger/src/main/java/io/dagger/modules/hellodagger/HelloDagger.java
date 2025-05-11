package io.dagger.modules.hellodagger;

import static io.dagger.client.Dagger.dag;

import io.dagger.client.CacheVolume;
import io.dagger.client.Client;
import io.dagger.client.Container;
import io.dagger.client.DaggerQueryException;
import io.dagger.client.Directory;
import io.dagger.client.Env;
import io.dagger.client.File;
import io.dagger.client.LLM;
import io.dagger.client.Secret;
import io.dagger.client.Workspace;
import io.dagger.module.annotation.DefaultPath;
import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Object;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** HelloDagger main object */
@Object
public class HelloDagger {

  /** Publish the application container after building and testing it on-the-fly */
  @Function
  public String publish(@DefaultPath("/") Directory source)
    throws InterruptedException, ExecutionException, DaggerQueryException {
    this.test(source);
    return this.build(source).publish(
        "ttl.sh/hello-dagger-%d".formatted((int) (Math.random() * 10000000))
      );
  }

  /** Build the application container */
  @Function
  public Container build(@DefaultPath("/") Directory source)
    throws InterruptedException, ExecutionException, DaggerQueryException {
    Directory build =
      this.buildEnv(source).withExec(List.of("npm", "run", "build")).directory("./dist");
    return dag()
      .container()
      .from("nginx:1.25-alpine")
      .withDirectory("/usr/share/nginx/html", build)
      .withExposedPort(80);
  }

  /** Return the result of running unit tests */
  @Function
  public String test(@DefaultPath("/") Directory source)
    throws InterruptedException, ExecutionException, DaggerQueryException {
    return this.buildEnv(source).withExec(List.of("npm", "run", "test:unit", "run")).stdout();
  }

  /** Build a ready-to-use development environment */
  @Function
  public Container buildEnv(@DefaultPath("/") Directory source)
    throws InterruptedException, ExecutionException, DaggerQueryException {
    CacheVolume nodeCache = dag().cacheVolume("node");
    return dag()
      .container()
      .from("node:21-slim")
      .withDirectory("/src", source)
      .withMountedCache("/root/.npm", nodeCache)
      .withWorkdir("/src")
      .withExec(List.of("npm", "install"));
  }

  /**
   * A coding agent for developing new features
   *
   * @param assignment Assignment to complete
   * @param source The source directory
   */
  @Function
  public Directory develop(String assignment, @DefaultPath("/") Directory source)
    throws ExecutionException, DaggerQueryException, InterruptedException {
    // Environment with agent inputs and outputs
    Env environment = dag()
      .env(new Client.EnvArguments().withPrivileged(true))
      .withStringInput("assignment", assignment, "the assignment to complete")
      .withWorkspaceInput(
        "workspace",
        dag().workspace(source),
        "the workspace with tools to edit code"
      )
      .withWorkspaceOutput("completed", "the workspace with the completed assignment");

    // Detailed prompt stored in markdown file
    File promptFile = dag().currentModule().source().file("develop_prompt.md");

    // Put it all together to form the agent
    LLM work = dag().llm().withEnv(environment).withPromptFile(promptFile);

    // Get the output from the agent
    Workspace completed = work.env().output("completed").asWorkspace();
    Directory completedDirectory = completed.getSource().withoutDirectory("node_modules");

    // Make sure the tests really pass
    test(completedDirectory);

    // Return the Directory with the assignment completed
    return completedDirectory;
  }

  /**
   * Develop with a Github issue as the assignment and open a pull request
   *
   * @param githubToken Github Token with permissions to write issues and contents
   * @param issueID Github issue number
   * @param repository Github repository url
   * @param source Source directory
   */
  @Function
  public String developIssue(
    Secret githubToken,
    Integer issueID,
    String repository,
    @DefaultPath("/") Directory source
  ) throws ExecutionException, DaggerQueryException, InterruptedException {
    // Get the Github issue
    var args = new Client.GithubIssueArguments().withToken(githubToken);
    GithubIssue issueClient = dag().githubIssue(args);
    var issue = issueClient.read(repository, issueID);

    // Get information from the Github issue
    String assignment = issue.body();

    // Solve the issue with the Develop agent
    Directory feature = develop(assignment, source);

    // Open a pull request
    String title = issue.title();
    String url = issue.url();
    String body = assignment + "\n\nCloses " + url;
    var pr = issueClient.createPullRequest(repository, title, body, feature, "main");

    return pr.url();
  }
}
