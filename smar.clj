#!/usr/bin/env bb

;; smar -- small agent harness
;; OpenAI-compatible CLI proxy for local LLM backends (ollama, koboldcpp, llama.cpp)

;; ---------------------------------------------------------------------------
;; Deps
;; ---------------------------------------------------------------------------

(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {metosin/malli {:mvn/version "0.16.4"}}})

(ns smar
  (:require [org.httpkit.client :as client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]
            [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def smar-version "0.5.0")

;; ---------------------------------------------------------------------------
;; Model presets
;; ---------------------------------------------------------------------------

(defn models-dir []
  (io/file
    (or (System/getenv "SMAR_MODELS_DIR")
        (str (System/getProperty "user.home") "/.local/smar/models"))))

(defn load-model-presets []
  (let [dir (models-dir)]
    (if (.isDirectory dir)
      (into {}
            (for [f (.listFiles dir)
                  :when (str/ends-with? (.getName f) ".edn")]
              (let [data (edn/read-string (slurp f))]
                [(:family data) data])))
      {})))

(def model-presets (load-model-presets))

(defn apply-model-preset [openai-req model-family]
  (if-let [preset (get model-presets model-family)]
    (merge (:defaults preset) openai-req)
    openai-req))


;; ---------------------------------------------------------------------------
;; Schema validation (malli)
;; ---------------------------------------------------------------------------

(defn json-schema->malli [json-schema]
  (let [t (get json-schema "type" (get json-schema :type))]
    (case t
      "string"  :string
      "number"  number?
      "integer" :int
      "boolean" :boolean
      "null"    nil?
      "array"   (let [items (get json-schema "items" (get json-schema :items))]
                  [:sequential (json-schema->malli items)])
      "object"  (let [props    (get json-schema "properties" (get json-schema :properties))
                      required (set (get json-schema "required" (get json-schema :required)))]
                  (into [:map]
                        (map (fn [[k v]]
                               (if (contains? required k)
                                 [(keyword k) (json-schema->malli v)]
                                 [(keyword k) {:optional true} (json-schema->malli v)])))
                        props))
      :any)))

(defn validate-response [json-schema response-str]
  (try
    (let [data   (json/parse-string response-str true)
          schema (json-schema->malli json-schema)]
      (if (m/validate schema data)
        {:valid true :data data}
        {:valid false
         :errors (me/humanize (m/explain schema data))}))
    (catch Exception e
      {:valid false :errors (str "JSON parse error: " (.getMessage e))})))

;; ---------------------------------------------------------------------------
;; Content extraction — strip chain-of-thought from structured responses
;; ---------------------------------------------------------------------------

(defn extract-json
  "Extract a JSON object or array from model output that may contain
   chain-of-thought reasoning or markdown fences. Handles patterns like:
     - Clean {...} / [...] (returned unchanged)
     - <channel|>{...}
     - CoT text followed by {...}
     - ```json\\n{...}\\n```
   Uses bracket matching to return the exact JSON span (no trailing garbage).
   Falls back to the original string if no valid JSON is found."
  [s]
  (letfn [(find-end [s start]
            ;; Return index just past the matching close bracket for the
            ;; opener at `start`, or nil. Respects string literals/escapes.
            (let [n     (count s)
                  open  (.charAt s start)
                  close (case open \{ \} \[ \])]
              (loop [i (inc start), depth 1, in-str false, escape false]
                (cond
                  (>= i n) nil
                  (and in-str escape) (recur (inc i) depth in-str false)
                  in-str (let [c (.charAt s i)]
                           (cond
                             (= c \\) (recur (inc i) depth in-str true)
                             (= c \") (recur (inc i) depth false false)
                             :else    (recur (inc i) depth in-str false)))
                  :else (let [c (.charAt s i)]
                          (cond
                            (= c \")   (recur (inc i) depth true false)
                            (= c open) (recur (inc i) (inc depth) false false)
                            (= c close) (if (= depth 1)
                                          (inc i)
                                          (recur (inc i) (dec depth) false false))
                            :else (recur (inc i) depth false false)))))))
          (extract-at [s start]
            (when-let [end (find-end s start)]
              (let [candidate (subs s start end)]
                (try (json/parse-string candidate) candidate
                     (catch Exception _ nil)))))
          (walk-back [s open-char]
            (loop [pos (str/last-index-of s open-char)]
              (when (and pos (>= pos 0))
                (or (extract-at s pos)
                    (recur (str/last-index-of s open-char (dec pos)))))))]
    (let [s (str/trim s)]
      (or
       (when (or (str/starts-with? s "{") (str/starts-with? s "["))
         (extract-at s 0))
       (when-let [idx (str/index-of s "<channel|>")]
         (let [after-idx (+ idx (count "<channel|>"))
               n         (count s)
               json-idx  (loop [i after-idx]
                           (if (and (< i n) (Character/isWhitespace (.charAt s i)))
                             (recur (inc i))
                             i))]
           (when (< json-idx n)
             (extract-at s json-idx))))
       (walk-back s \{)
       (walk-back s \[)
       s))))

;; ---------------------------------------------------------------------------
;; Tool call validation
;; ---------------------------------------------------------------------------

(defn tools->schema
  "Synthesise a JSON Schema (oneOf) that matches any valid tool call over `tools`.
   Used as the decode-time constraint on the `smar_tools` path."
  [tools]
  {:oneOf
   (mapv (fn [tool]
           (let [name   (get tool "name" (get tool :name))
                 params (or (get tool "parameters" (get tool :parameters))
                            {:type "object"})]
             {:type "object"
              :additionalProperties false
              :required ["name" "arguments"]
              :properties {"name"      {:const name}
                           "arguments" params}}))
         tools)})

(defn build-tools-system-prompt [tools]
  (str "You have access to the following tools:\n\n"
       (str/join "\n\n"
                 (map (fn [tool]
                        (str "Tool: " (get tool "name" (get tool :name)) "\n"
                             "Description: " (get tool "description" (get tool :description)) "\n"
                             "Parameters: " (json/generate-string
                                             (get tool "parameters" (get tool :parameters)))))
                      tools))
       "\n\nYou MUST respond with a JSON object in this exact format:\n"
       "{\"name\": \"<tool_name>\", \"arguments\": {<args matching the tool's parameters>}}\n"
       "Do NOT include any other text. Only output the JSON tool call."))

(defn validate-tool-call [tools response-str]
  (try
    (let [data       (json/parse-string response-str true)
          tool-name  (:name data)
          arguments  (:arguments data)
          tools-map  (into {} (map (fn [t]
                                     [(get t "name" (get t :name)) t])
                                   tools))]
      (cond
        (nil? tool-name)
        {:valid false :errors "Response missing 'name' field"}

        (not (contains? tools-map tool-name))
        {:valid false :errors (str "Unknown tool: " tool-name
                                   ". Available: " (str/join ", " (keys tools-map)))}

        (nil? arguments)
        {:valid false :errors "Response missing 'arguments' field"}

        :else
        (let [tool       (get tools-map tool-name)
              params     (get tool "parameters" (get tool :parameters))
              malli-schema (when params (json-schema->malli params))]
          (if (or (nil? malli-schema) (m/validate malli-schema arguments))
            {:valid true :tool-call {:name tool-name :arguments arguments}}
            {:valid false
             :errors (str "Invalid arguments for " tool-name ": "
                          (pr-str (me/humanize (m/explain malli-schema arguments))))}))))
    (catch Exception e
      {:valid false :errors (str "Failed to parse tool call JSON: " (.getMessage e))})))

(defn tool-call-response [model tool-call]
  {:id      (str "smar-" (System/currentTimeMillis))
   :object  "chat.completion"
   :created (quot (System/currentTimeMillis) 1000)
   :model   model
   :choices [{:index         0
              :message       {:role       "assistant"
                              :tool_calls [{:id       (str "call_" (System/currentTimeMillis))
                                            :type     "function"
                                            :function {:name      (:name tool-call)
                                                       :arguments (json/generate-string
                                                                   (:arguments tool-call))}}]}
              :finish_reason "tool_calls"}]})

;; ---------------------------------------------------------------------------
;; Backend detection & translation
;; ---------------------------------------------------------------------------

(defn probe-backend [base-url]
  (let [try-get (fn [path]
                  (try
                    (let [resp @(client/get (str base-url path)
                                            {:timeout 3000})]
                      (= 200 (:status resp)))
                    (catch Exception _ false)))]
    (cond
      (try-get "/api/tags")     :ollama
      (try-get "/api/v1/model") :koboldcpp
      :else                     :llamacpp)))

;; -- translate-request ------------------------------------------------------

(defmulti translate-request (fn [backend-type _req _schema] backend-type))

(defn- response-format-json-schema [schema]
  {:type "json_schema"
   :json_schema {:name "smar_response" :strict true :schema schema}})

(defmethod translate-request :ollama [_ req schema]
  {:url  "/api/chat"
   :body (cond-> {:model    (:model req)
                  :messages (:messages req)
                  :stream   (get req :stream false)
                  :options  (cond-> {}
                              (:temperature req)    (assoc :temperature (:temperature req))
                              (:max_tokens req)     (assoc :num_predict (:max_tokens req))
                              (:top_p req)          (assoc :top_p (:top_p req))
                              (:top_k req)          (assoc :top_k (:top_k req))
                              (:repeat_penalty req) (assoc :repeat_penalty (:repeat_penalty req)))}
           schema (assoc :format schema))})

(defmethod translate-request :llamacpp [_ req schema]
  {:url  "/v1/chat/completions"
   :body (cond-> req
           schema (assoc :response_format (response-format-json-schema schema)))})

(defmethod translate-request :koboldcpp [_ req schema]
  {:url  "/v1/chat/completions"
   :body (cond-> req
           schema (assoc :response_format (response-format-json-schema schema)))})

;; -- translate-response -----------------------------------------------------

(defmulti translate-response (fn [backend-type _resp] backend-type))

(defn openai-chat-response [model content]
  {:id      (str "smar-" (System/currentTimeMillis))
   :object  "chat.completion"
   :created (quot (System/currentTimeMillis) 1000)
   :model   model
   :choices [{:index         0
              :message       {:role "assistant" :content content}
              :finish_reason "stop"}]})

(defmethod translate-response :ollama [_ resp]
  (let [body (json/parse-string (:body resp) true)]
    (openai-chat-response
     (:model body)
     (get-in body [:message :content] ""))))

(defmethod translate-response :koboldcpp [_ resp]
  (json/parse-string (:body resp) true))

(defmethod translate-response :llamacpp [_ resp]
  (json/parse-string (:body resp) true))

;; -- list-models ------------------------------------------------------------

(defmulti list-models-remote (fn [backend-type _base-url] backend-type))

(defmethod list-models-remote :ollama [_ base-url]
  (let [resp @(client/get (str base-url "/api/tags") {:timeout 5000})
        body (json/parse-string (:body resp) true)]
    (mapv (fn [m] {:id (:name m) :object "model" :owned_by "ollama"})
          (:models body))))

(defmethod list-models-remote :koboldcpp [_ base-url]
  (let [resp @(client/get (str base-url "/api/v1/model") {:timeout 5000})
        body (json/parse-string (:body resp) true)]
    [{:id (:result body "koboldcpp") :object "model" :owned_by "koboldcpp"}]))

(defmethod list-models-remote :llamacpp [_ base-url]
  (try
    (let [resp @(client/get (str base-url "/v1/models") {:timeout 5000})
          body (json/parse-string (:body resp) true)]
      (or (:data body) [{:id "llamacpp" :object "model" :owned_by "llamacpp"}]))
    (catch Exception _
      [{:id "llamacpp" :object "model" :owned_by "llamacpp"}])))

;; ---------------------------------------------------------------------------
;; Structured output: strategy selection & retry
;; ---------------------------------------------------------------------------

(defn choose-strategy [strategy-override]
  (case strategy-override
    "validate" :validate
    :grammar))

(defn forward-request [base-url translated]
  (let [url  (str base-url (:url translated))
        body (json/generate-string (:body translated))]
    @(client/post url {:headers {"content-type" "application/json"}
                       :body    body
                       :timeout 30000})))

(defn complete-with-constraint
  "Unified loop for schema-constrained and tool-constrained completions.
   - `schema` is passed to translate-request (nil on the :validate path).
   - `validator` is called on extracted content; it returns {:valid bool :errors ... :data? ...}.
   - `on-valid` wraps a successful result (identity for schema, tool-call-response wrapper for tools)."
  [base-url backend-type req schema validator on-valid max-retries]
  (loop [attempt  0
         messages (:messages req)]
    (let [current-req (assoc req :messages messages)
          translated  (translate-request backend-type current-req schema)
          raw-resp    (forward-request base-url translated)
          openai-resp (translate-response backend-type raw-resp)
          raw-content (get-in openai-resp [:choices 0 :message :content] "")
          content     (extract-json raw-content)
          openai-resp (assoc-in openai-resp [:choices 0 :message :content] content)
          validation  (validator content)]
      (if (:valid validation)
        (on-valid openai-resp validation)
        (if (>= attempt max-retries)
          (assoc openai-resp
                 :smar_validation {:valid false :errors (:errors validation)})
          (recur (inc attempt)
                 (conj (vec messages)
                       {:role "assistant" :content content}
                       {:role "user"
                        :content (str "Your previous response was invalid. "
                                      "Errors: " (pr-str (:errors validation)) "\n"
                                      "Please try again.")})))))))

;; ---------------------------------------------------------------------------
;; Request handling
;; ---------------------------------------------------------------------------

(defn valid-strategy? [s]
  (or (nil? s) (contains? #{"grammar" "validate"} s)))

(defn valid-tools? [t]
  (boolean (and (sequential? t) (seq t))))

(defn valid-schema? [s]
  (map? s))

(defn extract-smar-fields [parsed-body]
  (let [target       (:smar_target parsed-body)
        schema       (:smar_schema parsed-body)
        tools        (:smar_tools parsed-body)
        model-family (:smar_model_family parsed-body)
        backend      (:smar_backend parsed-body)
        strategy     (:smar_strategy parsed-body)]
    (when target
      {:target       target
       :schema       schema
       :tools        tools
       :model-family model-family
       :backend      backend
       :strategy     strategy
       :body         (dissoc parsed-body :smar_target :smar_schema :smar_tools
                             :smar_model_family :smar_backend :smar_strategy)})))

(defn inject-tools-prompt [messages tools]
  (let [system-msg {:role "system" :content (build-tools-system-prompt tools)}]
    (vec (cons system-msg messages))))

(defn prepare-request [body model-family]
  (apply-model-preset body model-family))

;; ---------------------------------------------------------------------------
;; CLI — error output and dispatch
;; ---------------------------------------------------------------------------

(defn cli-error [exit-code message]
  (binding [*out* *err*]
    (println (json/generate-string {:error {:message message
                                            :type "invalid_request_error"}})))
  (System/exit exit-code))

(defn handle-preflight [json-str]
  (let [parsed (try (json/parse-string json-str true)
                    (catch Exception e
                      (cli-error 1 (str "Invalid JSON: " (.getMessage e)))))]
    (if-let [target (:smar_target parsed)]
      (let [backend-type (try (probe-backend target)
                              (catch Exception e
                                (cli-error 2 (str "Backend unreachable: " target
                                                  " — " (.getMessage e)))))
            models       (try (list-models-remote backend-type target)
                              (catch Exception _
                                []))]
        (println (json/generate-string {:backend_type (name backend-type)
                                        :target       target
                                        :models       models})))
      (cli-error 1 "Missing required field: smar_target"))))

(def valid-backends #{:ollama :koboldcpp :llamacpp})

(defn resolve-backend-type [target smar-backend]
  (if smar-backend
    (let [bt (keyword smar-backend)]
      (if (valid-backends bt)
        bt
        (cli-error 1 (str "Unknown smar_backend: " smar-backend
                          ". Must be one of: ollama, koboldcpp, llamacpp"))))
    (try (probe-backend target)
         (catch Exception e
           (cli-error 2 (str "Backend unreachable: " target
                             " — " (.getMessage e)))))))

(defn backend-call [f]
  (try (f)
       (catch Exception e
         (cli-error 2 (str "Backend error: " (.getMessage e))))))

(defn handle-complete []
  (let [input  (slurp *in*)
        parsed (try (json/parse-string input true)
                    (catch Exception e
                      (cli-error 1 (str "Invalid JSON on stdin: " (.getMessage e)))))]
    (if-let [{:keys [target schema tools model-family backend strategy body]}
             (extract-smar-fields parsed)]
      (do
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

          tools
          (let [backend-type  (resolve-backend-type target backend)
                openai-req    (-> (prepare-request body model-family)
                                  (update :messages inject-tools-prompt tools))
                strat         (choose-strategy strategy)
                constraint    (when (= strat :grammar) (tools->schema tools))
                validator     (fn [content] (validate-tool-call tools content))
                on-valid      (fn [_ validation]
                                (tool-call-response (:model openai-req)
                                                    (:tool-call validation)))
                response      (backend-call
                               #(complete-with-constraint target backend-type openai-req
                                                          constraint validator on-valid 3))]
            (println (json/generate-string response)))

          schema
          (let [backend-type (resolve-backend-type target backend)
                openai-req   (prepare-request body model-family)
                strat        (choose-strategy strategy)
                constraint   (when (= strat :grammar) schema)
                validator    (fn [content] (validate-response schema content))
                on-valid     (fn [openai-resp _] openai-resp)
                response     (backend-call
                              #(complete-with-constraint target backend-type openai-req
                                                         constraint validator on-valid 3))]
            (println (json/generate-string response)))

          :else
          (let [backend-type (resolve-backend-type target backend)
                openai-req   (prepare-request body model-family)
                translated   (translate-request backend-type openai-req nil)
                raw-resp     (backend-call #(forward-request target translated))
                response     (translate-response backend-type raw-resp)]
            (println (json/generate-string response)))))
      (cli-error 1 "Missing required field: smar_target"))))

;; ---------------------------------------------------------------------------
;; Self-test
;; ---------------------------------------------------------------------------

(defn run-self-test []
  (let [pass  (atom 0)
        fail  (atom 0)
        total (atom 0)]
    (letfn [(check [label pred]
              (swap! total inc)
              (if pred
                (do (swap! pass inc)
                    (println (str "  PASS " label)))
                (do (swap! fail inc)
                    (println (str "  FAIL " label)))))
            (section [title]
              (println)
              (println (str "-- " title " --")))]

      (section "Content extraction")
      (check "clean json passes through"
             (= "{\"a\":1}" (extract-json "{\"a\":1}")))
      (check "extracts json after channel tag"
             (= "{\"content\":\"hi\"}"
                (extract-json "* reasoning\n<channel|>{\"content\":\"hi\"}")))
      (check "extracts json after CoT"
             (= "{\"answer\":42}"
                (extract-json "Let me think about this...\n{\"answer\":42}")))
      (check "handles whitespace around json"
             (= "{\"x\":1}" (extract-json "  {\"x\":1}  ")))
      (check "returns original when no json found"
             (= "just plain text" (extract-json "just plain text")))
      (check "extracts json after markdown CoT"
             (= "{\"content\":\"hello\",\"expression\":\"neutral\"}"
                (extract-json (str "* Analyze the request\n"
                                   "* Formulate response\n"
                                   "<channel|>"
                                   "{\"content\":\"hello\",\"expression\":\"neutral\"}"))))
      (check "strips trailing markdown code fence"
             (= "{\"a\":1}"
                (extract-json "Here you go:\n```json\n{\"a\":1}\n```")))
      (check "strips trailing text after json"
             (= "{\"a\":1}"
                (extract-json "<channel|>{\"a\":1} please let me know if that works")))
      (check "respects braces inside string literals"
             (= "{\"msg\":\"has } brace\"}"
                (extract-json "prefix {\"msg\":\"has } brace\"}")))
      (check "extracts json array after CoT"
             (= "[1,2,3]"
                (extract-json "The list is:\n```\n[1,2,3]\n```")))

      (section "Schema validation")
      (let [schema {"type" "object"
                    "properties" {"name" {"type" "string"}
                                  "age"  {"type" "integer"}}
                    "required" ["name" "age"]}]
        (check "valid json passes"
               (:valid (validate-response schema "{\"name\":\"Alice\",\"age\":30}")))
        (check "invalid json fails"
               (not (:valid (validate-response schema "{\"name\":\"Alice\",\"age\":\"old\"}"))))
        (check "malformed json fails"
               (not (:valid (validate-response schema "not json")))))

      (section "Tool call validation")
      (let [tools [{"name" "get_weather"
                    "description" "Get weather"
                    "parameters" {"type" "object"
                                  "properties" {"city" {"type" "string"}}
                                  "required" ["city"]}}
                   {"name" "search"
                    "description" "Search the web"
                    "parameters" {"type" "object"
                                  "properties" {"query" {"type" "string"}}
                                  "required" ["query"]}}]]
        (check "valid tool call"
               (:valid (validate-tool-call tools
                         "{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Berlin\"}}")))
        (check "wrong tool name"
               (not (:valid (validate-tool-call tools
                              "{\"name\":\"unknown\",\"arguments\":{}}"))))
        (check "invalid arguments"
               (not (:valid (validate-tool-call tools
                              "{\"name\":\"get_weather\",\"arguments\":{\"city\":123}}"))))
        (check "missing name field"
               (not (:valid (validate-tool-call tools
                              "{\"arguments\":{\"city\":\"Berlin\"}}"))))
        (check "missing arguments field"
               (not (:valid (validate-tool-call tools
                              "{\"name\":\"get_weather\"}"))))
        (check "not json"
               (not (:valid (validate-tool-call tools
                              "I'll help you with the weather!"))))
        (check "valid second tool"
               (:valid (validate-tool-call tools
                         "{\"name\":\"search\",\"arguments\":{\"query\":\"weather Berlin\"}}"))))

      (section "Tools schema synthesis")
      (let [tools  [{"name" "get_weather"
                     "description" "Get weather"
                     "parameters" {"type" "object"
                                   "properties" {"city" {"type" "string"}}
                                   "required" ["city"]}}
                    {"name" "search"
                     "description" "Search"
                     "parameters" {"type" "object"
                                   "properties" {"query" {"type" "string"}}
                                   "required" ["query"]}}]
            schema (tools->schema tools)]
        (check "schema is oneOf"
               (and (map? schema) (vector? (:oneOf schema))))
        (check "one branch per tool"
               (= 2 (count (:oneOf schema))))
        (check "first branch pins name to const"
               (= "get_weather" (get-in schema [:oneOf 0 :properties "name" :const])))
        (check "first branch attaches parameters as arguments schema"
               (= {"type" "object"
                   "properties" {"city" {"type" "string"}}
                   "required" ["city"]}
                  (get-in schema [:oneOf 0 :properties "arguments"])))
        (check "branches forbid extra keys"
               (false? (get-in schema [:oneOf 0 :additionalProperties])))
        (check "branches require name and arguments"
               (= ["name" "arguments"] (get-in schema [:oneOf 0 :required]))))
      (let [tools  [{"name" "noop" "description" "." "parameters" nil}]
            schema (tools->schema tools)]
        (check "missing parameters defaults to object schema"
               (= {:type "object"}
                  (get-in schema [:oneOf 0 :properties "arguments"]))))
      (let [tools  [{:name "kw" :description "." :parameters {:type "object"}}]
            schema (tools->schema tools)]
        (check "keyword keys work as well as string keys"
               (= "kw" (get-in schema [:oneOf 0 :properties "name" :const]))))

      (section "Tools system prompt")
      (let [tools  [{"name" "test_tool" "description" "A test" "parameters" {"type" "object"}}]
            prompt (build-tools-system-prompt tools)]
        (check "prompt mentions tool name" (str/includes? prompt "test_tool"))
        (check "prompt mentions JSON format" (str/includes? prompt "\"name\"")))

      (section "Request translation (no schema)")
      (let [req    {:model "llama3" :messages [{:role "user" :content "hi"}]
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
                 (nil? (get-in llama [:body :response_format])))))

      (section "Request translation (with schema)")
      (let [req    {:model "llama3" :messages [{:role "user" :content "hi"}]}
            schema {:type "object" :properties {:x {:type "integer"}}}]
        (let [ollama (translate-request :ollama req schema)]
          (check "ollama :format equals schema"
                 (= schema (get-in ollama [:body :format]))))
        (let [llama (translate-request :llamacpp req schema)]
          (check "llamacpp :response_format type"
                 (= "json_schema" (get-in llama [:body :response_format :type])))
          (check "llamacpp :response_format schema"
                 (= schema (get-in llama [:body :response_format :json_schema :schema])))
          (check "llamacpp :response_format strict"
                 (true? (get-in llama [:body :response_format :json_schema :strict]))))
        (let [kobold (translate-request :koboldcpp req schema)]
          (check "koboldcpp :response_format schema"
                 (= schema (get-in kobold [:body :response_format :json_schema :schema])))
          (check "koboldcpp :response_format strict"
                 (true? (get-in kobold [:body :response_format :json_schema :strict])))))

      (section "Response translation")
      (let [resp {:body (json/generate-string
                         {:model "llama3"
                          :message {:role "assistant" :content "hello back"}})}
            result (translate-response :ollama resp)]
        (check "ollama response has choices"
               (= "hello back" (get-in result [:choices 0 :message :content]))))
      (let [resp {:body (json/generate-string
                         {:model "kobold"
                          :choices [{:index 0
                                     :message {:role "assistant" :content "kobold says hi"}
                                     :finish_reason "stop"}]})}
            result (translate-response :koboldcpp resp)]
        (check "koboldcpp response parses OpenAI-compat shape"
               (= "kobold says hi" (get-in result [:choices 0 :message :content]))))

      (section "Strategy selection")
      (check "default is grammar"
             (= :grammar (choose-strategy nil)))
      (check "\"grammar\" maps to :grammar"
             (= :grammar (choose-strategy "grammar")))
      (check "\"validate\" maps to :validate"
             (= :validate (choose-strategy "validate")))

      (section "Model presets")
      (check "presets loaded" (pos? (count model-presets)))
      (check "llama3 preset exists" (contains? model-presets "llama3"))
      (check "llama3 preset has temperature"
             (= 0.6 (get-in model-presets ["llama3" :defaults :temperature])))
      (let [req {:model "test" :messages [] :temperature 0.9}
            result (apply-model-preset req "llama3")]
        (check "preset applies defaults" (= 0.9 (get-in result [:top_p])))
        (check "request overrides preset" (= 0.9 (:temperature result))))
      (let [req {:model "test" :messages []}
            result (apply-model-preset req "llama3")]
        (check "preset fills missing temperature" (= 0.6 (:temperature result))))
      (let [req {:model "test"}
            result (apply-model-preset req "nonexistent")]
        (check "unknown family returns unchanged" (= req result)))

      (section "Smar fields extraction")
      (let [parsed {:smar_target "http://localhost:1234"
                    :smar_schema {"type" "object"}
                    :smar_tools  [{"name" "t"}]
                    :smar_model_family "llama3"
                    :smar_backend "ollama"
                    :smar_strategy "grammar"
                    :model "test" :messages []}
            result (extract-smar-fields parsed)]
        (check "extracts target" (= "http://localhost:1234" (:target result)))
        (check "extracts schema" (= {"type" "object"} (:schema result)))
        (check "extracts tools" (= [{"name" "t"}] (:tools result)))
        (check "extracts model-family" (= "llama3" (:model-family result)))
        (check "extracts backend" (= "ollama" (:backend result)))
        (check "extracts strategy" (= "grammar" (:strategy result)))
        (check "strips smar fields from body"
               (and (not (contains? (:body result) :smar_target))
                    (not (contains? (:body result) :smar_schema))
                    (not (contains? (:body result) :smar_tools))
                    (not (contains? (:body result) :smar_model_family))
                    (not (contains? (:body result) :smar_backend))
                    (not (contains? (:body result) :smar_strategy))
                    (= "test" (:model (:body result))))))

      (section "Input validation")
      (check "extract-smar-fields accepts valid strategy \"grammar\""
             (some? (extract-smar-fields {:smar_target "x" :smar_strategy "grammar"})))
      (check "extract-smar-fields accepts valid strategy \"validate\""
             (some? (extract-smar-fields {:smar_target "x" :smar_strategy "validate"})))
      (check "valid-tools? rejects empty vector"
             (false? (valid-tools? [])))
      (check "valid-tools? accepts non-empty vector"
             (true? (valid-tools? [{"name" "t" "parameters" {"type" "object"}}])))
      (check "valid-strategy? rejects unknown"
             (false? (valid-strategy? "llguidance")))
      (check "valid-strategy? accepts nil (default)"
             (true? (valid-strategy? nil)))
      (check "valid-schema? rejects nil"
             (false? (valid-schema? nil)))
      (check "valid-schema? rejects non-map"
             (false? (valid-schema? "json")))
      (check "valid-schema? accepts map"
             (true? (valid-schema? {:type "object"})))

      (section "CLI error formatting")
      (let [err-json (json/generate-string {:error {:message "test error"
                                                    :type "invalid_request_error"}})]
        (check "error json format"
               (= {:error {:message "test error" :type "invalid_request_error"}}
                  (json/parse-string err-json true))))

      (println)
      (let [p @pass f @fail t @total]
        (if (zero? f)
          (println (str p "/" t " passed"))
          (println (str p "/" t " passed, " f " failed"))))
      (println)
      (when (pos? @fail)
        (System/exit 1)))))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [cmd (first args)]
    (cond
      (= cmd "--version")
      (println (str "smar " smar-version))

      (= cmd "--self-test")
      (run-self-test)

      (= cmd "preflight")
      (if-let [json-str (second args)]
        (handle-preflight json-str)
        (cli-error 1 "Usage: smar preflight '<json>'"))

      (= cmd "complete")
      (handle-complete)

      :else
      (do
        (println "Usage: bb smar.clj <command>")
        (println)
        (println "Commands:")
        (println "  preflight '<json>'   Probe backend, list models")
        (println "  complete             Read request from stdin, write response to stdout")
        (println)
        (println "Flags:")
        (println "  --self-test          Run inline tests")
        (println "  --version            Print version")
        (System/exit 1)))))

;; Fires only when executed directly (bb smar.clj …), not when required as a lib.
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
