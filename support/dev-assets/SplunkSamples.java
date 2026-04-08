///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17

//COMPILE_OPTIONS -encoding UTF-8
//RUNTIME_OPTIONS -Dfile.encoding=UTF-8

//DEPS info.picocli:picocli:4.7.6

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
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
public class SplunkSamples implements Callable<Integer> {

  private static final Path DEFAULT_OUTPUT_DIR =
      Path.of(System.getProperty("user.home"), ".splunkctl", "samples");

  private static final String JSON_FILE_NAME = "app.log.json";
  private static final String PREFIXED_FILE_NAME = "app.log.prefixed";
  private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_INSTANT;
  private static final DateTimeFormatter RTR_INNER_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'+0000'").withZone(ZoneOffset.UTC);

  @Option(
      names = "--output-dir",
      description = "Directory where sample files will be written. Default: ~/.splunkctl/samples")
  private Path outputDir;

  @Option(
      names = "--base-time",
      description =
          "Timestamp for the first sample event, in ISO-8601 format. Defaults to about 10 seconds before now.")
  private Instant baseTime;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new SplunkSamples()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws IOException {
    Path effectiveOutputDir = outputDir != null ? outputDir : DEFAULT_OUTPUT_DIR;
    List<SampleEvent> appEvents = sampleEvents();
    List<RtrEvent> rtrEvents = rtrEvents();
    Instant firstEventTime =
      (baseTime != null ? baseTime : Instant.now()).truncatedTo(ChronoUnit.MILLIS)
        .minusSeconds(appEvents.size() + rtrEvents.size() - 1L);

    Files.createDirectories(effectiveOutputDir);
    Files.writeString(
        effectiveOutputDir.resolve(JSON_FILE_NAME), buildJsonLines(appEvents, firstEventTime));
    Files.writeString(
        effectiveOutputDir.resolve(PREFIXED_FILE_NAME),
        buildPrefixedLines(appEvents, rtrEvents, firstEventTime));

    System.out.println("Generated fresh samples:");
    System.out.println("  " + effectiveOutputDir.resolve(JSON_FILE_NAME).toAbsolutePath());
    System.out.println("  " + effectiveOutputDir.resolve(PREFIXED_FILE_NAME).toAbsolutePath());
    System.out.println();
    System.out.println("Next step:");
    System.out.println("  ./jbang splunkctl start --log-path " + effectiveOutputDir.toAbsolutePath());
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

  private String buildPrefixedLines(List<SampleEvent> appEvents, List<RtrEvent> rtrEvents, Instant firstEventTime) {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < appEvents.size(); index++) {
      SampleEvent event = appEvents.get(index);
      Instant eventTime = firstEventTime.plusSeconds(index);
      builder.append(TIMESTAMP_FORMATTER.format(eventTime))
             .append(" [APP/PROC/WEB/0] ")
             .append(event.stream())
             .append(' ')
             .append(toJsonLine(event, eventTime))
             .append(System.lineSeparator());
    }
    Instant rtrBase = firstEventTime.plusSeconds(appEvents.size());
    for (int index = 0; index < rtrEvents.size(); index++) {
      RtrEvent event = rtrEvents.get(index);
      Instant eventTime = rtrBase.plusSeconds(index);
      builder.append(TIMESTAMP_FORMATTER.format(eventTime))
             .append(" [RTR/0] OUT ")
             .append(toRtrLine(event, eventTime))
             .append(System.lineSeparator());
    }
    return builder.toString();
  }

  private List<RtrEvent> rtrEvents() {
    return List.of(
        new RtrEvent("api.example.com", "GET", "/health", 200, 0.001234),
        new RtrEvent("api.example.com", "POST", "/api/users", 201, 0.045678),
        new RtrEvent("api.example.com", "GET", "/api/orders", 500, 0.098765));
  }

  private String toRtrLine(RtrEvent event, Instant ts) {
    String innerTs = RTR_INNER_FORMATTER.format(ts);
    return String.format(
        "%s - [%s] \"%s %s HTTP/1.1\" %d 0 512 \"-\" \"Go-http-client/1.1\""
            + " \"10.0.0.1:12345\" \"10.0.0.2:8080\" response_time:%.6f"
            + " app_id:\"demo-app\" app_index:\"0\"",
        event.host(), innerTs, event.method(), event.path(), event.status(), event.responseTime());
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

record RtrEvent(String host, String method, String path, int status, double responseTime) {}