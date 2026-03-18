# smar — small agent harness

Babashka proxy server that sits between clients and LLM backends,
providing a unified OpenAI-compatible API with structured output enforcement.

Single-file design: everything lives in `smar.bb.clj`. No separate
`bb.edn` — deps are loaded inline via `babashka.deps/add-deps`.

## Usage

```bash
bb smar.bb.clj <server-port> <remote-port>
bb smar.bb.clj --self-test
```

- `server-port` — port smar listens on (the OpenAI-compatible API)
- `remote-port` — port of the LLM backend to proxy to (localhost)
- `--self-test` — run inline tests and exit (no server started, no backend needed)

Examples:

```bash
bb smar.bb.clj 8080 11434    # listen on 8080, proxy to ollama on 11434
bb smar.bb.clj --self-test   # run all inline tests
```

The self-test exercises request/response translation, GBNF generation,
schema validation, chat template rendering, and routing — everything
that can be tested without a live backend.

## API surface

**OpenAI-compatible** (primary):
- `POST /v1/chat/completions` — chat completion (streaming + non-streaming)
- `POST /v1/completions` — raw completion
- `GET  /v1/models` — list available models

**Admin**:
- `GET  /admin/health` — backend health check

## Backend

Single backend pointing at `http://localhost:<remote-port>`. Auto-detects
the backend type by probing known endpoints on startup:

| Probe                        | Type      |
|------------------------------|-----------|
| `GET /api/tags` responds     | ollama    |
| `GET /api/v1/model` responds | koboldcpp |
| otherwise                    | llamacpp  |

Each type knows how to translate OpenAI-format requests to the native API:

```clojure
(defmulti translate-request :backend-type)   ;; OpenAI → native format
(defmulti translate-response :backend-type)  ;; native → OpenAI format
(defmulti list-models :backend-type)         ;; fetch available models
(defmulti supports-grammar? :backend-type)   ;; GBNF grammar support?
```

## Structured output

Two strategies, chosen per-request based on backend capabilities:

1. **Grammar-constrained** — convert JSON schema → GBNF grammar, pass to
   backends that support it (llama.cpp, koboldcpp). Output is structurally
   guaranteed.

2. **Validate + retry** — let the model generate freely, validate response
   against JSON schema. On failure, re-prompt with the validation error
   (up to 3 retries).

Strategy selection: grammar-constrained when `(supports-grammar? backend)`
is true, otherwise validate+retry. Client can force a strategy via
`x-smar-strategy: grammar|validate` header.

## Chat templates

Resolved based on backend type and model metadata:

```clojure
(def templates
  {:chatml  {:bos "<|im_start|>" :eos "<|im_end|>" ...}
   :llama3  {:bos "<|begin_of_text|>" ...}
   :mistral { ... }})
```

Template selection:
1. Auto-detect from model metadata (ollama modelfile, gguf metadata)
2. Fallback to ChatML

Only relevant for raw completion endpoints. Chat-native endpoints
(ollama `/api/chat`, llama.cpp `/v1/chat/completions`) handle their own
templates — the proxy passes messages through.

## Project layout

```
smar/
└── smar.bb.clj     ;; everything (deps, server, logic)
```

## Dependencies

Loaded inline at the top of `smar.bb.clj`:

```clojure
(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {metosin/malli {:mvn/version "0.16.4"}}})
```

Key libs:
- **httpkit** (bundled in bb) — HTTP server + client
- **malli** — schema validation
- **cheshire** (bundled in bb) — JSON encode/decode

## Request flow

```
Client (OpenAI-compat request)
  → parse CLI args: server-port, remote-port
  → router (ring handler)
  → detect backend type (cached after first probe)
  → translate request (OpenAI → native format)
  → if structured output requested:
      → grammar-capable? → inject GBNF grammar
      → else             → mark for post-validation
  → forward to http://localhost:<remote-port>
  → translate response (native → OpenAI format)
  → if post-validation:
      → validate with malli
      → on failure: retry with error context (up to 3)
  → return response to client
```
