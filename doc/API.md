# smar API Reference

Base URL: `http://localhost:<server-port>`

All endpoints are POST with JSON body. Every request must include `smar_target`.

## POST /smar/complete

Unified completion endpoint. Mode determined by request body fields.

### Plain completion

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

### Structured JSON output

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

### Tool calling

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
| `smar_schema` | object | no | JSON Schema for structured output enforcement |
| `smar_tools` | array | no | Tool definitions for tool call enforcement |
| `model` | string | yes | Model name as reported by the backend |
| `messages` | array | yes | Array of `{role, content}` message objects |
| `temperature` | number | no | Sampling temperature |
| `max_tokens` | integer | no | Max tokens to generate |

`smar_schema` and `smar_tools` are mutually exclusive. If both are present, the request returns 400.

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

## POST /smar/models

List available models from a backend.

**Request:**

```json
{
  "smar_target": "http://localhost:11434"
}
```

**Response:**

```json
{
  "object": "list",
  "data": [
    {"id": "llama3:8b", "object": "model", "owned_by": "ollama"}
  ]
}
```

## Structured Output Strategies

smar picks a strategy automatically based on backend capability:

| Backend | Strategy | Mechanism |
|---|---|---|
| llama.cpp | grammar | GBNF grammar injected into request |
| KoboldCPP | grammar | GBNF grammar injected into request |
| Ollama | validate | Generate freely, validate with malli, retry on failure (up to 3 times) |

Override with the `x-smar-strategy` header (`grammar` or `validate`).

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

## Errors

| Status | Cause |
|---|---|
| 400 | Missing `smar_target`, or both `smar_schema` and `smar_tools` present |
| 404 | Unknown route |
| 500 | Backend unreachable or internal error |
