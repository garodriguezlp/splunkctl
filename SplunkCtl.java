///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17

//COMPILE_OPTIONS -encoding UTF-8
//RUNTIME_OPTIONS -Dfile.encoding=UTF-8

//DEPS info.picocli:picocli:4.7.6
//DEPS org.apache.commons:commons-exec:1.3

//FILES docker-compose.yml=support/compose-working-dir/docker-compose.yml
//FILES default.yml=support/compose-working-dir/default.yml

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import picocli.AutoComplete.GenerateCompletion;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.PropertiesDefaultProvider;

@Command(
    name = "splunkctl",
    mixinStandardHelpOptions = true,
    version = "splunkctl 0.1",
    description = "Manages the lifecycle of a local Splunk Docker container.",
    defaultValueProvider = PropertiesDefaultProvider.class,
    subcommands = {
      StartCommand.class,
      StopCommand.class,
      DestroyCommand.class,
      StatusCommand.class,
      ExtractCommand.class,
      GenerateCompletion.class
    })
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
    return new SplunkConfig(resolveLogPath(logPath), splunkImage, splunkPassword);
  }

  private Path resolveLogPath(Path logPath) {
    if (logPath != null) {
      return logPath;
    }
    String envLogPath = System.getenv("SPLUNK_LOG_PATH");
    if (envLogPath != null && !envLogPath.isBlank()) {
      return Path.of(envLogPath);
    }
    return InfrastructureExtractor.DEFAULT_SAMPLE_DIR;
  }

  /**
   * Ensures ~/.splunkctl/compose-working-dir contains the bundled Compose working directory files
   * and returns the path to docker-compose.yml.
   */
  Path prepareComposeWorkingDirectory(ComposeWorkingDirectoryMode mode) throws IOException {
    InfrastructureExtractor extractor = new InfrastructureExtractor();
    if (extractor.migrateLegacyWorkingDirectoryIfNeeded()) {
      System.out.println(
          "Migrated legacy compose working directory into "
              + InfrastructureExtractor.COMPOSE_WORKING_DIR);
    }
    if (mode == ComposeWorkingDirectoryMode.USE_EXISTING_WORKING_DIRECTORY
        && extractor.hasCompleteWorkingDirectory()) {
      return extractor.composePath();
    }
    if (mode == ComposeWorkingDirectoryMode.USE_EXISTING_WORKING_DIRECTORY) {
      System.out.println(
          "Compose working directory is missing or incomplete. Restoring bundled files in "
              + InfrastructureExtractor.COMPOSE_WORKING_DIR);
      return extractor.extract();
    }
    if (extractor.hasIncompleteWorkingDirectory()) {
      System.out.println(
          "Compose working directory is incomplete. Restoring bundled files in "
              + InfrastructureExtractor.COMPOSE_WORKING_DIR);
      return extractor.extract();
    }
    if (extractor.hasAnyFiles()) {
      System.out.println(
          "Warning: files already exist in "
              + InfrastructureExtractor.COMPOSE_WORKING_DIR
              + " and will be overwritten with bundled defaults.");
      System.out.print("Continue? [N/y]: ");
      String answer = new Scanner(System.in).nextLine().trim();
      if (!answer.equalsIgnoreCase("y")) {
        System.out.println("Keeping existing context.");
        return extractor.composePath();
      }
    }
    return extractor.extract();
  }

  void printComposeWorkingDirectory() {
    System.out.println(
        "Compose working directory: "
            + InfrastructureExtractor.COMPOSE_WORKING_DIR.toAbsolutePath());
  }
}

enum ComposeWorkingDirectoryMode {
  PROMPT_BEFORE_OVERWRITE,
  USE_EXISTING_WORKING_DIRECTORY
}

// --- Commands ---

@Command(
    name = "start",
    mixinStandardHelpOptions = true,
    description = "Start the Splunk container in detached mode.")
class StartCommand implements Callable<Integer> {

  @ParentCommand private SplunkCtl parent;

  @Option(
      names = "--log-path",
      description =
          "Host directory mounted into the container as /var/log/springapp. Created if absent. Env:"
              + " SPLUNK_LOG_PATH. Default: ~/.splunkctl/samples")
  private Path logPath;

  @Override
  public Integer call() throws IOException {
    SplunkConfig config = parent.config(logPath);
    if (!new PreconditionChecker().check()) return 1;
    Path composePath =
        parent.prepareComposeWorkingDirectory(ComposeWorkingDirectoryMode.PROMPT_BEFORE_OVERWRITE);
    Files.createDirectories(config.logPath());
    parent.printComposeWorkingDirectory();
    new ContainerInspector().printEffectiveConfig(config);
    int exitCode = new DockerComposeRunner(composePath).up(config);
    if (exitCode == 0) {
      System.out.println(
          "Splunk started. Web UI: http://localhost:8000 (admin / "
              + config.splunkPassword()
              + ")");
    }
    return exitCode;
  }
}

@Command(
    name = "stop",
    mixinStandardHelpOptions = true,
    description = "Stop and remove containers, preserving volumes.")
class StopCommand implements Callable<Integer> {

  @ParentCommand private SplunkCtl parent;

  @Override
  public Integer call() throws IOException {
    SplunkConfig config = parent.config(Path.of("."));
    if (!new PreconditionChecker().check()) return 1;
    Path composePath =
        parent.prepareComposeWorkingDirectory(
            ComposeWorkingDirectoryMode.USE_EXISTING_WORKING_DIRECTORY);
    parent.printComposeWorkingDirectory();
    return new DockerComposeRunner(composePath).down(config);
  }
}

@Command(
    name = "destroy",
    mixinStandardHelpOptions = true,
    description = "Tear down containers and remove all volumes.")
class DestroyCommand implements Callable<Integer> {

  @ParentCommand private SplunkCtl parent;

  @Override
  public Integer call() throws IOException {
    SplunkConfig config = parent.config(Path.of("."));
    if (!new PreconditionChecker().check()) return 1;
    Path composePath =
        parent.prepareComposeWorkingDirectory(
            ComposeWorkingDirectoryMode.USE_EXISTING_WORKING_DIRECTORY);
    parent.printComposeWorkingDirectory();
    return new DockerComposeRunner(composePath).downWithVolumes(config);
  }
}

@Command(
    name = "status",
    mixinStandardHelpOptions = true,
    description = "Show current state of the managed containers.")
class StatusCommand implements Callable<Integer> {

  @ParentCommand private SplunkCtl parent;

  @Override
  public Integer call() throws IOException {
    SplunkConfig config = parent.config(Path.of("."));
    if (!new PreconditionChecker().check()) return 1;
    Path composePath =
        parent.prepareComposeWorkingDirectory(
            ComposeWorkingDirectoryMode.USE_EXISTING_WORKING_DIRECTORY);
    parent.printComposeWorkingDirectory();
    new ContainerInspector().printActualConfig();
    return new DockerComposeRunner(composePath).ps(config);
  }
}

@Command(
    name = "extract",
    mixinStandardHelpOptions = true,
    description = "Decompress a .gzip log file into the Splunk watched folder.")
class ExtractCommand implements Callable<Integer> {

  @ParentCommand private SplunkCtl parent;

  @Parameters(index = "0", description = "Path to the .gzip file to extract.")
  private Path gzipFile;

  @Option(
      names = "--log-path",
      description =
          "Directory where the extracted log file will be written. Created if absent."
              + " Env: SPLUNK_LOG_PATH. Default: ~/.splunkctl/samples")
  private Path logPath;

  @Override
  public Integer call() throws IOException {
    SplunkConfig config = parent.config(logPath);
    Path outputDir = config.logPath();
    Files.createDirectories(outputDir);
    Path outputFile = new GzipExtractor().extract(gzipFile, outputDir);
    System.out.println("Extracted: " + outputFile.toAbsolutePath());
    return 0;
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

  private final Path composePath;

  DockerComposeRunner(Path composePath) {
    this.composePath = composePath;
  }

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
    cmd.addArgument(composePath.toAbsolutePath().toString());
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

  void printEffectiveConfig(SplunkConfig config) {
    System.out.println("\nEffective configuration:");
    System.out.println("  Image:     " + config.splunkImage());
    System.out.println("  Log path:  " + config.logPath().toAbsolutePath());
    System.out.println("  Password:  " + config.splunkPassword());
    System.out.println();
  }

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
      org.apache.commons.exec.CommandLine cmd = new org.apache.commons.exec.CommandLine("docker");
      cmd.addArgument("inspect");
      cmd.addArgument("--format");
      cmd.addArgument(INSPECT_FORMAT, false);
      cmd.addArgument(CONTAINER_NAME);
      DefaultExecutor executor = new DefaultExecutor();
      executor.setStreamHandler(new PumpStreamHandler(System.out, OutputStream.nullOutputStream()));
      executor.setExitValues(null);
      return executor.execute(cmd);
    } catch (Exception e) {
      System.err.println("Error: docker inspect failed: " + e.getMessage());
      return 1;
    }
  }
}

final class InfrastructureExtractor {

  static final Path SPLUNKCTL_HOME = Path.of(System.getProperty("user.home"), ".splunkctl");
  static final Path COMPOSE_WORKING_DIR = SPLUNKCTL_HOME.resolve("compose-working-dir");
  static final Path DEFAULT_SAMPLE_DIR = SPLUNKCTL_HOME.resolve("samples");

  private static final Path LEGACY_COMPOSE_PATH = SPLUNKCTL_HOME.resolve("docker-compose.yml");
  private static final Path LEGACY_DEFAULT_YML_PATH =
      SPLUNKCTL_HOME.resolve(Path.of("docker", "splunk", "default.yml"));

  private static final String COMPOSE_RESOURCE = "docker-compose.yml";
  private static final String DEFAULT_YML_RESOURCE = "default.yml";

  Path composePath() {
    return COMPOSE_WORKING_DIR.resolve(COMPOSE_RESOURCE);
  }

  Path defaultYmlPath() {
    return COMPOSE_WORKING_DIR.resolve(DEFAULT_YML_RESOURCE);
  }

  boolean migrateLegacyWorkingDirectoryIfNeeded() throws IOException {
    boolean hasLegacyCompose = Files.exists(LEGACY_COMPOSE_PATH);
    boolean hasLegacyDefaultYml = Files.exists(LEGACY_DEFAULT_YML_PATH);
    if (!hasLegacyCompose && !hasLegacyDefaultYml) {
      return false;
    }

    Files.createDirectories(COMPOSE_WORKING_DIR);
    moveIfPresent(LEGACY_COMPOSE_PATH, composePath());
    moveIfPresent(LEGACY_DEFAULT_YML_PATH, defaultYmlPath());
    deleteIfEmpty(SPLUNKCTL_HOME.resolve(Path.of("docker", "splunk")));
    deleteIfEmpty(SPLUNKCTL_HOME.resolve("docker"));
    return true;
  }

  boolean hasAnyFiles() {
    return Files.exists(composePath()) || Files.exists(defaultYmlPath());
  }

  boolean hasCompleteWorkingDirectory() {
    return Files.exists(composePath()) && Files.exists(defaultYmlPath());
  }

  boolean hasIncompleteWorkingDirectory() {
    return hasAnyFiles() && !hasCompleteWorkingDirectory();
  }

  Path extract() throws IOException {
    extractResource(COMPOSE_RESOURCE);
    extractResource(DEFAULT_YML_RESOURCE);
    return composePath();
  }

  private void moveIfPresent(Path source, Path target) throws IOException {
    if (!Files.exists(source)) {
      return;
    }
    Files.createDirectories(target.getParent());
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
  }

  private void deleteIfEmpty(Path path) throws IOException {
    if (!Files.isDirectory(path)) {
      return;
    }
    try (Stream<Path> children = Files.list(path)) {
      if (children.findAny().isEmpty()) {
        Files.delete(path);
      }
    }
  }

  private void extractResource(String resource) throws IOException {
    Path target = COMPOSE_WORKING_DIR.resolve(resource);
    Files.createDirectories(target.getParent());
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
      if (in == null) throw new IllegalStateException("Missing bundled resource: " + resource);
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}

final class GzipExtractor {

  private static final String OUTPUT_FILE_PREFIX = "extracted-";
  private static final String OUTPUT_FILE_SUFFIX = ".log";
  private static final DateTimeFormatter TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

  Path extract(Path gzipFile, Path outputDir) throws IOException {
    Path outputFile = outputDir.resolve(outputFileName());
    try (InputStream in =
            new GZIPInputStream(new BufferedInputStream(Files.newInputStream(gzipFile)));
        OutputStream out = Files.newOutputStream(outputFile)) {
      in.transferTo(out);
    }
    return outputFile;
  }

  private String outputFileName() {
    return OUTPUT_FILE_PREFIX
        + LocalDateTime.now().format(TIMESTAMP_FORMATTER)
        + OUTPUT_FILE_SUFFIX;
  }
}
