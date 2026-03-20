# smar

> **Experimental** — early development, APIs and behavior may change without notice, not recommended for production use.

OpenAI-compatible proxy for local LLM backends. Single Babashka script, no config files.

Auto-detects backend type (Ollama, KoboldCPP, llama.cpp) and translates requests/responses transparently. Target backend is specified per request via `smar_target` in the request body.

## Requirements

[Babashka](https://github.com/babashka/babashka) >= 1.12.215 (deps are fetched automatically on first run).

## Usage

```
bb smar.bb.clj <server-port>
bb smar.bb.clj --self-test
```

| Parameter | Description |
|---|---|
| `server-port` | Port smar listens on |
| `--self-test` | Run inline tests and exit |

## Example

```bash
bb smar.bb.clj 8080
```

Then use any OpenAI-compatible client, adding `smar_target` to the request body:

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"smar_target":"http://localhost:11434","model":"llama3","messages":[{"role":"user","content":"hello"}]}'
```

For GET endpoints, pass the target as a query parameter:

```bash
curl http://localhost:8080/v1/models?target=http://localhost:11434
```

## Endpoints

| Method | Path | Target via |
|---|---|---|
| POST | `/v1/chat/completions` | `smar_target` in body |
| POST | `/v1/completions` | `smar_target` in body |
| GET | `/v1/models` | `?target=` query param |
| GET | `/admin/health` | `?target=` query param |

## Structured output

Provide a JSON schema via `smar_schema` in the request body. Strategy is auto-selected based on backend capability (GBNF grammar injection or validate+retry). Override with the `x-smar-strategy` header (`grammar` or `validate`). OpenAI-style `response_format` is also supported as a fallback.

## Limitations

- Grammar strategy (GBNF) trusts the backend to enforce structure — no post-validation
- Streaming support is limited to Ollama passthrough
- No authentication or rate limiting
- Error handling for unreachable backends is minimal

## Documentation

- [API Reference](doc/API.md) — endpoints, request/response formats, structured output details
- [Supported Models](doc/MODELS.md) — tested models and implemented chat templates
