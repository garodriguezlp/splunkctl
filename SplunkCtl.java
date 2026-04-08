///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17

//COMPILE_OPTIONS -encoding UTF-8
//RUNTIME_OPTIONS -Dfile.encoding=UTF-8

//DEPS info.picocli:picocli:4.7.6
//DEPS org.apache.commons:commons-exec:1.3

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.PropertiesDefaultProvider;

@Command(
    name = "splunkctl",
    mixinStandardHelpOptions = true,
    version = "splunkctl 0.1",
    description = "Manages the lifecycle of a local Splunk Docker container.",
    defaultValueProvider = PropertiesDefaultProvider.class,
    subcommands = {StartCommand.class, StopCommand.class, ResetCommand.class, StatusCommand.class})
public class SplunkCtl implements Callable<Integer> {

  @Option(
      names = "--splunk-image",
      defaultValue = "${SPLUNK_IMAGE:-splunk/splunk:10.0.0}",
      description = "Full image reference. Env: SPLUNK_IMAGE. Default: ${DEFAULT-VALUE}")
  private String splunkImage;

  @Option(
      names = "--splunk-password",
      defaultValue = "${SPLUNK_PASSWORD:-changeme123}",
      description = "Splunk admin password. Env: SPLUNK_PASSWORD. Default: ${DEFAULT-VALUE}")
  private String splunkPassword;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new SplunkCtl()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() {
    new CommandLine(this).usage(System.out);
    return 0;
  }

  SplunkConfig config(Path logPath) {
    return new SplunkConfig(logPath, splunkImage, splunkPassword);
  }
}

// --- Commands ---

@Command(name = "start", description = "Start the Splunk container in detached mode.")
class StartCommand implements Callable<Integer> {

  @ParentCommand private SplunkCtl parent;

  @Option(
      names = "--log-path",
      defaultValue = "${SPLUNK_LOG_PATH:-./samples}",
      description =
          "Host directory mounted into the container as /var/log/springapp. Created if absent. Env:"
              + " SPLUNK_LOG_PATH. Default: ${DEFAULT-VALUE}")
  private Path logPath;

  @Override
  public Integer call() throws IOException {
    SplunkConfig config = parent.config(logPath);
    if (!new PreconditionChecker().check()) return 1;
    Files.createDirectories(config.logPath());
    int exitCode = new DockerComposeRunner().up(config);
    if (exitCode == 0) {
      System.out.println(
          "Splunk started. Web UI: http://localhost:8000 (admin / "
              + config.splunkPassword()
              + ")");
    }
    return exitCode;
  }
}

@Command(name = "stop", description = "Stop and remove containers, preserving volumes.")
class StopCommand implements Callable<Integer> {

  @ParentCommand private SplunkCtl parent;

  @Override
  public Integer call() {
    SplunkConfig config = parent.config(Path.of("."));
    if (!new PreconditionChecker().check()) return 1;
    return new DockerComposeRunner().down(config);
  }
}

@Command(name = "reset", description = "Full teardown including volumes (fresh slate).")
class ResetCommand implements Callable<Integer> {

  @ParentCommand private SplunkCtl parent;

  @Override
  public Integer call() {
    SplunkConfig config = parent.config(Path.of("."));
    if (!new PreconditionChecker().check()) return 1;
    return new DockerComposeRunner().downWithVolumes(config);
  }
}

@Command(name = "status", description = "Show current state of the managed containers.")
class StatusCommand implements Callable<Integer> {

  @ParentCommand private SplunkCtl parent;

  @Override
  public Integer call() {
    SplunkConfig config = parent.config(Path.of("."));
    if (!new PreconditionChecker().check()) return 1;
    new ContainerInspector().printActualConfig();
    return new DockerComposeRunner().ps(config);
  }
}

// --- Infrastructure ---

record SplunkConfig(Path logPath, String splunkImage, String splunkPassword) {}

final class PreconditionChecker {

  boolean check() {
    return isComposeAvailable() && isDockerRunning();
  }

  private boolean isComposeAvailable() {
    if (runSilently("docker-compose", "version") == 0) return true;
    System.err.println(
        "Error: 'docker-compose' is not available. Please install docker-compose"
            + " (https://docs.docker.com/compose/install/).");
    return false;
  }

  private boolean isDockerRunning() {
    if (runSilently("docker", "info") == 0) return true;
    System.err.println("Error: Docker daemon is not running. Please start Docker and try again.");
    return false;
  }

  private int runSilently(String executable, String... args) {
    try {
      org.apache.commons.exec.CommandLine cmd = new org.apache.commons.exec.CommandLine(executable);
      for (String arg : args) cmd.addArgument(arg);
      DefaultExecutor executor = new DefaultExecutor();
      executor.setStreamHandler(
          new PumpStreamHandler(OutputStream.nullOutputStream(), OutputStream.nullOutputStream()));
      executor.setExitValues(null);
      return executor.execute(cmd);
    } catch (Exception e) {
      return 1;
    }
  }
}

final class DockerComposeRunner {

  private static final String COMPOSE_FILE = "./docker-compose.yml";

  int up(SplunkConfig config) {
    return run(config, "up", "-d");
  }

  int down(SplunkConfig config) {
    return run(config, "down");
  }

  int downWithVolumes(SplunkConfig config) {
    return run(config, "down", "-v");
  }

  int ps(SplunkConfig config) {
    return run(config, "ps");
  }

  private int run(SplunkConfig config, String... composeArgs) {
    try {
      org.apache.commons.exec.CommandLine cmd = buildCommand(composeArgs);
      Map<String, String> env = buildEnvironment(config);
      DefaultExecutor executor = new DefaultExecutor();
      executor.setStreamHandler(new PumpStreamHandler(System.out, System.err));
      executor.setExitValues(null);
      int exitCode = executor.execute(cmd, env);
      if (exitCode != 0) {
        System.err.println("Error: docker-compose exited with code " + exitCode);
      }
      return exitCode;
    } catch (Exception e) {
      System.err.println("Error: docker-compose execution failed: " + e.getMessage());
      return 1;
    }
  }

  private org.apache.commons.exec.CommandLine buildCommand(String... composeArgs) {
    org.apache.commons.exec.CommandLine cmd =
        new org.apache.commons.exec.CommandLine("docker-compose");
    cmd.addArgument("-f");
    cmd.addArgument(COMPOSE_FILE);
    for (String arg : composeArgs) {
      cmd.addArgument(arg);
    }
    return cmd;
  }

  private Map<String, String> buildEnvironment(SplunkConfig config) {
    Map<String, String> env = new HashMap<>(System.getenv());
    env.put("LOG_PATH", config.logPath().toAbsolutePath().toString());
    env.put("SPLUNK_IMAGE", config.splunkImage());
    env.put("SPLUNK_PASSWORD", config.splunkPassword());
    return env;
  }
}

final class ContainerInspector {

  private static final String CONTAINER_NAME = "splunk";
  private static final String INSPECT_FORMAT =
      "Image:  {{.Config.Image}}\n"
          + "Mounts:{{range .HostConfig.Binds}}\n"
          + "  {{.}}{{end}}\n"
          + "Env:{{range .Config.Env}}\n"
          + "  {{.}}{{end}}";

  void printActualConfig() {
    System.out.println("Container configuration (from Docker):");
    int exitCode = runInspect();
    if (exitCode != 0) {
      System.out.println("  (container '" + CONTAINER_NAME + "' is not running)");
    }
    System.out.println();
  }

  private int runInspect() {
    try {
      org.apache.commons.exec.CommandLine cmd =
          new org.apache.commons.exec.CommandLine("docker");
      cmd.addArgument("inspect");
      cmd.addArgument("--format");
      cmd.addArgument(INSPECT_FORMAT, false);
      cmd.addArgument(CONTAINER_NAME);
      DefaultExecutor executor = new DefaultExecutor();
      executor.setStreamHandler(
          new PumpStreamHandler(System.out, OutputStream.nullOutputStream()));
      executor.setExitValues(null);
      return executor.execute(cmd);
    } catch (Exception e) {
      System.err.println("Error: docker inspect failed: " + e.getMessage());
      return 1;
    }
  }
}
