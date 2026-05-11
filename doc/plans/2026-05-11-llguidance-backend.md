# llguidance backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `:llguidance` backend type to smar that exposes Lark/regex constraints via a new `smar_grammar` request field, targeted at llama.cpp servers built with `-DLLAMA_LLGUIDANCE=ON`.

**Architecture:** Single-file change to `smar.clj`. The new backend derives from `:llamacpp` (Clojure's multimethod hierarchy), inheriting model listing, response translation, and the existing `smar_schema` / `smar_tools` paths. A new pure helper `wrap-llguidance-grammar` produces the wire string `"%llguidance {}\n<source>"`. `handle-complete` gains a `grammar` dispatch branch that calls the helper, sends a single non-retrying request, and returns the response. `handle-preflight` honors explicit `smar_backend: "llguidance"` and skips probing.

**Tech Stack:** Babashka (>= 1.12.215) with httpkit + cheshire + malli. No test framework — tests are inline `check` calls inside `(run-self-test)`. Run with `SMAR_MODELS_DIR=./models bb smar.clj --self-test`.

**Spec reference:** `doc/specs/2026-05-10-llguidance-backend-design.md`.

---

### Task 1: Bump version

**Files:**
- Modify: `smar.clj:26` (`smar-version` constant)

- [ ] **Step 1: Update the constant**

In `smar.clj`, change line 26 from:

```clojure
(def smar-version "0.4.0")
```

to:

```clojure
(def smar-version "0.5.0")
```

- [ ] **Step 2: Verify**

Run: `bb smar.clj --version`
Expected: `smar 0.5.0`

- [ ] **Step 3: Commit**

```bash
git add smar.clj
git commit -m "bump to 0.5.0 for llguidance backend"
```

---

### Task 2: Add `:llguidance` to backend set and derive from `:llamacpp`

**Files:**
- Modify: `smar.clj` — `valid-backends` constant, derive call, error message in `resolve-backend-type`, self-test

- [ ] **Step 1: Add failing self-test for backend membership and inheritance**

Open `smar.clj`. Find the `(section "Input validation")` block in `run-self-test` (around line 754). Add these checks at the end of that section, before the closing `(println)`:

```clojure
      (check ":llguidance is in valid-backends"
             (contains? valid-backends :llguidance))
      (check ":llguidance derives from :llamacpp"
             (isa? :llguidance :llamacpp))
```

- [ ] **Step 2: Run self-test to verify both checks fail**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | tail -20`
Expected: two `FAIL` lines for the new checks.

- [ ] **Step 3: Add `:llguidance` to `valid-backends`**

Locate `valid-backends` (around line 436). Change:

```clojure
(def valid-backends #{:ollama :koboldcpp :llamacpp})
```

to:

```clojure
(def valid-backends #{:ollama :koboldcpp :llamacpp :llguidance})
(derive :llguidance :llamacpp)
```

- [ ] **Step 4: Update the error message in `resolve-backend-type`**

In `resolve-backend-type` (around lines 438–444), change the error string from:

```clojure
". Must be one of: ollama, koboldcpp, llamacpp"
```

to:

```clojure
". Must be one of: ollama, koboldcpp, llamacpp, llguidance"
```

- [ ] **Step 5: Run self-test to verify both checks pass**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | tail -10`
Expected: full pass count (e.g. `XX/XX passed`), no `FAIL` lines.

- [ ] **Step 6: Commit**

```bash
git add smar.clj
git commit -m "add :llguidance backend type, derive from :llamacpp"
```

---

### Task 3: Add `valid-grammar?` predicate

**Files:**
- Modify: `smar.clj` — add predicate next to existing `valid-strategy?` etc., add self-tests

- [ ] **Step 1: Add failing self-tests for the predicate**

In `run-self-test`, in the `(section "Input validation")` block, add at the end:

```clojure
      (check "valid-grammar? rejects nil"
             (false? (valid-grammar? nil)))
      (check "valid-grammar? rejects empty string"
             (false? (valid-grammar? "")))
      (check "valid-grammar? rejects blank string"
             (false? (valid-grammar? "   ")))
      (check "valid-grammar? rejects non-string"
             (false? (valid-grammar? 42)))
      (check "valid-grammar? accepts non-empty string"
             (true? (valid-grammar? "start: \"X\"")))
```

- [ ] **Step 2: Run self-test to verify the new checks fail**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | tail -15`
Expected: 5 `FAIL` lines, all of the form `Could not resolve symbol: valid-grammar?` or similar runtime error.

(If a runtime error aborts the suite before the failure summary, that still counts as failing — proceed to step 3.)

- [ ] **Step 3: Implement the predicate**

Find the existing predicates `valid-strategy?`, `valid-tools?`, `valid-schema?` (around lines 376–383). Add immediately after `valid-schema?`:

```clojure
(defn valid-grammar? [g]
  (and (string? g) (not (str/blank? g))))
```

- [ ] **Step 4: Run self-test to verify all checks pass**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | tail -10`
Expected: full pass count, no `FAIL` lines.

- [ ] **Step 5: Commit**

```bash
git add smar.clj
git commit -m "add valid-grammar? predicate"
```

---

### Task 4: Thread `smar_grammar` through `extract-smar-fields`

**Files:**
- Modify: `smar.clj` — `extract-smar-fields` (lines 385–400), add self-tests

- [ ] **Step 1: Add failing self-tests for round-trip and stripping**

In `run-self-test`, in the `(section "Smar fields extraction")` block (around lines 730–752), find the `parsed` map and the existing `result` checks. Extend the `parsed` map to include `:smar_grammar`:

```clojure
      (let [parsed {:smar_target "http://localhost:1234"
                    :smar_schema {"type" "object"}
                    :smar_tools  [{"name" "t"}]
                    :smar_model_family "llama3"
                    :smar_backend "ollama"
                    :smar_strategy "grammar"
                    :smar_grammar "start: \"X\""
                    :model "test" :messages []}
            result (extract-smar-fields parsed)]
```

Add two new checks at the end of that `let` (alongside the existing `extracts ...` checks):

```clojure
        (check "extracts grammar" (= "start: \"X\"" (:grammar result)))
        (check "strips smar_grammar from body"
               (not (contains? (:body result) :smar_grammar)))
```

- [ ] **Step 2: Run self-test to verify the two checks fail**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | grep -E '(FAIL|PASS extracts grammar|PASS strips smar_grammar)' | head -10`
Expected: two FAIL lines for `extracts grammar` and `strips smar_grammar from body`.

- [ ] **Step 3: Update `extract-smar-fields`**

Replace the entire `extract-smar-fields` defn (lines 385–400) with:

```clojure
(defn extract-smar-fields [parsed-body]
  (let [target       (:smar_target parsed-body)
        schema       (:smar_schema parsed-body)
        tools        (:smar_tools parsed-body)
        model-family (:smar_model_family parsed-body)
        backend      (:smar_backend parsed-body)
        strategy     (:smar_strategy parsed-body)
        grammar      (:smar_grammar parsed-body)]
    (when target
      {:target       target
       :schema       schema
       :tools        tools
       :model-family model-family
       :backend      backend
       :strategy     strategy
       :grammar      grammar
       :body         (dissoc parsed-body :smar_target :smar_schema :smar_tools
                             :smar_model_family :smar_backend :smar_strategy
                             :smar_grammar)})))
```

- [ ] **Step 4: Run self-test to verify all checks pass**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | tail -5`
Expected: full pass count, no `FAIL` lines.

- [ ] **Step 5: Commit**

```bash
git add smar.clj
git commit -m "thread smar_grammar through extract-smar-fields"
```

---

### Task 5: Add `wrap-llguidance-grammar` helper

**Files:**
- Modify: `smar.clj` — add helper near other small helpers, add self-test

- [ ] **Step 1: Add failing self-test**

In `run-self-test`, add a new section *before* `(section "Strategy selection")` (around line 706):

```clojure
      (section "llguidance grammar wrapping")
      (check "wraps with %llguidance prefix and newline"
             (= "%llguidance {}\nstart: \"X\""
                (wrap-llguidance-grammar "start: \"X\"")))
      (check "preserves multi-line grammar source"
             (= "%llguidance {}\nstart: A\nA: \"a\""
                (wrap-llguidance-grammar "start: A\nA: \"a\"")))
```

- [ ] **Step 2: Run self-test to verify failure**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | tail -15`
Expected: error like `Could not resolve symbol: wrap-llguidance-grammar` (this is a hard runtime failure; that's fine).

- [ ] **Step 3: Implement the helper**

Find the `forward-request` defn (around line 336). Add immediately *before* it:

```clojure
(defn wrap-llguidance-grammar
  "Prepend the llguidance dispatch prefix llama.cpp expects on the
   `grammar` request field when built with -DLLAMA_LLGUIDANCE=ON."
  [source]
  (str "%llguidance {}\n" source))
```

- [ ] **Step 4: Run self-test to verify all checks pass**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | tail -5`
Expected: full pass count.

- [ ] **Step 5: Commit**

```bash
git add smar.clj
git commit -m "add wrap-llguidance-grammar helper"
```

---

### Task 6: Verify `:llguidance` inherits `translate-request` from `:llamacpp`

**Files:**
- Modify: `smar.clj` — add self-tests only (no implementation; `derive` from Task 2 should make this work)

- [ ] **Step 1: Add self-tests confirming inheritance**

In `run-self-test`, find the `(section "Request translation (no schema)")` block (around line 649). At the end of the existing `let` that defines `req`, add:

```clojure
        (let [llamacpp-out  (translate-request :llamacpp  req nil)
              llguidance-out (translate-request :llguidance req nil)]
          (check ":llguidance translate-request inherits from :llamacpp (no schema)"
                 (= llamacpp-out llguidance-out))))
```

Note: this is added inside the closing `)` of the existing `let`. Be careful with the parens — count from the existing `(let [llama (translate-request :llamacpp req nil)]` block: that block already ends with two `)` followed by the closing of the outer `(let [req ...])`. Place the new `let` between the inner llama-test block's close and the outer let's close.

If parenthesis nesting is unclear, replace the entire outer block (lines 649–669) with:

```clojure
      (section "Request translation (no schema)")
      (let [req {:model "llama3" :messages [{:role "user" :content "hi"}]
                 :temperature 0.5}]
        (let [ollama (translate-request :ollama req nil)]
          (check "ollama url" (= "/api/chat" (:url ollama)))
          (check "ollama passes messages" (= [{:role "user" :content "hi"}]
                                             (get-in ollama [:body :messages])))
          (check "ollama maps temperature" (= 0.5 (get-in ollama [:body :options :temperature])))
          (check "ollama has no :format when schema is nil"
                 (nil? (get-in ollama [:body :format]))))
        (let [kobold (translate-request :koboldcpp req nil)]
          (check "koboldcpp uses OpenAI-compat url" (= "/v1/chat/completions" (:url kobold)))
          (check "koboldcpp body has messages"
                 (= [{:role "user" :content "hi"}] (get-in kobold [:body :messages])))
          (check "koboldcpp has no :response_format when schema is nil"
                 (nil? (get-in kobold [:body :response_format]))))
        (let [llama (translate-request :llamacpp req nil)]
          (check "llamacpp url" (= "/v1/chat/completions" (:url llama)))
          (check "llamacpp passes body through" (= req (:body llama)))
          (check "llamacpp has no :response_format when schema is nil"
                 (nil? (get-in llama [:body :response_format]))))
        (let [llamacpp-out  (translate-request :llamacpp  req nil)
              llguidance-out (translate-request :llguidance req nil)]
          (check ":llguidance translate-request inherits from :llamacpp (no schema)"
                 (= llamacpp-out llguidance-out))))
```

- [ ] **Step 2: Run self-test to verify the new check passes**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | grep -E '(FAIL|llguidance translate-request inherits)'`
Expected: a single `PASS` line for the new check, no `FAIL` lines.

If it fails with a "no method" error, the `derive` in Task 2 didn't take effect; revisit Task 2.

- [ ] **Step 3: Add inheritance check for the schema path**

In the `(section "Request translation (with schema)")` block (around line 671), at the end of the outer `let`, add (mirroring the no-schema check):

```clojure
        (let [llamacpp-out  (translate-request :llamacpp  req schema)
              llguidance-out (translate-request :llguidance req schema)]
          (check ":llguidance translate-request inherits from :llamacpp (with schema)"
                 (= llamacpp-out llguidance-out))))
```

Place it inside the outer `(let [req ... schema ...])` block, alongside the existing `ollama`/`llama`/`kobold` sub-blocks.

- [ ] **Step 4: Run self-test**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | tail -5`
Expected: full pass count.

- [ ] **Step 5: Commit**

```bash
git add smar.clj
git commit -m "verify :llguidance inherits translate-request from :llamacpp"
```

---

### Task 7: Add three-way mutex check upfront in `handle-complete`

**Files:**
- Modify: `smar.clj` — `handle-complete` (lines 455–508)

This task only changes the validation gate; the new `grammar` dispatch branch comes in Task 8. The change here ensures any combination of `grammar` + `schema` / `tools` is rejected before either old branch executes.

- [ ] **Step 1: Update validation block**

In `handle-complete`, locate the validation block (lines 463–472):

```clojure
        (when-not (valid-strategy? strategy)
          (cli-error 1 (str "Invalid smar_strategy: " (pr-str strategy)
                            ". Must be \"grammar\" or \"validate\".")))
        (when (and tools (not (valid-tools? tools)))
          (cli-error 1 "smar_tools must be a non-empty array"))
        (when (and schema (not (valid-schema? schema)))
          (cli-error 1 "smar_schema must be a JSON Schema object"))
        (cond
          (and schema tools)
          (cli-error 1 "smar_schema and smar_tools are mutually exclusive")
```

Replace with:

```clojure
        (when-not (valid-strategy? strategy)
          (cli-error 1 (str "Invalid smar_strategy: " (pr-str strategy)
                            ". Must be \"grammar\" or \"validate\".")))
        (when (and tools (not (valid-tools? tools)))
          (cli-error 1 "smar_tools must be a non-empty array"))
        (when (and schema (not (valid-schema? schema)))
          (cli-error 1 "smar_schema must be a JSON Schema object"))
        (when (and grammar (not (valid-grammar? grammar)))
          (cli-error 1 "smar_grammar must be a non-empty string"))
        (when (and grammar schema)
          (cli-error 1 "smar_grammar and smar_schema are mutually exclusive"))
        (when (and grammar tools)
          (cli-error 1 "smar_grammar and smar_tools are mutually exclusive"))
        (when (and grammar (not= backend "llguidance"))
          (cli-error 1 "smar_grammar requires smar_backend: \"llguidance\""))
        (cond
          (and schema tools)
          (cli-error 1 "smar_schema and smar_tools are mutually exclusive")
```

Note: `grammar` is already destructured from the `extract-smar-fields` result in the existing `:keys` (Task 4 added it). If destructuring needs updating, do so now — find:

```clojure
      (if-let [{:keys [target schema tools model-family backend strategy body]}
               (extract-smar-fields parsed)]
```

and replace with:

```clojure
      (if-let [{:keys [target schema tools model-family backend strategy grammar body]}
               (extract-smar-fields parsed)]
```

- [ ] **Step 2: Smoke-test the validation manually (no self-test exists for handle-complete)**

```bash
echo '{"smar_target":"http://localhost:8080","smar_grammar":"start: \"X\"","model":"x","messages":[]}' \
  | SMAR_MODELS_DIR=./models bb smar.clj complete 2>&1 | head -5
```

Expected stderr: `{"error":{"message":"smar_grammar requires smar_backend: \"llguidance\"", ...}}`
Expected exit: non-zero. (Confirm with `echo $?`.)

- [ ] **Step 3: Verify mutex with schema**

```bash
echo '{"smar_target":"http://localhost:8080","smar_backend":"llguidance","smar_grammar":"start: \"X\"","smar_schema":{"type":"object"},"model":"x","messages":[]}' \
  | SMAR_MODELS_DIR=./models bb smar.clj complete 2>&1 | head -5
```

Expected stderr: `{"error":{"message":"smar_grammar and smar_schema are mutually exclusive", ...}}`

- [ ] **Step 4: Verify the existing self-test still passes**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | tail -5`
Expected: full pass count.

- [ ] **Step 5: Commit**

```bash
git add smar.clj
git commit -m "validate smar_grammar and reject conflicting modes upfront"
```

---

### Task 8: Implement the `grammar` dispatch branch in `handle-complete`

**Files:**
- Modify: `smar.clj` — `handle-complete` `cond` body (lines 470–507)

- [ ] **Step 1: Add the new branch**

In `handle-complete`, locate the `cond` (currently `(cond (and schema tools) ... tools ... schema ... :else ...)`). Add a new `grammar` clause as the first branch *after* the existing `(and schema tools)` rejection:

Find:

```clojure
        (cond
          (and schema tools)
          (cli-error 1 "smar_schema and smar_tools are mutually exclusive")

          tools
```

Insert between the `schema-tools mutex` clause and the `tools` clause:

```clojure
        (cond
          (and schema tools)
          (cli-error 1 "smar_schema and smar_tools are mutually exclusive")

          grammar
          (let [backend-type (resolve-backend-type target backend)
                openai-req   (prepare-request body model-family)
                translated   (-> (translate-request backend-type openai-req nil)
                                 (assoc-in [:body :grammar]
                                           (wrap-llguidance-grammar grammar)))
                raw-resp     (backend-call #(forward-request target translated))
                response     (translate-response backend-type raw-resp)]
            (println (json/generate-string response)))

          tools
```

(The rest of `cond` continues unchanged — `tools`, `schema`, `:else` branches stay as-is.)

- [ ] **Step 2: Smoke-test against the live llguidance server**

This step requires an llguidance-enabled llama.cpp running on `localhost:8080`. If it is, run:

```bash
echo '{"smar_target":"http://localhost:8080","smar_backend":"llguidance","smar_grammar":"start: \"PURPLE\" | \"ORANGE\"","model":"qwen3.6-27b","messages":[{"role":"user","content":"Pick one /no_think"}],"max_tokens":15,"temperature":0,"chat_template_kwargs":{"enable_thinking":false}}' \
  | SMAR_MODELS_DIR=./models bb smar.clj complete | head -c 400
```

Expected: a JSON response where `choices[0].message.content` is exactly `"PURPLE"` or `"ORANGE"` (nothing else). Example successful output snippet:

```json
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","content":"PURPLE"}}], ...}
```

If the server is not available, skip this step and note it; correctness has been verified by the upstream probes during design.

- [ ] **Step 3: Verify regex constraint also flows through**

```bash
echo '{"smar_target":"http://localhost:8080","smar_backend":"llguidance","smar_grammar":"start: /ORD-[0-9]{6}/","model":"qwen3.6-27b","messages":[{"role":"user","content":"Order ID? /no_think"}],"max_tokens":20,"temperature":0,"chat_template_kwargs":{"enable_thinking":false}}' \
  | SMAR_MODELS_DIR=./models bb smar.clj complete | head -c 400
```

Expected: content matches `/ORD-[0-9]{6}/` (e.g. `"ORD-202405"`).

- [ ] **Step 4: Verify self-test still passes**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | tail -5`
Expected: full pass count.

- [ ] **Step 5: Commit**

```bash
git add smar.clj
git commit -m "wire smar_grammar through handle-complete"
```

---

### Task 9: Honor explicit `:llguidance` opt-in in `handle-preflight`

**Files:**
- Modify: `smar.clj` — `handle-preflight` (lines 419–434)

- [ ] **Step 1: Update `handle-preflight` to skip probing when `:llguidance` is requested**

Replace the entire `handle-preflight` defn (lines 419–434) with:

```clojure
(defn handle-preflight [json-str]
  (let [parsed (try (json/parse-string json-str true)
                    (catch Exception e
                      (cli-error 1 (str "Invalid JSON: " (.getMessage e)))))]
    (if-let [target (:smar_target parsed)]
      (let [requested    (:smar_backend parsed)
            backend-type (if (= requested "llguidance")
                           :llguidance
                           (try (probe-backend target)
                                (catch Exception e
                                  (cli-error 2 (str "Backend unreachable: " target
                                                    " — " (.getMessage e))))))
            models       (try (list-models-remote backend-type target)
                              (catch Exception _
                                []))]
        (println (json/generate-string {:backend_type (name backend-type)
                                        :target       target
                                        :models       models})))
      (cli-error 1 "Missing required field: smar_target"))))
```

- [ ] **Step 2: Smoke-test default preflight**

```bash
SMAR_MODELS_DIR=./models bb smar.clj preflight '{"smar_target":"http://localhost:8080"}' | head -c 200
```

Expected: `"backend_type":"llamacpp"` (unchanged behaviour without opt-in).

- [ ] **Step 3: Smoke-test explicit llguidance preflight**

```bash
SMAR_MODELS_DIR=./models bb smar.clj preflight '{"smar_target":"http://localhost:8080","smar_backend":"llguidance"}' | head -c 200
```

Expected: `"backend_type":"llguidance"` and the same `models` list as without opt-in.

- [ ] **Step 4: Verify self-test still passes**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | tail -5`
Expected: full pass count.

- [ ] **Step 5: Commit**

```bash
git add smar.clj
git commit -m "honor smar_backend=\"llguidance\" in preflight"
```

---

### Task 10: Update documentation

**Files:**
- Modify: `doc/API.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Read current docs**

Run: `cat /home/soeren/repos/private/smar/doc/API.md` and skim. Look for the section listing `smar_*` request fields under `complete`.

- [ ] **Step 2: Add `smar_grammar` to `doc/API.md`**

In the `complete` command section, after the existing `smar_strategy` entry (or at the bottom of the field list, matching the existing format), add:

```markdown
- `smar_grammar` (string, requires `smar_backend: "llguidance"`):
  Lark or regular-expression source enforced as a decode-time constraint
  by [llguidance](https://github.com/guidance-ai/llguidance). smar wraps
  the value with the `%llguidance {}` prefix llama.cpp expects when built
  with `-DLLAMA_LLGUIDANCE=ON`. Mutually exclusive with `smar_schema` and
  `smar_tools`. No retries — the constraint is enforced at sample time.
```

In the `smar_backend` entry, extend the enum from `"ollama" | "koboldcpp" | "llamacpp"` to `"ollama" | "koboldcpp" | "llamacpp" | "llguidance"`. Add a sentence:

> `"llguidance"` is a llama.cpp build with `-DLLAMA_LLGUIDANCE=ON`; it can
> only be selected explicitly (no auto-detection).

- [ ] **Step 3: Update `CLAUDE.md` "CLI commands → complete" section**

In `CLAUDE.md`, find the bulleted list of `smar_*` modes under `complete`. After the existing `smar_strategy` bullet, add:

```markdown
- `smar_grammar` -> Lark/regex constraint via llguidance (requires `smar_backend: "llguidance"`)
```

Update the `smar_backend` bullet to include `llguidance`:

```markdown
- `smar_backend` -> skip backend probe, use given type (`ollama`, `koboldcpp`, `llamacpp`, `llguidance`)
```

- [ ] **Step 4: Update `CLAUDE.md` "Backend detection" section**

Find the line:

```markdown
Auto-detects by probing: `/api/tags` -> ollama, `/api/v1/model` -> koboldcpp, else -> llamacpp.
```

Add immediately after it:

```markdown
`:llguidance` cannot be auto-detected (no fingerprint on `/props`); clients
must opt in explicitly via `smar_backend: "llguidance"`. The backend
derives from `:llamacpp` and inherits all behaviours except that
`smar_grammar` is wrapped with the `%llguidance {}` prefix and sent on the
`grammar` field of `/v1/chat/completions`.
```

- [ ] **Step 5: Sanity-check the docs render**

Run: `head -60 /home/soeren/repos/private/smar/doc/API.md` and `head -40 /home/soeren/repos/private/smar/CLAUDE.md` — eyeball that nothing is mangled.

- [ ] **Step 6: Commit**

```bash
git add doc/API.md CLAUDE.md
git commit -m "document llguidance backend and smar_grammar"
```

---

## Final verification

- [ ] **Step 1: Full self-test pass**

Run: `SMAR_MODELS_DIR=./models bb smar.clj --self-test 2>&1 | tail -5`
Expected: e.g. `XX/XX passed`, no failures.

- [ ] **Step 2: Version reports correctly**

Run: `bb smar.clj --version`
Expected: `smar 0.5.0`

- [ ] **Step 3: End-to-end with the live llguidance server (if available)**

Run the full Lark + regex smoke tests from Task 8 again; they should still produce constrained output.

- [ ] **Step 4: Confirm `git log --oneline` shows clean, granular commits**

Run: `git log --oneline -12`
Expected: a sequence of small, focused commits (version bump, derive, predicate, extract-fields, helper, inheritance test, mutex, dispatch, preflight, docs).
