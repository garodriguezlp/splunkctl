# splunkctl

> A zero-ceremony CLI to spin up a local Splunk instance for log exploration.

## One tool, four commands

`splunkctl` wraps `docker-compose` to manage the full lifecycle of a local Splunk container. One command starts Splunk, mounts your log directory, and applies a pre-configured input pipeline — no manual YAML tweaking, no port fumbling.

It handles two common log formats out of the box:

- **Plain JSON lines** — one JSON object per line
- **Prefixed JSON lines** — a leading timestamp/stream prefix before the JSON payload  
  (e.g. `2024-01-15T10:00:00Z OUT {"level":"INFO",...}`)

## From raw logs to searchable data in under a minute

Spinning up Splunk locally is useful but tedious. You need to know the right Docker flags, wire up volumes, configure `inputs.conf` and `props.conf` for your source type, and remember where the UI lives. `splunkctl` packages all of that into four subcommands so you can go from "I have some logs" to "I can query them in Splunk" in under a minute.

There is no installation step, no package manager, no build. If you have Java and JBang, you run the file and it works.

## Under the hood

| Technology | Role |
|---|---|
| [JBang](https://www.jbang.dev/) | Runs the whole project as a single script; resolves dependencies at runtime |
| Java 17 | Language |
| [PicoCLI](https://picocli.info/) | CLI parsing, subcommands, `--help`, properties-file defaults |
| `docker-compose` | Container lifecycle |
| [Apache Commons Exec](https://commons.apache.org/proper/commons-exec/) | Cross-platform process execution |

The entire program is a single Java file (`SplunkCtl.java`). No build step, no JAR, no native image.

## Prerequisites

- Java 17+
- [JBang](https://www.jbang.dev/download/)
- Docker with the standalone [`docker-compose`](https://docs.docker.com/compose/install/) binary

## Usage

Without cloning — zero install (Linux/macOS):

```bash
curl -Ls https://sh.jbang.dev | bash -s - run splunkctl@garodriguezlp/splunkctl start
```

Without cloning — zero install (Windows PowerShell):

```powershell
iex "& { $(iwr -useb https://ps.jbang.dev) } run splunkctl@garodriguezlp/splunkctl start"
```

With jbang already installed:

```bash
jbang run splunkctl@garodriguezlp/splunkctl start
```

From a local clone:

```bash
git clone https://github.com/garodriguezlp/splunkctl
cd splunkctl
./jbang splunkctl start
```

Splunk will be available at **http://localhost:8000** — log in as `admin` / `changeme123`.

The default `samples/` directory is mounted automatically. Refresh it with fresh timestamps before starting Splunk:

```bash
./jbang splunk-samples
./jbang splunkctl start
```

### Available subcommands

| Command | What it does |
|---|---|
| `start` | Pull image, wire log directory, start container in detached mode |
| `stop` | Stop and remove containers (volumes preserved) |
| `destroy` | Tear down containers and remove all volumes |
| `status` | Show current container state |

```bash
./jbang splunk-samples                      # refresh ./samples with current timestamps
./jbang splunkctl start                        # defaults: ./samples as log dir
./jbang splunkctl start --log-path /path/to/logs
./jbang splunkctl status
./jbang splunkctl stop
./jbang splunkctl destroy
```

### Configuration

Defaults can be overridden with flags, environment variables, or a `~/.splunkctl.properties` file:

| Option | Flag | Env var | Default |
|---|---|---|---|
| Log directory | `--log-path` | `SPLUNK_LOG_PATH` | `./samples` |
| Splunk image | `--splunk-image` | `SPLUNK_IMAGE` | `splunk/splunk:10.0.0` |
| Admin password | `--splunk-password` | `SPLUNK_PASSWORD` | `changeme123` |

**Properties file example** (`~/.splunkctl.properties`):

```properties
splunk-password=mysecurepassword
log-path=/Users/me/app-logs
```

## Splunk jump start

Use `./jbang splunk-samples` to regenerate the demo files in `samples/` with timestamps anchored to the current time. That keeps them visible in Splunk's default UI window and makes API examples work with rolling searches like `earliest_time=-15m`.

The generator writes two files covering both supported formats:

- `app.log.json`: plain JSON lines, each with an in-payload `ts` field.
- `app.log.prefixed`: a Docker-style prefix followed by the same JSON payload. The payload keeps `ts`, so after Splunk strips the prefix you still have a timestamp field inside the event.

That duplication is intentional:

- the prefix keeps prefixed logs realistic for the stripping pipeline;
- the JSON `ts` field survives in `_raw` after prefix removal;
- both files stay aligned around the same event times.

Recommended flow:

```bash
./jbang splunk-samples
./jbang splunkctl start
```

If you want reproducible fixtures instead of "now", pass an explicit base time:

```bash
./jbang splunk-samples --base-time 2026-04-07T12:00:00Z
```

### Get oriented

```spl
* index=main | table _time, level, message

* index=main | stats count by level
```

### Errors and warnings

```spl
* index=main (level=ERROR OR level=WARN) | table _time, level, message

* index=main level=ERROR OR level=WARN | timechart count by level

* index=main exception=* | table _time, class, exception, message
```

### HTTP requests

```spl
* index=main message="Received request" | stats count by path, status
```

### Latency

```spl
* index=main durationMs>0 | stats avg(durationMs), max(durationMs) by message | sort -avg(durationMs)
```

## Testing via the REST API

You can verify the stack without opening the Splunk UI by calling the management API on `https://localhost:8089`.

Use basic auth with the same admin credentials you passed to `splunkctl`.
Because the local container uses a self-signed certificate, the examples below use `-k` with `curl`.

### Check that Splunk is ready

```bash
curl -k -u admin:changeme123 \
  "https://localhost:8089/services/server/info?output_mode=json"
```

If Splunk is still booting, this request may fail for a short time after `splunkctl start` returns. Retry until you get a `200 OK` response.

### Read a few log events

This runs a search and streams JSON results back immediately:

```bash
curl -k -s -u admin:changeme123 \
  https://localhost:8089/services/search/jobs/export \
  -d search='search index=main | table _time level message | head 10' \
  -d output_mode=json
```

Example searches:

```bash
curl -k -s -u admin:changeme123 \
  https://localhost:8089/services/search/jobs/export \
  -d search='search index=main level=ERROR OR level=WARN | table _time level message exception' \
  -d output_mode=json

curl -k -s -u admin:changeme123 \
  https://localhost:8089/services/search/jobs/export \
  -d search='search index=main message="Received request" | stats count by path status' \
  -d output_mode=json
```

### Control the time window

Freshly generated sample files should appear in the default recent time window. If you generated them a while ago, or you used `--base-time`, adjust the time range explicitly.

You have two practical options:

1. In the UI, set the time picker to a wide enough range.
2. In the REST API, pass an explicit time range.

For a recent rolling window:

```bash
curl -k -s -u admin:changeme123 \
  https://localhost:8089/services/search/jobs/export \
  -d search='search index=main | table _time level message ts | head 10' \
  -d earliest_time='-15m' \
  -d latest_time='now' \
  -d output_mode=json
```

To search everything regardless of age:

```bash
curl -k -s -u admin:changeme123 \
  https://localhost:8089/services/search/jobs/export \
  -d search='search index=main | table _time level message ts | head 10' \
  -d earliest_time=0 \
  -d latest_time=now \
  -d output_mode=json
```

To search a specific fixed window that includes generated fixtures:

```bash
curl -k -s -u admin:changeme123 \
  https://localhost:8089/services/search/jobs/export \
  -d search='search index=main | table _time level message ts | head 10' \
  -d earliest_time='2026-04-07T12:00:00' \
  -d latest_time='2026-04-07T12:05:00' \
  -d output_mode=json
```

That gives you a clean way to validate both freshly generated sample files and deliberately backdated fixtures.

---

## Tailing live output into Splunk

`splunkctl` is particularly useful when you want to explore a live log stream from any process — a CLI tool, a deployment job, a migration script — without setting up a dedicated logging pipeline.

The pattern:

1. Point `splunkctl` at a directory on your machine.
2. Stream your process output into a file in that directory.  
   `tee` lets you watch it live in the terminal and write it to disk at the same time.
3. Open Splunk and query the data however you like.

```bash
# Terminal 1 — start Splunk watching a directory
./jbang splunkctl start --log-path ./live-logs

# Terminal 2 — stream output from any process, watch it live and capture it
some-command that produces logs | tee ./live-logs/output.log

# Now explore at http://localhost:8000
```

This works especially well when the process emits structured (JSON) logs — Splunk will parse the fields automatically and make them filterable and chartable without any extra configuration.

When you are done, stop Splunk cleanly:

```bash
./jbang splunkctl stop   # keeps data volume for next time
./jbang splunkctl destroy  # wipes everything for a fresh start
```

## License

MIT
