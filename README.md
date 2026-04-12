# smar

> **Experimental** — early development, APIs and behavior may change without notice, not recommended for production use.

OpenAI-compatible CLI proxy for local LLM backends. Single Babashka script, no config files.

Auto-detects backend type (Ollama, KoboldCPP, llama.cpp) and translates requests/responses transparently. Target backend is specified per request via `smar_target` in the request body.

## Requirements

[Babashka](https://github.com/babashka/babashka) >= 1.12.215 (deps are fetched automatically on first run).

## Usage

```
bb smar.bb.clj preflight '<json>'   # probe backend, list models
bb smar.bb.clj complete             # read request from stdin, write response to stdout
bb smar.bb.clj --self-test          # run inline tests
bb smar.bb.clj --version            # print version
```

| Command | Description |
|---|---|
| `preflight '<json>'` | Probe backend and list models. JSON must contain `smar_target`. |
| `complete` | Read completion request from stdin, write response to stdout. |
| `--self-test` | Run inline tests and exit |
| `--version` | Print version and exit |

## Example

**Preflight (probe backend and list models):**

```bash
bb smar.bb.clj preflight '{"smar_target":"http://localhost:11434"}'
```

**Plain completion:**

```bash
echo '{"smar_target":"http://localhost:11434","model":"llama3","messages":[{"role":"user","content":"hello"}]}' \
  | bb smar.bb.clj complete
```

**Structured JSON output:**

```bash
echo '{"smar_target":"http://localhost:11434","smar_schema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]},"model":"llama3","messages":[{"role":"user","content":"give me a name"}}' \
  | bb smar.bb.clj complete
```

**Tool calling:**

```bash
echo '{"smar_target":"http://localhost:11434","smar_tools":[{"name":"get_weather","description":"Get weather for a city","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}],"model":"llama3","messages":[{"role":"user","content":"What is the weather in Berlin?"}]}' \
  | bb smar.bb.clj complete
```

**Skip backend probe (if you already know the backend type):**

```bash
echo '{"smar_target":"http://localhost:11434","smar_backend":"ollama","model":"llama3","messages":[{"role":"user","content":"hello"}]}' \
  | bb smar.bb.clj complete
```

## Modes

The `complete` command mode is determined by which fields are present in the request:

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

## Error handling

- Errors are JSON on stderr: `{"error":{"message":"...","type":"invalid_request_error"}}`
- Exit 0: success
- Exit 1: user/request error
- Exit 2: backend unreachable

## Limitations

- Grammar strategy (GBNF) trusts the backend to enforce structure — no post-validation
- Streaming support is limited to Ollama passthrough
- No authentication or rate limiting
- Tool calling: smar enforces format only, does not execute tools

## Documentation

- [API Reference](doc/API.md) — CLI commands, request/response formats, structured output details
- [Supported Models](doc/MODELS.md) — tested models and implemented chat templates
