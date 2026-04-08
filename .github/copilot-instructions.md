# Copilot Instructions for splunkctl

`splunkctl` is a single-file JBang CLI (`SplunkCtl.java`, Java 17) that wraps `docker-compose`
to manage a local Splunk container. Subcommands: `start`, `stop`, `reset`, `status`.

## Navigating the code

All logic lives in `SplunkCtl.java`. Classes are ordered top-down: CLI commands first,
infrastructure last. Section dividers (`// --- Commands ---`, `// --- Infrastructure ---`)
are the map.

- CLI layer: `SplunkCtl` (root), `StartCommand`, `StopCommand`, `ResetCommand`, `StatusCommand`
- Config boundary: `SplunkConfig` record
- Infrastructure: `PreconditionChecker`, `DockerComposeRunner`

`DockerComposeRunner` owns all process invocations — commands never spawn processes directly.
`docker-compose.yml` and `docker/splunk/default.yml` are internal implementation details; their
paths are constants, not CLI options.

## Code style

- Small, single-purpose methods at one level of abstraction.
- Intention-revealing names; no abbreviations outside the domain.
- Boolean predicates: `isDockerRunning()`, `isComposeAvailable()`.
- Private helpers over inline complexity; no deep nesting.
- Extract magic strings as named constants.
- Prefer composition over inheritance. No Lombok. No framework annotations outside PicoCLI.

## For agents

Keep changes surgical. Place new behaviour in the layer that owns that concern. Favor
readability over micro-optimizations. When unsure about scope, read the existing class
and match its style before adding anything new.
