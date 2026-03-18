#!/usr/bin/env bb

;; smar — small agent harness
;; OpenAI-compatible proxy for local LLM backends (ollama, koboldcpp, llama.cpp)

;; ---------------------------------------------------------------------------
;; Deps
;; ---------------------------------------------------------------------------

(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {metosin/malli {:mvn/version "0.16.4"}}})

(require '[org.httpkit.server :as http]
         '[org.httpkit.client :as client]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[malli.core :as m]
         '[malli.error :as me]
         '[malli.json-schema :as mjs])

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
    :suffix ""}})

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
      :else                            :chatml)))

;; ---------------------------------------------------------------------------
;; GBNF grammar generation from JSON schema
;; ---------------------------------------------------------------------------

(defn json-schema->gbnf [schema]
  (letfn [(type->rule [s path]
            (let [t (get s "type" (get s :type))]
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
                                                  (str "\"\\\"" k "\\\":\" ws "
                                                       (type->rule (get props k) (conj path k))))
                                                keys))
                                 " ws \"}\""))
                ;; fallback
                "[^\\x00]*")))]
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
;; Backend detection & translation
;; ---------------------------------------------------------------------------

(def backend-state (atom nil))

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

(defn get-backend [base-url]
  (if-let [b @backend-state]
    b
    (let [detected (probe-backend base-url)]
      (reset! backend-state {:type detected :base-url base-url})
      @backend-state)))

;; -- translate-request ------------------------------------------------------

(defmulti translate-request (fn [backend-type _req] backend-type))

(defmethod translate-request :ollama [_ req]
  {:url    "/api/chat"
   :body   {:model    (:model req)
            :messages (:messages req)
            :stream   (get req :stream false)
            :options  (cond-> {}
                        (:temperature req) (assoc :temperature (:temperature req))
                        (:max_tokens req)  (assoc :num_predict (:max_tokens req)))}})

(defmethod translate-request :koboldcpp [_ req]
  (let [template-key (detect-template-from-model (:model req))
        prompt       (apply-template template-key (:messages req))]
    {:url  "/api/v1/generate"
     :body (cond-> {:prompt     prompt
                    :max_length (or (:max_tokens req) 512)}
             (:temperature req) (assoc :temperature (:temperature req)))}))

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

(defn choose-strategy [backend-type request-headers]
  (let [header (get request-headers "x-smar-strategy")]
    (cond
      (= header "grammar")  :grammar
      (= header "validate") :validate
      (supports-grammar? backend-type) :grammar
      :else :validate)))

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
          content      (get-in openai-resp [:choices 0 :message :content] "")
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

;; ---------------------------------------------------------------------------
;; Request handling
;; ---------------------------------------------------------------------------

(defn parse-body [req]
  (when-let [body (:body req)]
    (json/parse-string (slurp body) true)))

(defn json-response [status body]
  {:status  status
   :headers {"content-type" "application/json"}
   :body    (json/generate-string body)})

(defn get-response-schema [openai-req]
  (when-let [rf (:response_format openai-req)]
    (when (= (:type rf) "json_schema")
      (or (:schema rf)
          (get-in rf [:json_schema :schema])))))

(defn handle-chat-completions [base-url req]
  (let [backend      (get-backend base-url)
        backend-type (:type backend)
        openai-req   (parse-body req)
        json-schema  (get-response-schema openai-req)
        headers      (into {} (map (fn [[k v]] [(str/lower-case (name k)) v])
                                   (:headers req)))
        strategy     (when json-schema
                       (choose-strategy backend-type headers))]
    (cond
      (= strategy :grammar)
      (let [translated (-> (translate-request backend-type openai-req)
                           (inject-grammar json-schema))
            raw-resp   (forward-request base-url translated)
            response   (translate-response backend-type raw-resp)]
        (json-response 200 response))

      (= strategy :validate)
      (let [response (complete-with-validation
                      base-url backend-type openai-req json-schema 3)]
        (json-response 200 response))

      :else
      (let [translated (translate-request backend-type openai-req)
            raw-resp   (forward-request base-url translated)
            response   (translate-response backend-type raw-resp)]
        (json-response 200 response)))))

(defn handle-completions [base-url req]
  (let [backend      (get-backend base-url)
        backend-type (:type backend)
        openai-req   (parse-body req)
        template-key (detect-template-from-model (:model openai-req))
        prompt       (or (:prompt openai-req)
                         (apply-template template-key
                                         [{:role "user" :content (:prompt openai-req "")}]))
        translated   (translate-request backend-type
                                         (assoc openai-req
                                                :messages [{:role "user" :content prompt}]))
        raw-resp     (forward-request base-url translated)
        response     (translate-response backend-type raw-resp)]
    (json-response 200 response)))

(defn handle-models [base-url _req]
  (let [backend (get-backend base-url)
        models  (list-models-remote (:type backend) base-url)]
    (json-response 200 {:object "list" :data models})))

(defn handle-health [base-url _req]
  (let [backend (get-backend base-url)]
    (json-response 200 {:status "ok"
                        :backend_type (name (:type backend))
                        :base_url base-url})))

(defn router [base-url]
  (fn [req]
    (let [uri    (:uri req)
          method (:request-method req)]
      (cond
        (and (= method :post) (= uri "/v1/chat/completions"))
        (handle-chat-completions base-url req)

        (and (= method :post) (= uri "/v1/completions"))
        (handle-completions base-url req)

        (and (= method :get) (= uri "/v1/models"))
        (handle-models base-url req)

        (and (= method :get) (= uri "/admin/health"))
        (handle-health base-url req)

        :else
        (json-response 404 {:error {:message "Not found"
                                     :type "invalid_request_error"}})))))

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
                    (println "  PASS" label))
                (do (swap! fail inc)
                    (println "  FAIL" label))))]

      (println "--- Chat templates ---")
      (let [result (apply-template :chatml [{:role "user" :content "hello"}])]
        (check "chatml wraps user message"
               (and (str/includes? result "<|im_start|>user")
                    (str/includes? result "hello")
                    (str/includes? result "<|im_end|>"))))
      (let [result (apply-template :llama3 [{:role "user" :content "hi"}])]
        (check "llama3 template"
               (and (str/includes? result "<|begin_of_text|>")
                    (str/includes? result "<|start_header_id|>user"))))
      (check "detect llama3 model"
             (= :llama3 (detect-template-from-model "meta-llama3-8b")))
      (check "detect mistral model"
             (= :mistral (detect-template-from-model "Mistral-7B")))
      (check "detect fallback to chatml"
             (= :chatml (detect-template-from-model "some-random-model")))

      (println "--- GBNF generation ---")
      (let [gbnf (json-schema->gbnf {"type" "object"
                                      "properties" {"name" {"type" "string"}
                                                    "age"  {"type" "integer"}}})]
        (check "gbnf has root rule" (str/includes? gbnf "root ::="))
        (check "gbnf has ws rule" (str/includes? gbnf "ws ::="))
        (check "gbnf references name field" (str/includes? gbnf "name"))
        (check "gbnf references age field" (str/includes? gbnf "age")))

      (println "--- Schema validation ---")
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

      (println "--- Request translation ---")
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

      (println "--- Response translation ---")
      (let [resp {:body (json/generate-string
                         {:model "llama3"
                          :message {:role "assistant" :content "hello back"}})}]
        (let [result (translate-response :ollama resp)]
          (check "ollama response has choices"
                 (= "hello back" (get-in result [:choices 0 :message :content])))))
      (let [resp {:body (json/generate-string
                         {:results [{:text "kobold says hi"}]})}]
        (let [result (translate-response :koboldcpp resp)]
          (check "koboldcpp response extracts text"
                 (= "kobold says hi" (get-in result [:choices 0 :message :content])))))

      (println "--- Strategy selection ---")
      (check "grammar when supported"
             (= :grammar (choose-strategy :llamacpp {})))
      (check "validate when not supported"
             (= :validate (choose-strategy :ollama {})))
      (check "header override to validate"
             (= :validate (choose-strategy :llamacpp {"x-smar-strategy" "validate"})))
      (check "header override to grammar"
             (= :grammar (choose-strategy :ollama {"x-smar-strategy" "grammar"})))

      (println "--- Routing ---")
      (let [handler (router "http://localhost:99999")]
        (let [resp (handler {:uri "/nonexistent" :request-method :get})]
          (check "404 for unknown route" (= 404 (:status resp)))))

      (println)
      (println (str @pass "/" @total " passed, " @fail " failed"))
      (when (pos? @fail)
        (System/exit 1)))))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (cond
    (some #{"--self-test"} args)
    (run-self-test)

    (= 2 (count args))
    (let [server-port (Integer/parseInt (first args))
          remote-port (Integer/parseInt (second args))
          base-url    (str "http://localhost:" remote-port)]
      (println (str "smar: probing backend at " base-url "..."))
      (let [backend (get-backend base-url)]
        (println (str "smar: detected " (name (:type backend)) " backend"))
        (println (str "smar: listening on port " server-port))
        (http/run-server (router base-url) {:port server-port})
        @(promise)))

    :else
    (do
      (println "Usage: bb smar.bb.clj <server-port> <remote-port>")
      (println "       bb smar.bb.clj --self-test")
      (System/exit 1))))

(apply -main *command-line-args*)
