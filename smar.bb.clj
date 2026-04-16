#!/usr/bin/env bb

;; smar -- small agent harness
;; OpenAI-compatible CLI proxy for local LLM backends (ollama, koboldcpp, llama.cpp)

;; ---------------------------------------------------------------------------
;; Deps
;; ---------------------------------------------------------------------------

(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {metosin/malli {:mvn/version "0.16.4"}}})

(require '[org.httpkit.client :as client]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[malli.core :as m]
         '[malli.error :as me])

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def smar-version "0.2.3")

;; ---------------------------------------------------------------------------
;; Model presets
;; ---------------------------------------------------------------------------

(defn load-model-presets []
  (let [dir (io/file "models")]
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

(defn get-preset-template [model-family]
  (when-let [preset (get model-presets model-family)]
    (:template preset)))

;; ---------------------------------------------------------------------------
;; Chat templates
;; ---------------------------------------------------------------------------

(def templates
  {:chatml
   {:bos    ""
    :eos    ""
    :start  (fn [role] (str "<|im_start|>" role "\n"))
    :end    "<|im_end|>\n"
    :suffix "<|im_start|>assistant\n"}

   :llama3
   {:bos    "<|begin_of_text|>"
    :eos    "<|end_of_text|>"
    :start  (fn [role] (str "<|start_header_id|>" role "<|end_header_id|>\n\n"))
    :end    "<|eot_id|>\n"
    :suffix "<|start_header_id|>assistant<|end_header_id|>\n\n"}

   :mistral
   {:bos    "<s>"
    :eos    "</s>"
    :start  (fn [role] (if (= role "user") "[INST] " ""))
    :end    (fn [role] (if (= role "user") " [/INST]" "</s>"))
    :suffix ""}

   :gemma2
   {:bos    "<bos>"
    :eos    ""
    :start  (fn [role] (str "<start_of_turn>"
                            (case role "assistant" "model" role)
                            "\n"))
    :end    "<end_of_turn>\n"
    :suffix "<start_of_turn>model\n"}

   :gemma4
   {:bos    ""
    :eos    ""
    :start  (fn [role] (str "<|turn>"
                            (case role "assistant" "model" role)
                            "\n"))
    :end    "<turn|>\n"
    :suffix "<|turn>model\n"}})

(defn apply-template [template-key messages]
  (let [tmpl (get templates template-key (:chatml templates))]
    (str (:bos tmpl)
         (apply str
                (for [{:keys [role content]} messages]
                  (let [start-fn (:start tmpl)
                        end-val  (:end tmpl)
                        start    (if (fn? start-fn) (start-fn role) start-fn)
                        end      (if (fn? end-val) (end-val role) end-val)]
                    (str start content end))))
         (:suffix tmpl))))

(defn detect-template-from-model [model-name]
  (let [lower (str/lower-case (or model-name ""))]
    (cond
      (str/includes? lower "llama-3")  :llama3
      (str/includes? lower "llama3")   :llama3
      (str/includes? lower "mistral")  :mistral
      (or (str/includes? lower "gemma-4")
          (str/includes? lower "gemma4"))  :gemma4
      (str/includes? lower "gemma")    :gemma2
      :else                            :chatml)))

;; ---------------------------------------------------------------------------
;; GBNF grammar generation from JSON schema
;; ---------------------------------------------------------------------------

(defn json-schema->gbnf [schema]
  (letfn [(type->rule [s path]
            (let [t    (get s "type" (get s :type))
                  enum (get s "enum" (get s :enum))]
              (if enum
                (str "(" (str/join " | "
                           (map (fn [v]
                                  (if (string? v)
                                    (str "\"\\\"" v "\\\"\"")
                                    (str "\"" v "\"")))
                                enum))
                     ")")
                (case t
                  "string"  "\"\\\"\" [^\"\\\\]* \"\\\"\" "
                "number"  "[\"-\"]? [0-9]+ (\".\" [0-9]+)?"
                "integer" "[\"-\"]? [0-9]+"
                "boolean" "(\"true\" | \"false\")"
                "null"    "\"null\""
                "array"   (let [items (get s "items" (get s :items))]
                            (str "\"[\" ws "
                                 (type->rule items (conj path "item"))
                                 " (\",\" ws " (type->rule items (conj path "item")) ")* "
                                 "ws \"]\""))
                "object"  (let [props (get s "properties" (get s :properties))
                                keys  (sort (keys props))]
                            (str "\"{\" ws "
                                 (str/join " \",\" ws "
                                           (map (fn [k]
                                                  (str "\"\\\"" (name k) "\\\":\" ws "
                                                       (type->rule (get props k) (conj path (name k)))))
                                                keys))
                                 " ws \"}\""))
                  ;; fallback
                  "[^\\x00]*"))))]
    (str "root ::= " (type->rule schema []) "\n"
         "ws ::= [ \\t\\n]*\n")))

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
   chain-of-thought reasoning. Handles patterns like:
     - <channel|>{...}
     - CoT text followed by {...}
     - Clean JSON (returned as-is)"
  [s]
  (let [s (str/trim s)]
    (or
     ;; Fast path: clean JSON
     (when (or (str/starts-with? s "{") (str/starts-with? s "["))
       (try (json/parse-string s) s (catch Exception _ nil)))
     ;; <channel|>{...} pattern
     (when-let [idx (str/index-of s "<channel|>")]
       (let [after (str/trim (subs s (+ idx (count "<channel|>"))))]
         (try (json/parse-string after) after (catch Exception _ nil))))
     ;; Last JSON object in text: find last { and try to parse from there
     (loop [search-from (str/last-index-of s "{")]
       (when (and search-from (>= search-from 0))
         (let [candidate (subs s search-from)]
           (if (try (json/parse-string candidate) true (catch Exception _ false))
             candidate
             (recur (str/last-index-of s "{" (dec search-from)))))))
     ;; Last JSON array in text
     (loop [search-from (str/last-index-of s "[")]
       (when (and search-from (>= search-from 0))
         (let [candidate (subs s search-from)]
           (if (try (json/parse-string candidate) true (catch Exception _ false))
             candidate
             (recur (str/last-index-of s "[" (dec search-from)))))))
     ;; Nothing found — return original
     s)))

;; ---------------------------------------------------------------------------
;; Tool call validation
;; ---------------------------------------------------------------------------

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

(defmulti translate-request (fn [backend-type _req] backend-type))

(defmethod translate-request :ollama [_ req]
  {:url    "/api/chat"
   :body   {:model    (:model req)
            :messages (:messages req)
            :stream   (get req :stream false)
            :options  (cond-> {}
                        (:temperature req)    (assoc :temperature (:temperature req))
                        (:max_tokens req)     (assoc :num_predict (:max_tokens req))
                        (:top_p req)          (assoc :top_p (:top_p req))
                        (:top_k req)          (assoc :top_k (:top_k req))
                        (:repeat_penalty req) (assoc :repeat_penalty (:repeat_penalty req)))}})

(defmethod translate-request :koboldcpp [_ req]
  (let [template-key (or (:smar_template req) (detect-template-from-model (:model req)))
        prompt       (apply-template template-key (:messages req))]
    {:url  "/api/v1/generate"
     :body (cond-> {:prompt     prompt
                    :max_length (or (:max_tokens req) 512)}
             (:temperature req)    (assoc :temperature (:temperature req))
             (:top_p req)          (assoc :top_p (:top_p req))
             (:top_k req)          (assoc :top_k (:top_k req))
             (:repeat_penalty req) (assoc :rep_pen (:repeat_penalty req)))}))

(defmethod translate-request :llamacpp [_ req]
  {:url  "/v1/chat/completions"
   :body req})

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
  (let [body    (json/parse-string (:body resp) true)
        content (get-in body [:results 0 :text] "")]
    (openai-chat-response "koboldcpp" content)))

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

;; -- supports-grammar? ------------------------------------------------------

(defmulti supports-grammar? identity)
(defmethod supports-grammar? :llamacpp [_] true)
(defmethod supports-grammar? :koboldcpp [_] true)
(defmethod supports-grammar? :ollama [_] false)

;; ---------------------------------------------------------------------------
;; Structured output: strategy selection & retry
;; ---------------------------------------------------------------------------

(defn choose-strategy [backend-type strategy-override]
  (cond
    (= strategy-override "grammar")  :grammar
    (= strategy-override "validate") :validate
    (supports-grammar? backend-type) :grammar
    :else :validate))

(defn inject-grammar [translated-req json-schema]
  (let [grammar (json-schema->gbnf json-schema)]
    (update translated-req :body assoc :grammar grammar)))

(defn forward-request [base-url translated]
  (let [url  (str base-url (:url translated))
        body (json/generate-string (:body translated))]
    @(client/post url {:headers {"content-type" "application/json"}
                       :body    body
                       :timeout 30000})))

(defn complete-with-validation [base-url backend-type req json-schema max-retries]
  (loop [attempt 0
         messages (:messages req)]
    (let [current-req  (assoc req :messages messages)
          translated   (translate-request backend-type current-req)
          raw-resp     (forward-request base-url translated)
          openai-resp  (translate-response backend-type raw-resp)
          raw-content  (get-in openai-resp [:choices 0 :message :content] "")
          content      (extract-json raw-content)
          openai-resp  (assoc-in openai-resp [:choices 0 :message :content] content)
          validation   (validate-response json-schema content)]
      (if (:valid validation)
        openai-resp
        (if (>= attempt max-retries)
          (assoc openai-resp
                 :smar_validation {:valid false :errors (:errors validation)})
          (recur (inc attempt)
                 (conj (vec messages)
                       {:role "assistant" :content content}
                       {:role "user"
                        :content (str "Your response did not match the required schema. "
                                      "Errors: " (pr-str (:errors validation)) "\n"
                                      "Please try again and respond with valid JSON.")})))))))

(defn complete-with-tool-validation [base-url backend-type req tools max-retries]
  (loop [attempt 0
         messages (:messages req)]
    (let [current-req  (assoc req :messages messages)
          translated   (translate-request backend-type current-req)
          raw-resp     (forward-request base-url translated)
          openai-resp  (translate-response backend-type raw-resp)
          content      (extract-json (get-in openai-resp [:choices 0 :message :content] ""))
          validation   (validate-tool-call tools content)]
      (if (:valid validation)
        (tool-call-response (:model req) (:tool-call validation))
        (if (>= attempt max-retries)
          (assoc openai-resp
                 :smar_validation {:valid false :errors (:errors validation)})
          (recur (inc attempt)
                 (conj (vec messages)
                       {:role "assistant" :content content}
                       {:role "user"
                        :content (str "Your response was not a valid tool call. "
                                      "Error: " (:errors validation) "\n"
                                      "You MUST respond with ONLY a JSON object: "
                                      "{\"name\": \"<tool_name>\", \"arguments\": {...}}")})))))))

;; ---------------------------------------------------------------------------
;; Request handling
;; ---------------------------------------------------------------------------

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
  (let [preset-template (get-preset-template model-family)
        req             (apply-model-preset body model-family)]
    (if preset-template
      (assoc req :smar_template preset-template)
      req)))

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
      (cond
        (and schema tools)
        (cli-error 1 "smar_schema and smar_tools are mutually exclusive")

        tools
        (let [backend-type (resolve-backend-type target backend)
              openai-req   (-> (prepare-request body model-family)
                               (update :messages inject-tools-prompt tools))
              response     (backend-call
                            #(complete-with-tool-validation target backend-type openai-req tools 3))]
          (println (json/generate-string response)))

        schema
        (let [backend-type (resolve-backend-type target backend)
              openai-req   (prepare-request body model-family)
              strat        (choose-strategy backend-type strategy)]
          (cond
            (= strat :grammar)
            (let [translated (-> (translate-request backend-type openai-req)
                                 (inject-grammar schema))
                  raw-resp   (backend-call #(forward-request target translated))
                  response   (translate-response backend-type raw-resp)
                  content    (get-in response [:choices 0 :message :content] "")
                  response   (assoc-in response [:choices 0 :message :content]
                                       (extract-json content))]
              (println (json/generate-string response)))

            :else
            (let [response (backend-call
                            #(complete-with-validation target backend-type openai-req schema 3))]
              (println (json/generate-string response)))))

        :else
        (let [backend-type (resolve-backend-type target backend)
              openai-req   (prepare-request body model-family)
              translated   (translate-request backend-type openai-req)
              raw-resp     (backend-call #(forward-request target translated))
              response     (translate-response backend-type raw-resp)]
          (println (json/generate-string response))))
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

      (section "Chat templates")
      (let [result (apply-template :chatml [{:role "user" :content "hello"}])]
        (check "chatml wraps user message"
               (and (str/includes? result "<|im_start|>user")
                    (str/includes? result "hello")
                    (str/includes? result "<|im_end|>"))))
      (let [result (apply-template :llama3 [{:role "user" :content "hi"}])]
        (check "llama3 template"
               (and (str/includes? result "<|begin_of_text|>")
                    (str/includes? result "<|start_header_id|>user"))))
      (let [result (apply-template :gemma2 [{:role "user" :content "hi"}
                                             {:role "assistant" :content "hello"}])]
        (check "gemma2 template uses start_of_turn"
               (and (str/includes? result "<bos>")
                    (str/includes? result "<start_of_turn>user")
                    (str/includes? result "<start_of_turn>model\nhello")
                    (str/includes? result "<end_of_turn>")
                    (str/ends-with? result "<start_of_turn>model\n"))))
      (let [result (apply-template :gemma4 [{:role "system" :content "sys"}
                                             {:role "user" :content "hi"}
                                             {:role "assistant" :content "hello"}])]
        (check "gemma4 template uses turn delimiters"
               (and (str/includes? result "<|turn>system\nsys<turn|>")
                    (str/includes? result "<|turn>user\nhi<turn|>")
                    (str/includes? result "<|turn>model\nhello<turn|>")
                    (str/ends-with? result "<|turn>model\n"))))
      (check "detect llama3 model"
             (= :llama3 (detect-template-from-model "meta-llama3-8b")))
      (check "detect mistral model"
             (= :mistral (detect-template-from-model "Mistral-7B")))
      (check "detect gemma-4 model"
             (= :gemma4 (detect-template-from-model "gemma-4-26B-A4B-it")))
      (check "detect gemma4 model (no dash)"
             (= :gemma4 (detect-template-from-model "gemma4:e4b")))
      (check "detect gemma-2 model"
             (= :gemma2 (detect-template-from-model "gemma-2-9b-it")))
      (check "detect fallback to chatml"
             (= :chatml (detect-template-from-model "some-random-model")))

      (section "GBNF generation")
      (let [gbnf (json-schema->gbnf {"type" "object"
                                     "properties" {"name" {"type" "string"}
                                                   "age"  {"type" "integer"}}})]
        (check "gbnf has root rule" (str/includes? gbnf "root ::="))
        (check "gbnf has ws rule" (str/includes? gbnf "ws ::="))
        (check "gbnf references name field" (str/includes? gbnf "name"))
        (check "gbnf references age field" (str/includes? gbnf "age")))
      ;; keyword keys should produce clean JSON keys (no leading colons)
      (let [gbnf (json-schema->gbnf {:type "object"
                                     :properties {:content {:type "string"}
                                                  :mood    {:type "string"}}})]
        (check "keyword keys have no colons"
               (and (str/includes? gbnf "content")
                    (not (str/includes? gbnf ":content"))
                    (str/includes? gbnf "mood")
                    (not (str/includes? gbnf ":mood")))))
      ;; enum values should be constrained
      (let [gbnf (json-schema->gbnf {"type" "object"
                                     "properties" {"color" {"type" "string"
                                                            "enum" ["red" "blue" "green"]}}})]
        (check "enum generates alternation"
               (and (str/includes? gbnf "red")
                    (str/includes? gbnf "blue")
                    (str/includes? gbnf "green")
                    (str/includes? gbnf "|"))))

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

      (section "Tools system prompt")
      (let [tools  [{"name" "test_tool" "description" "A test" "parameters" {"type" "object"}}]
            prompt (build-tools-system-prompt tools)]
        (check "prompt mentions tool name" (str/includes? prompt "test_tool"))
        (check "prompt mentions JSON format" (str/includes? prompt "\"name\"")))

      (section "Request translation")
      (let [req {:model "llama3" :messages [{:role "user" :content "hi"}]
                 :temperature 0.5}]
        (let [ollama (translate-request :ollama req)]
          (check "ollama url" (= "/api/chat" (:url ollama)))
          (check "ollama passes messages" (= [{:role "user" :content "hi"}]
                                             (get-in ollama [:body :messages])))
          (check "ollama maps temperature" (= 0.5 (get-in ollama [:body :options :temperature]))))
        (let [kobold (translate-request :koboldcpp req)]
          (check "koboldcpp url" (= "/api/v1/generate" (:url kobold)))
          (check "koboldcpp has prompt" (string? (get-in kobold [:body :prompt]))))
        (let [llama (translate-request :llamacpp req)]
          (check "llamacpp url" (= "/v1/chat/completions" (:url llama)))
          (check "llamacpp passes body through" (= req (:body llama)))))

      (section "Response translation")
      (let [resp {:body (json/generate-string
                         {:model "llama3"
                          :message {:role "assistant" :content "hello back"}})}
            result (translate-response :ollama resp)]
        (check "ollama response has choices"
               (= "hello back" (get-in result [:choices 0 :message :content]))))
      (let [resp {:body (json/generate-string
                         {:results [{:text "kobold says hi"}]})}
            result (translate-response :koboldcpp resp)]
        (check "koboldcpp response extracts text"
               (= "kobold says hi" (get-in result [:choices 0 :message :content]))))

      (section "Strategy selection")
      (check "grammar when supported"
             (= :grammar (choose-strategy :llamacpp nil)))
      (check "validate when not supported"
             (= :validate (choose-strategy :ollama nil)))
      (check "override to validate"
             (= :validate (choose-strategy :llamacpp "validate")))
      (check "override to grammar"
             (= :grammar (choose-strategy :ollama "grammar")))

      (section "Model presets")
      (check "presets loaded" (pos? (count model-presets)))
      (check "llama3 preset exists" (contains? model-presets "llama3"))
      (check "llama3 preset has temperature"
             (= 0.6 (get-in model-presets ["llama3" :defaults :temperature])))
      (check "llama3 preset has template"
             (= :llama3 (get-in model-presets ["llama3" :template])))
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
      (check "preset template for llama3" (= :llama3 (get-preset-template "llama3")))
      (check "preset template for unknown is nil" (nil? (get-preset-template "nonexistent")))

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
        (println "Usage: bb smar.bb.clj <command>")
        (println)
        (println "Commands:")
        (println "  preflight '<json>'   Probe backend, list models")
        (println "  complete             Read request from stdin, write response to stdout")
        (println)
        (println "Flags:")
        (println "  --self-test          Run inline tests")
        (println "  --version            Print version")
        (System/exit 1)))))

(apply -main *command-line-args*)
