# smar API Reference

smar is a stateless CLI tool. Each invocation reads input, calls a backend, and writes output.

## Commands

### preflight

Probe a backend and list available models.

```bash
bb smar.bb.clj preflight '{"smar_target":"http://localhost:11434"}'
```

**Output (stdout):**

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

Read a request from stdin, forward to backend, write response to stdout.

```bash
echo '{"smar_target":"http://localhost:11434","model":"llama3","messages":[{"role":"user","content":"Hello"}]}' | bb smar.bb.clj complete
```

#### Plain completion

```json
{
  "smar_target": "http://localhost:11434",
  "model": "llama3",
  "messages": [
    {"role": "user", "content": "Hello"}
  ],
  "temperature": 0.7,
  "max_tokens": 512
}
```

#### Structured JSON output

```json
{
  "smar_target": "http://localhost:11434",
  "smar_schema": {
    "type": "object",
    "properties": {
      "name": {"type": "string"},
      "age": {"type": "integer"}
    },
    "required": ["name", "age"]
  },
  "model": "llama3",
  "messages": [
    {"role": "user", "content": "Give me a name and age"}
  ]
}
```

#### Tool calling

```json
{
  "smar_target": "http://localhost:11434",
  "smar_tools": [
    {
      "name": "get_weather",
      "description": "Get weather for a city",
      "parameters": {
        "type": "object",
        "properties": {
          "city": {"type": "string"}
        },
        "required": ["city"]
      }
    }
  ],
  "model": "llama3",
  "messages": [
    {"role": "user", "content": "What's the weather in Berlin?"}
  ]
}
```

### Request fields

| Field | Type | Required | Description |
|---|---|---|---|
| `smar_target` | string | yes | Backend URL (e.g. `http://localhost:11434`) |
| `smar_model_family` | string | no | Model family preset (e.g. `llama3`, `mistral`, `qwen3`) |
| `smar_schema` | object | no | JSON Schema for structured output enforcement |
| `smar_tools` | array | no | Tool definitions for tool call enforcement |
| `smar_backend` | string | no | Skip backend probe. One of: `ollama`, `koboldcpp`, `llamacpp` |
| `smar_strategy` | string | no | Override structured output strategy: `grammar` or `validate` |
| `model` | string | yes | Model name as reported by the backend |
| `messages` | array | yes | Array of `{role, content}` message objects |
| `temperature` | number | no | Sampling temperature |
| `top_p` | number | no | Nucleus sampling threshold |
| `top_k` | integer | no | Top-k sampling |
| `repeat_penalty` | number | no | Repetition penalty |
| `max_tokens` | integer | no | Max tokens to generate |

`smar_schema` and `smar_tools` are mutually exclusive. If both are present, smar exits with an error.

All `smar_*` fields are stripped from the body before forwarding to the backend.

### Tool definition format

Each tool in `smar_tools` is an object:

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Tool name (must be unique) |
| `description` | string | yes | Description shown to the LLM |
| `parameters` | object | yes | JSON Schema for the tool's arguments |

### Response (plain / schema mode)

```json
{
  "id": "smar-1710756000000",
  "object": "chat.completion",
  "created": 1710756000,
  "model": "llama3",
  "choices": [
    {
      "index": 0,
      "message": {"role": "assistant", "content": "Hello!"},
      "finish_reason": "stop"
    }
  ]
}
```

### Response (tool call mode)

```json
{
  "id": "smar-1710756000000",
  "object": "chat.completion",
  "created": 1710756000,
  "model": "llama3",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "tool_calls": [
          {
            "id": "call_1710756000000",
            "type": "function",
            "function": {
              "name": "get_weather",
              "arguments": "{\"city\":\"Berlin\"}"
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ]
}
```

smar validates that the LLM response is a valid tool call (correct tool name, arguments matching the parameter schema). If invalid, it retries up to 3 times. smar does **not** execute tools — it returns the validated tool call to the client.

## Structured Output Strategies

smar picks a strategy automatically based on backend type:

| Backend | Strategy | Mechanism |
|---|---|---|
| llama.cpp | grammar | GBNF grammar injected into request |
| KoboldCPP | grammar | GBNF grammar injected into request |
| Ollama | validate | Generate freely, validate with malli, retry on failure (up to 3 times) |

Override with the `smar_strategy` field in the request body (`grammar` or `validate`).

When validation fails after all retries, the response includes a `smar_validation` field:

```json
{
  "choices": [{"message": {"role": "assistant", "content": "..."}}],
  "smar_validation": {
    "valid": false,
    "errors": {"age": ["should be an integer"]}
  }
}
```

## Error Handling

Errors are written as JSON to stderr:

```json
{"error": {"message": "missing smar_target", "type": "invalid_request_error"}}
```

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | User/request error (missing fields, invalid input) |
| 2 | Backend unreachable |
