# smar — small agent harness

Single-file Babashka proxy (`smar.bb.clj`) between clients and local LLM backends.
OpenAI-compatible API with structured output enforcement.

## Quick reference

```bash
bb smar.bb.clj <server-port>   # start server
bb smar.bb.clj --self-test     # run inline tests
bb smar.bb.clj --version       # print version
```

## Versioning

The version is defined as `smar-version` constant in `smar.bb.clj`.
**Bump the patch version on every change to `smar.bb.clj`.**
Use semantic versioning: major.minor.patch.

## Architecture

- Single file: `smar.bb.clj` — deps loaded inline via `babashka.deps/add-deps`
- Requires Babashka >= 1.12.215 (JLine3 for TUI)
- Deps: malli (inline), httpkit + cheshire (bundled in bb)
- Server: httpkit with configurable thread pool (`worker-threads` constant)
- No fixed backend — target URL provided per-request via `smar_target` body field

## API surface

Two endpoints, both POST with JSON body:

- `POST /smar/complete` — unified completion (plain, JSON schema, tool calling)
- `POST /smar/models` — list models from a backend

All requests include `smar_target` in the body. The complete endpoint mode is
determined by which smar fields are present:

- Nothing extra → plain completion
- `smar_schema` → structured JSON output (GBNF or validate+retry)
- `smar_tools` → enforce valid tool call response (validate+retry)
- `smar_model_family` → apply preset defaults (temperature, top_p, top_k, repeat_penalty, template)
- Both `smar_schema` + `smar_tools` → 400 error (mutually exclusive)

## Model presets

EDN files in `models/` directory, loaded at startup into `model-presets` map.
Each file: `{:family "name" :template :key :defaults {:temperature ...} :description "..."}`.
`apply-model-preset` merges defaults under explicit request fields (request wins).
`get-preset-template` returns the template key for koboldcpp translation.

## TUI (JLine3)

Terminal output uses JLine3 `AttributedStringBuilder` for styled/colored text.
Key abstractions:

- `styled` — creates ANSI-styled text via JLine3 `AttributedStyle`
- `tui-print` / `tui-print-no-nl` — terminal-aware print (falls back to `println` if no terminal)
- `with-spinner` — animated spinner for blocking operations (backend probe)
- `log-request` — colored request log line (timestamp, method, uri, status, latency)
- `wrap-request-tracking` — ring middleware tracking active/total/error counts per request
- `*terminal*` — dynamic var bound in `-main`, threaded through all output

Self-test uses green PASS / red FAIL with section headers.
Server mode shows a startup banner + live colored request log.

## Backend detection

Auto-detects by probing: `/api/tags` → ollama, `/api/v1/model` → koboldcpp, else → llamacpp.
Detection is cached per target URL in `backend-cache` atom.
Translation via multimethods: `translate-request`, `translate-response`, `list-models-remote`.

## Structured output

Two strategies: GBNF grammar injection (llamacpp, koboldcpp) or validate+retry with malli (ollama).
Override via `x-smar-strategy` header.

## Tool calling

smar enforces that the LLM produces a valid tool call (correct tool name, valid arguments
matching the tool's parameter schema). It does NOT execute tools — it validates and retries
until the LLM response is a well-formed tool call, then returns it to the client.

## Docs

- `doc/API.md` — endpoint reference
- `doc/MODELS.md` — tested models and chat templates
