# Supported Models

Models tested and maintained with smar. Backend type is auto-detected.

| Model | Backend | Template | Structured Output | Notes |
|---|---|---|---|---|

*No models tested yet.*

## Chat Templates

Templates currently implemented in smar. Any model using one of these formats should work.

- **ChatML** — default fallback (e.g. Qwen, Yi, OpenHermes, Dolphin)
- **Llama 3** — Meta Llama 3.x family
- **Mistral** — Mistral / Mixtral family
- **Gemma 2** — Google Gemma 2 / 3 family (`<start_of_turn>` / `<end_of_turn>`, `assistant` → `model`)
- **Gemma 4** — Google Gemma 4 family (`<|turn>` / `<turn|>`, `assistant` → `model`). To enable thinking mode, include `<|think|>` in the system prompt; smar's structured-output paths strip the `<channel|>` wrapper automatically.

Auto-detection matches on substrings in the model name: `llama-3`/`llama3`, `mistral`, `gemma-4`/`gemma4`, and `gemma` (any other gemma → gemma 2 template).
