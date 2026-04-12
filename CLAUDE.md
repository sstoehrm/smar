# smar — small agent harness

Single-file Babashka CLI tool (`smar.bb.clj`) that proxies between clients and local LLM backends.
OpenAI-compatible stateless CLI with structured output enforcement.

## Quick reference

```bash
bb smar.bb.clj preflight '<json>'   # probe backend, list models
bb smar.bb.clj complete             # read request from stdin, write response to stdout
bb smar.bb.clj --self-test          # run inline tests
bb smar.bb.clj --version            # print version
```

## Versioning

The version is defined as `smar-version` constant in `smar.bb.clj`.
**Bump the patch version on every change to `smar.bb.clj`.**
Use semantic versioning: major.minor.patch.

## Architecture

- Single file: `smar.bb.clj` — deps loaded inline via `babashka.deps/add-deps`
- Requires Babashka >= 1.12.215
- Deps: malli (inline), httpkit client + cheshire (bundled in bb)
- Stateless CLI: dispatches on first argument (`preflight`, `complete`, `--self-test`, `--version`)
- No fixed backend — target URL provided per-request via `smar_target` field

## CLI commands

### `preflight`

Probes a backend and lists its models. Argument is a JSON string with `smar_target`.

- Stdout: `{"backend_type":"ollama","target":"...","models":[...]}`
- Auto-detects backend by probing: `/api/tags` -> ollama, `/api/v1/model` -> koboldcpp, else -> llamacpp

### `complete`

Reads an OpenAI-compatible JSON request from stdin, writes completion JSON to stdout.
Mode determined by which `smar_*` fields are present:

- Nothing extra -> plain completion
- `smar_schema` -> structured JSON output (GBNF or validate+retry)
- `smar_tools` -> enforce valid tool call response (validate+retry)
- `smar_model_family` -> apply preset defaults (temperature, top_p, top_k, repeat_penalty, template)
- `smar_backend` -> skip backend probe, use given type (`ollama`, `koboldcpp`, `llamacpp`)
- `smar_strategy` -> override structured output strategy (`grammar` or `validate`)
- Both `smar_schema` + `smar_tools` -> error (mutually exclusive)

### Error handling

- Errors are JSON on stderr: `{"error":{"message":"...","type":"invalid_request_error"}}`
- Exit 0: success
- Exit 1: user/request error
- Exit 2: backend unreachable

## Model presets

EDN files in `models/` directory, loaded at startup into `model-presets` map.
Each file: `{:family "name" :template :key :defaults {:temperature ...} :description "..."}`.
`apply-model-preset` merges defaults under explicit request fields (request wins).
`get-preset-template` returns the template key for koboldcpp translation.

## Backend detection

Auto-detects by probing: `/api/tags` -> ollama, `/api/v1/model` -> koboldcpp, else -> llamacpp.
Translation via multimethods: `translate-request`, `translate-response`, `list-models-remote`.

## Structured output

Two strategies: GBNF grammar injection (llamacpp, koboldcpp) or validate+retry with malli (ollama).
Override via `smar_strategy` field in the request body (`grammar` or `validate`).

## Tool calling

smar enforces that the LLM produces a valid tool call (correct tool name, valid arguments
matching the tool's parameter schema). It does NOT execute tools — it validates and retries
until the LLM response is a well-formed tool call, then returns it to the client.

## Docs

- `doc/API.md` — CLI command reference
- `doc/MODELS.md` — tested models and chat templates
