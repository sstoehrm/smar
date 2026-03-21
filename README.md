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

**Plain completion:**

```bash
curl -X POST http://localhost:8080/smar/complete \
  -H "Content-Type: application/json" \
  -d '{"smar_target":"http://localhost:11434","model":"llama3","messages":[{"role":"user","content":"hello"}]}'
```

**Structured JSON output:**

```bash
curl -X POST http://localhost:8080/smar/complete \
  -H "Content-Type: application/json" \
  -d '{"smar_target":"http://localhost:11434","smar_schema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]},"model":"llama3","messages":[{"role":"user","content":"give me a name"}]}'
```

**Tool calling:**

```bash
curl -X POST http://localhost:8080/smar/complete \
  -H "Content-Type: application/json" \
  -d '{"smar_target":"http://localhost:11434","smar_tools":[{"name":"get_weather","description":"Get weather for a city","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}],"model":"llama3","messages":[{"role":"user","content":"What is the weather in Berlin?"}]}'
```

**List models:**

```bash
curl -X POST http://localhost:8080/smar/models \
  -H "Content-Type: application/json" \
  -d '{"smar_target":"http://localhost:11434"}'
```

## Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/smar/complete` | Completion (plain, JSON schema, or tool call) |
| POST | `/smar/models` | List models from a backend |

## Modes

The `/smar/complete` endpoint mode is determined by which fields are present:

| Field | Mode | Description |
|---|---|---|
| (none) | plain | Direct completion, response passed through |
| `smar_schema` | JSON | Enforce structured output via GBNF grammar or validate+retry |
| `smar_tools` | tools | Enforce valid tool call response, retry if invalid |

`smar_schema` and `smar_tools` are mutually exclusive.

## Model families

Use `smar_model_family` to apply recommended defaults (temperature, top_p, top_k, repeat_penalty) for a model family. Explicit request fields override preset values.

```json
{"smar_target": "...", "smar_model_family": "llama3", "model": "llama3:8b", "messages": [...]}
```

Available families: `llama3`, `mistral`, `gemma2`, `phi4`, `qwen25`, `qwen3`, `qwen35`, `deepseek`, `command-r`, `codellama`, `default`. Presets are defined as EDN files in the `models/` directory.

## Limitations

- Grammar strategy (GBNF) trusts the backend to enforce structure — no post-validation
- Streaming support is limited to Ollama passthrough
- No authentication or rate limiting
- Error handling for unreachable backends is minimal
- Tool calling: smar enforces format only, does not execute tools

## Documentation

- [API Reference](doc/API.md) — endpoints, request/response formats, structured output details
- [Supported Models](doc/MODELS.md) — tested models and implemented chat templates
