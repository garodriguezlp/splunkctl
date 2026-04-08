///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17

//COMPILE_OPTIONS -encoding UTF-8
//RUNTIME_OPTIONS -Dfile.encoding=UTF-8

//DEPS info.picocli:picocli:4.7.6

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "splunk-samples",
    mixinStandardHelpOptions = true,
    version = "splunk-samples 0.1",
    description = "Generates fresh sample log files for splunkctl.")
public class GenerateSamples implements Callable<Integer> {

  private static final String JSON_FILE_NAME = "app.log.json";
  private static final String PREFIXED_FILE_NAME = "app.log.prefixed";
  private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_INSTANT;

  @Option(
      names = "--output-dir",
      defaultValue = "./samples",
      description = "Directory where sample files will be written. Default: ${DEFAULT-VALUE}")
  private Path outputDir;

  @Option(
      names = "--base-time",
      description =
          "Timestamp for the first sample event, in ISO-8601 format. Defaults to about 10 seconds before now.")
  private Instant baseTime;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new GenerateSamples()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws IOException {
    List<SampleEvent> events = sampleEvents();
    Instant firstEventTime =
      (baseTime != null ? baseTime : Instant.now()).truncatedTo(ChronoUnit.MILLIS)
        .minusSeconds(events.size() - 1L);

    Files.createDirectories(outputDir);
    Files.writeString(outputDir.resolve(JSON_FILE_NAME), buildJsonLines(events, firstEventTime));
    Files.writeString(outputDir.resolve(PREFIXED_FILE_NAME), buildPrefixedLines(events, firstEventTime));

    System.out.println("Generated fresh samples:");
    System.out.println("  " + outputDir.resolve(JSON_FILE_NAME).toAbsolutePath());
    System.out.println("  " + outputDir.resolve(PREFIXED_FILE_NAME).toAbsolutePath());
    System.out.println();
    System.out.println("Next step:");
    System.out.println("  ./jbang splunkctl start --log-path " + outputDir.toAbsolutePath());
    return 0;
  }

  private String buildJsonLines(List<SampleEvent> events, Instant firstEventTime) {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < events.size(); index++) {
      Instant eventTime = firstEventTime.plusSeconds(index);
      builder.append(toJsonLine(events.get(index), eventTime));
      builder.append(System.lineSeparator());
    }
    return builder.toString();
  }

  private String buildPrefixedLines(List<SampleEvent> events, Instant firstEventTime) {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < events.size(); index++) {
      SampleEvent event = events.get(index);
      Instant eventTime = firstEventTime.plusSeconds(index);
      builder.append(TIMESTAMP_FORMATTER.format(eventTime));
      builder.append(' ');
      builder.append(event.stream());
      builder.append(' ');
      builder.append(toJsonLine(event, eventTime));
      builder.append(System.lineSeparator());
    }
    return builder.toString();
  }

  private String toJsonLine(SampleEvent event, Instant eventTime) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("level", event.level());
    values.put("message", event.message());
    values.putAll(event.fields());
    values.put("ts", TIMESTAMP_FORMATTER.format(eventTime));
    return toJson(values);
  }

  private String toJson(Map<String, Object> values) {
    StringBuilder builder = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      if (!first) {
        builder.append(',');
      }
      first = false;
      builder.append('"').append(escape(entry.getKey())).append('"').append(':');
      builder.append(toJsonValue(entry.getValue()));
    }
    builder.append('}');
    return builder.toString();
  }

  private String toJsonValue(Object value) {
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    return '"' + escape(String.valueOf(value)) + '"';
  }

  private String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t");
  }

  private List<SampleEvent> sampleEvents() {
    return List.of(
      new SampleEvent("OUT", "INFO", "Application started", fields("app", "demo")),
        new SampleEvent(
            "OUT",
            "INFO",
            "Received request",
        fields("path", "/health", "status", 200)),
        new SampleEvent(
            "ERR",
            "ERROR",
            "Connection failed",
        fields("host", "db.example.com")),
        new SampleEvent(
            "OUT",
            "INFO",
            "Scheduled job executed",
        fields("job", "cleanup", "durationMs", 42)),
        new SampleEvent(
            "OUT",
            "WARN",
            "High memory usage",
        fields("usedMb", 812, "maxMb", 1024)),
        new SampleEvent(
            "OUT",
            "INFO",
            "Received request",
        fields("path", "/api/users", "status", 200)),
        new SampleEvent(
            "ERR",
            "ERROR",
            "Unhandled exception",
        fields("exception", "NullPointerException", "class", "UserService")),
        new SampleEvent(
            "OUT",
            "INFO",
            "Cache miss",
        fields("key", "user:42", "store", "redis")),
        new SampleEvent(
            "OUT",
            "INFO",
            "Received request",
        fields("path", "/api/orders", "status", 201)),
        new SampleEvent(
            "OUT",
            "DEBUG",
            "DB query executed",
        fields("sql", "SELECT * FROM orders", "durationMs", 5)));
    }

    private Map<String, Object> fields(Object... keyValues) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      values.put((String) keyValues[index], keyValues[index + 1]);
    }
    return values;
  }
}

record SampleEvent(String stream, String level, String message, Map<String, Object> fields) {}