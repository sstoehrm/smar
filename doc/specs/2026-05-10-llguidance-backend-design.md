# llguidance backend — design

Status: approved 2026-05-10
Target version: smar 0.5.0

## Motivation

`llama.cpp` built with `-DLLAMA_LLGUIDANCE=ON` accepts Lark grammars and
regular expressions as decode-time constraints in addition to the standard
GBNF and JSON Schema paths. The wire shape is non-standard — the existing
`grammar` request field is overloaded and the integration is selected by a
`%llguidance {}` prefix on the grammar string. This is not part of upstream
llama.cpp's documented API surface, so a stock `:llamacpp` backend cannot
expose it without false positives on servers that do not have llguidance
compiled in.

We add a separate `:llguidance` backend type that mirrors `:llamacpp` for
all existing modes and adds a single new request field, `smar_grammar`, for
Lark/regex constraints.

## Wire format (verified against `b9095-f3c3e0e9a` with `LLAMA_LLGUIDANCE=ON`)

| Field on `/v1/chat/completions` | Effect |
|---|---|
| `grammar: "root ::= …"` | GBNF, parsed by stock llama.cpp |
| `grammar: "%llguidance {}\n<lark or regex>"` | Routed to llguidance |
| `response_format: {type: "json_schema", …}` | JSON Schema, accelerated by llguidance when present (transparent) |
| `llg_grammar`, `%lark` prefix, etc. | Silently ignored |

llguidance presence is **not** detectable from `/props`, `/v1/models`, or any
other read-only endpoint on the server we tested. Auto-detection would
require sending a probe constraint and inspecting the response — too
expensive and side-effecting for `preflight`. Therefore the new backend is
**explicit-only**: clients opt in via `smar_backend: "llguidance"`.

## API surface

### New request field

```
smar_grammar: "<Lark or regex source>"
```

A non-empty string. smar prepends `%llguidance {}\n` and sends the result
as the `grammar` field on `/v1/chat/completions`. The string is opaque to
smar — no parsing, no validation beyond non-emptiness.

### Mutual exclusion

`smar_grammar`, `smar_schema`, and `smar_tools` remain pairwise mutually
exclusive. Existing error path (`smar_schema and smar_tools are mutually
exclusive`) generalises to a three-way check.

### Backend gating

`smar_grammar` is only honored when `smar_backend: "llguidance"` is set.
Any other combination returns:

```
smar_grammar requires smar_backend: "llguidance"
```

Rationale: silently passing `grammar` through on `:llamacpp` would either
be misinterpreted as GBNF (different grammar dialect) or ignored on
`:ollama` / `:koboldcpp`. Failing loud is the only correct behaviour.

### Validation and retries

None. llguidance enforces the constraint at sample time, so the response
is guaranteed to conform to the grammar. The existing
`complete-with-constraint` retry loop is bypassed entirely on the
`smar_grammar` path. The response flows back through `translate-response`
and is returned verbatim.

### preflight

`smar_backend: "llguidance"` on `preflight` is honored: the probe step is
skipped (since llguidance can't be auto-detected) and the returned
`backend_type` is `"llguidance"`. Without explicit opt-in, preflight
continues to detect and return `"llamacpp"` against the same server.

### Other modes on `:llguidance`

- **Plain completion**: identical to `:llamacpp` (body forwarded to
  `/v1/chat/completions`).
- **`smar_schema`**: identical to `:llamacpp` — uses `response_format:
  {type: "json_schema", json_schema: {schema, strict: true}}`. llguidance
  accelerates this internally and may enforce richer schema features
  (regex `pattern`, full `enum`, deeper `oneOf`/`anyOf`) that stock
  llama.cpp's GBNF converter does not, but no smar code change is needed
  to benefit.
- **`smar_tools`**: identical to `:llamacpp` — uses the synthesised
  `oneOf` schema via `response_format`.
- **Model listing**: identical to `:llamacpp` (`/v1/models`).

## Implementation

Single file, `smar.clj`. Changes:

1. **Bump `smar-version` → `"0.5.0"`.**

2. **Backend set.** Extend `valid-backends` to include `:llguidance`:
   ```clojure
   (def valid-backends #{:ollama :koboldcpp :llamacpp :llguidance})
   ```
   Update the matching error message in `resolve-backend-type`.

3. **Inheritance.** Add `(derive :llguidance :llamacpp)` so all existing
   multimethods (`translate-response`, `list-models-remote`, the
   un-overridden `translate-request`) fall back to `:llamacpp`.

4. **No new `translate-request` override.** Because `:llguidance` derives
   from `:llamacpp`, plain and `smar_schema` requests already produce the
   correct translated body via inheritance. The grammar wrapping happens
   in `handle-complete` (see step 7), keeping the multimethod's existing
   3-arity signature untouched and avoiding metadata or out-of-band
   threading.

5. **Field extraction.** Add `:smar_grammar` to `extract-smar-fields`'s
   destructure and `dissoc` list. Returned map gains `:grammar`.

6. **Validators.** New predicate:
   ```clojure
   (defn valid-grammar? [g] (and (string? g) (not (str/blank? g))))
   ```

7. **`handle-complete` dispatch.** Add a new branch for `grammar` that
   inlines the wrapping (no helper, no retry loop):
   ```clojure
   grammar
   (let [backend-type (resolve-backend-type target backend)
         _ (when (not= backend-type :llguidance)
             (cli-error 1 "smar_grammar requires smar_backend: \"llguidance\""))
         openai-req (prepare-request body model-family)
         translated (-> (translate-request backend-type openai-req nil)
                        (assoc-in [:body :grammar]
                                  (str "%llguidance {}\n" grammar)))
         raw-resp   (backend-call #(forward-request target translated))
         response   (translate-response backend-type raw-resp)]
     (println (json/generate-string response)))
   ```
   The existing `tools` and `schema` branches gain a check rejecting
   coexistence with `grammar` (extending the current `schema and tools
   are mutually exclusive` error to a three-way check upfront).

8. **`handle-preflight`.** When `smar_backend` is `"llguidance"`, skip
   `probe-backend` and use `:llguidance` directly. Return
   `backend_type: "llguidance"`.

## Self-test additions

Under existing sections, add:

- **Input validation**:
  - `valid-grammar?` rejects `nil`, `""`, `"   "`, non-string.
  - `valid-grammar?` accepts non-empty string.
  - `valid-backends` contains `:llguidance`.

- **Smar fields extraction**:
  - `extract-smar-fields` round-trips `:smar_grammar`.
  - `:smar_grammar` is stripped from `:body`.

- **Request translation (llguidance)**:
  - `:llguidance` without grammar → translated body equals `:llamacpp`
    output for the same input (confirms inheritance works).
  - Wrapping is correct: given `g = "start: \"X\""`, the assembled
    `grammar` field equals `"%llguidance {}\nstart: \"X\""`.

- **Backend validation**: `resolve-backend-type` accepts
  `"llguidance"` and rejects `"llg"`.

## Documentation updates

- `doc/API.md`: add `smar_grammar` to the `complete` command's field list
  with an example. Add `llguidance` to the `smar_backend` enum.
- `CLAUDE.md` (project root): update "CLI commands → complete" to mention
  `smar_grammar`, update "Backend detection" to note that `:llguidance`
  is explicit-only.
- `doc/MODELS.md`: no changes (model presets are backend-agnostic).

## Out of scope

- Auto-detecting llguidance via a probe constraint.
- Exposing the raw `/completion` endpoint with grammar + stop tokens.
- Lark-based tool calling (rich tool-calling grammars). The existing
  JSON-Schema tool path already works on llguidance and gives clients a
  uniform API across backends.
- Companion regex validation for `smar_grammar` responses.
