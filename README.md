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

The `samples/` directory is mounted by default, so there are events ready to query immediately.

### Available subcommands

| Command | What it does |
|---|---|
| `start` | Pull image, wire log directory, start container in detached mode |
| `stop` | Stop and remove containers (volumes preserved) |
| `reset` | Full teardown including volumes — fresh slate |
| `status` | Show current container state |

```bash
./jbang splunkctl start                        # defaults: ./samples as log dir
./jbang splunkctl start --log-path /path/to/logs
./jbang splunkctl status
./jbang splunkctl stop
./jbang splunkctl reset
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

The `samples/` directory includes two pre-built log files covering both supported formats — a good way to verify your setup before pointing `splunkctl` at real logs.

> **Note:** The sample events have **fixed historical timestamps** (January 2024). Set Splunk's time picker to **All time** before running the queries below, otherwise the result set will be empty.

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
./jbang splunkctl reset  # wipes everything for a fresh start
```

## License

MIT
