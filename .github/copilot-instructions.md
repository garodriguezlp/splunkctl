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

- Keep one clear responsibility per class, record, and method. A single file may contain multiple concerns, but each type should still have one reason to change.
- Commands coordinate use cases; they should not contain infrastructure details, shell construction, or environment probing logic.
- Separate policy from detail: what the app decides should be distinct from how Docker, files, and processes are invoked.
- Dependencies flow inward: CLI and process execution depend on core logic, not the other way around.
- Prefer small, single-purpose methods at one level of abstraction. If a method mixes workflow steps with low-level mechanics, extract helpers or a dedicated type.
- Intention-revealing names; no abbreviations outside the domain.
- Boolean predicates read as facts: `isDockerRunning()`, `isComposeAvailable()`, `hasRequiredConfig()`.
- Model domain concepts explicitly with records or enums when they clarify behavior, for example `SplunkConfig`, `CommandResult`, or `ComposeCommand`.
- Keep side effects at the edges. Parsing, validation, decision-making, and message formatting should be testable without spawning processes.
- Private helpers over inline complexity; avoid deep nesting and temporal coupling.
- Extract magic strings, exit codes, command fragments, and user-facing messages as named constants or value objects.
- Prefer composition over inheritance. No Lombok. No framework annotations outside PicoCLI.
- When adding behavior, first ask whether it belongs to command policy, shared application logic, or infrastructure detail. Put it in the owner of that concern.
- Optimize for local readability over clever reuse. Duplication is cheaper than the wrong abstraction.

## For agents

Keep changes surgical. Place new behaviour in the layer that owns that concern. Favor
readability over micro-optimizations. When unsure about scope, read the existing class
and match its style before adding anything new.
