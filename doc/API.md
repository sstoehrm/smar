# smar API Reference

All endpoints follow the OpenAI API format. Assume `http://localhost:<server-port>` as the base URL.

## POST /v1/chat/completions

Chat completion. The primary endpoint.

**Request body:**

```json
{
  "model": "llama3",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user", "content": "Hello"}
  ],
  "temperature": 0.7,
  "max_tokens": 512,
  "stream": false,
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "schema": {
        "type": "object",
        "properties": {
          "answer": {"type": "string"}
        },
        "required": ["answer"]
      }
    }
  }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `model` | string | yes | Model name as reported by the backend |
| `messages` | array | yes | Array of `{role, content}` message objects |
| `temperature` | number | no | Sampling temperature |
| `max_tokens` | integer | no | Max tokens to generate |
| `stream` | boolean | no | Streaming (ollama only, default `false`) |
| `response_format` | object | no | Structured output enforcement (see below) |

**Response:**

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

## POST /v1/completions

Raw completion. Messages are formatted using a chat template (auto-detected from model name, fallback to ChatML).

**Request body:**

```json
{
  "model": "llama3",
  "prompt": "Once upon a time",
  "temperature": 0.7,
  "max_tokens": 256
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `model` | string | yes | Model name |
| `prompt` | string | yes | Raw prompt text |
| `temperature` | number | no | Sampling temperature |
| `max_tokens` | integer | no | Max tokens to generate |

**Response:** Same format as `/v1/chat/completions`.

## GET /v1/models

List available models from the backend.

**Response:**

```json
{
  "object": "list",
  "data": [
    {"id": "llama3:8b", "object": "model", "owned_by": "ollama"}
  ]
}
```

## GET /admin/health

Backend health check.

**Response:**

```json
{
  "status": "ok",
  "backend_type": "ollama",
  "base_url": "http://localhost:11434"
}
```

## Structured Output

Request structured output by including `response_format` in the chat completions request body. Set `type` to `"json_schema"` and provide a JSON Schema under `json_schema.schema`.

smar picks a strategy automatically based on backend capability:

| Backend | Strategy | Mechanism |
|---|---|---|
| llama.cpp | grammar | GBNF grammar injected into request |
| KoboldCPP | grammar | GBNF grammar injected into request |
| Ollama | validate | Generate freely, validate with malli, retry on failure (up to 3 times) |

Override the strategy with the `x-smar-strategy` header:

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "x-smar-strategy: validate" \
  -d '{"model":"llama3","messages":[{"role":"user","content":"give me a name and age"}],"response_format":{"type":"json_schema","json_schema":{"schema":{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}},"required":["name","age"]}}}}'
```

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

Unknown routes return:

```json
{"error": {"message": "Not found", "type": "invalid_request_error"}}
```

with HTTP status `404`.
