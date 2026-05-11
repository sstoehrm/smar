# smar — small agent harness

Single-file Babashka script + library (`smar.clj`, ns `smar`) that proxies between clients and
local LLM backends. OpenAI-compatible, stateless CLI with structured output enforcement.
The same file is both runnable (`bb smar.clj …`) and requirable (`(require '[smar :as smar])`)
via a guarded `-main` dispatch at the end.

## Quick reference

```bash
bb smar.clj preflight '<json>'   # probe backend, list models
bb smar.clj complete             # read request from stdin, write response to stdout
bb smar.clj --self-test          # run inline tests
bb smar.clj --version            # print version
```

## Versioning

The version is defined as `smar-version` constant in `smar.clj`.
**Bump the patch version on every change to `smar.clj`.**
Use semantic versioning: major.minor.patch.

## Architecture

- Single file: `smar.clj` (ns `smar`) — deps loaded inline via `babashka.deps/add-deps`
- `bb.edn` at repo root exposes `.` on the classpath so consumers can `(require '[smar :as smar])`
- CLI dispatch at EOF is guarded by `(= *file* (System/getProperty "babashka.file"))` so it only
  fires when executed directly, not when required as a library
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
- `smar_backend` -> skip backend probe, use given type (`ollama`, `koboldcpp`, `llamacpp`, `llguidance`)
- `smar_strategy` -> override structured output strategy (`grammar` or `validate`)
- `smar_grammar` -> Lark/regex constraint via llguidance (requires `smar_backend: "llguidance"`)
- Both `smar_schema` + `smar_tools` -> error (mutually exclusive)

### Error handling

- Errors are JSON on stderr: `{"error":{"message":"...","type":"invalid_request_error"}}`
- Exit 0: success
- Exit 1: user/request error
- Exit 2: backend unreachable

## Model presets

EDN files in `~/.local/smar/models/` (override with `SMAR_MODELS_DIR`), loaded at
startup into `model-presets` map. Install once: `cp -r models ~/.local/smar/models`.
For repo-local dev (self-test etc.): `SMAR_MODELS_DIR=./models bb smar.clj ...`.
Each file: `{:family "name" :defaults {:temperature ...} :description "..."}`.
`apply-model-preset` merges defaults under explicit request fields (request wins).

## Backend detection

Auto-detects by probing: `/api/tags` -> ollama, `/api/v1/model` -> koboldcpp, else -> llamacpp.
`:llguidance` cannot be auto-detected (no fingerprint on `/props`); clients
must opt in explicitly via `smar_backend: "llguidance"`. The backend
derives from `:llamacpp` and inherits all behaviours except that
`smar_grammar` is wrapped with the `%llguidance {}` prefix and sent on the
`grammar` field of `/v1/chat/completions`.
Translation via multimethods: `translate-request` (takes schema as a 3rd arg),
`translate-response`, `list-models-remote`. koboldcpp uses the OpenAI-compat
endpoint (`/v1/chat/completions`), so no client-side chat-template application
is needed.

## Structured output

`smar_schema` triggers decode-time constraint on the backend. Each backend places
the schema in its native field:

- ollama: `{:format <schema>}` on `/api/chat` (Ollama >= 0.5)
- llama.cpp: `{:response_format {:type "json_schema" :json_schema {:schema <s> :strict true}}}` on `/v1/chat/completions`
- koboldcpp: same as llama.cpp on `/v1/chat/completions` (koboldcpp >= 1.68)

After decoding, smar runs `extract-json` + malli validation as a safety net, with
up to 3 retries on validation failure. Override the default grammar-constrained
flow via `smar_strategy: "validate"` to skip native constraint (useful when a
specific model misbehaves when grammar-locked).

## Tool calling

smar enforces that the LLM produces a valid tool call (correct tool name, valid arguments
matching the tool's parameter schema). It does NOT execute tools — it validates and retries
until the LLM response is a well-formed tool call, then returns it to the client.

## Docs

- `doc/API.md` — CLI command reference
- `doc/MODELS.md` — tested models and chat templates
