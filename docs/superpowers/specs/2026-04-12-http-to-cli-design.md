# smar: HTTP server to CLI tool

## Summary

Convert smar from an httpkit HTTP server to a stateless CLI tool with two
commands (`preflight`, `complete`), JSON pipes, and Unix exit code conventions.
The caller (e.g. Clojure via `babashka.process`) owns caching of preflight
results.

## CLI interface

```
bb smar.bb.clj preflight '<json>'   # probe backend, list models
bb smar.bb.clj complete             # read request from stdin, write response to stdout
bb smar.bb.clj --self-test          # run inline tests
bb smar.bb.clj --version            # print version
```

### preflight

Argument: JSON string with `smar_target`.

```json
{"smar_target": "http://localhost:11434"}
```

Stdout (success):

```json
{
  "backend_type": "ollama",
  "target": "http://localhost:11434",
  "models": [
    {"id": "llama3:8b", "object": "model", "owned_by": "ollama"}
  ]
}
```

### complete

Reads JSON from stdin. Same request body as today with one addition:

- `smar_backend` (optional) — if present, skip backend probe. Value is one of
  `"ollama"`, `"koboldcpp"`, `"llamacpp"`. Caller gets this from `preflight`.

Stdout (success): OpenAI chat completion JSON.

### Error handling

- Errors: JSON to stderr, non-zero exit code.
- Exit 0: success.
- Exit 1: user/request error (missing fields, invalid input, schema+tools conflict).
- Exit 2: backend unreachable or backend error.

Stderr error format:

```json
{"error": {"message": "Missing required field: smar_target", "type": "invalid_request_error"}}
```

## Removals

- httpkit server: `run-server`, `router`, `wrap-request-tracking`, ring handler plumbing
- JLine3 TUI: `create-terminal`, `styled`, `tui-print`, `tui-print-no-nl`,
  `clear-line`, `with-spinner`, `print-banner`, `*terminal*` dynamic var, all
  JLine imports
- Request tracking: `request-stats` atom, `track-request-start`,
  `track-request-end`, `format-status`, `format-method`, `format-latency`,
  `log-request`
- HTTP response helpers: `json-response`, `error-response`, `parse-body`
- Models endpoint as standalone: `handle-models` removed, `list-models-remote`
  moves into preflight only
- `worker-threads` constant

## Unchanged core logic

- Chat templates (`templates`, `apply-template`, `detect-template-from-model`)
- GBNF generation (`json-schema->gbnf`)
- Malli validation (`json-schema->malli`, `validate-response`)
- Tool call validation (`validate-tool-call`, `build-tools-system-prompt`,
  `tool-call-response`)
- Backend detection (`probe-backend`) — used within preflight
- Backend translation multimethods (`translate-request`, `translate-response`)
- `forward-request`, retry loops (`complete-with-validation`,
  `complete-with-tool-validation`)
- `extract-smar-fields` (updated to also extract `smar_backend`),
  `inject-tools-prompt`, `prepare-request`
- Model presets (`load-model-presets`, `apply-model-preset`,
  `get-preset-template`)
- Strategy selection (`choose-strategy`, `inject-grammar`, `supports-grammar?`)

## New pieces

- `cli-error` — writes JSON error to stderr, calls `System/exit` with
  appropriate code
- `handle-preflight` — parses JSON arg, probes backend, lists models, writes
  JSON to stdout
- `handle-complete` — reads stdin JSON, extracts smar fields, dispatches to
  plain/schema/tool flow, writes JSON to stdout
- `-main` — dispatches on first arg: `preflight`, `complete`, `--self-test`,
  `--version`
- `smar_backend` field in complete requests — if present, skip probe

## Self-test updates

- `tui-print` calls become `println`
- Styled `PASS`/`FAIL` becomes plain text
- Same test logic, same assertions
- Routing tests removed (no HTTP router)
- New tests for CLI error formatting and `smar_backend` field extraction

## Deps changes

- Remove: `org.httpkit.server` require, JLine imports
- Keep: `org.httpkit.client` (still needed for `forward-request` and `probe-backend`),
  `cheshire`, `malli`, `clojure.string`, `clojure.edn`, `clojure.java.io`

## Strategy header

`x-smar-strategy` was an HTTP header. Replace with `smar_strategy` body field
(same values: `"grammar"` or `"validate"`). Consistent with other `smar_*`
fields.
