# smar

> **Experimental** — early development, APIs and behavior may change without notice, not recommended for production use.

OpenAI-compatible proxy for local LLM backends. Single Babashka script, no config files.

Auto-detects backend type (Ollama, KoboldCPP, llama.cpp) and translates requests/responses transparently.

## Requirements

[Babashka](https://github.com/babashka/babashka) >= 1.12.215 (deps are fetched automatically on first run).

## Usage

```
bb smar.bb.clj <server-port> <remote-port>
bb smar.bb.clj --self-test
```

| Parameter | Description |
|---|---|
| `server-port` | Port smar listens on |
| `remote-port` | Port of the LLM backend on localhost |
| `--self-test` | Run inline tests and exit |

## Example

```bash
# Proxy to Ollama on port 11434, expose OpenAI API on port 8080
bb smar.bb.clj 8080 11434
```

Then use any OpenAI-compatible client:

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"llama3","messages":[{"role":"user","content":"hello"}]}'
```

## Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/v1/chat/completions` | Chat completion |
| POST | `/v1/completions` | Raw completion |
| GET | `/v1/models` | List models |
| GET | `/admin/health` | Backend health check |

## Structured output

Request JSON schema enforcement via `response_format` in the request body. Strategy is auto-selected based on backend capability (GBNF grammar injection or validate+retry). Override with the `x-smar-strategy` header (`grammar` or `validate`).

## Limitations

- Grammar strategy (GBNF) trusts the backend to enforce structure — no post-validation
- Streaming support is limited to Ollama passthrough
- No authentication or rate limiting
- Single backend only (one remote-port per instance)
- Error handling for unreachable backends is minimal

## Documentation

- [API Reference](doc/API.md) — endpoints, request/response formats, structured output details
- [Supported Models](doc/MODELS.md) — tested models and implemented chat templates
