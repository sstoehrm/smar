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

(import '[org.jline.terminal TerminalBuilder]
        '[org.jline.utils AttributedStringBuilder AttributedStyle])

;; ---------------------------------------------------------------------------
;; TUI — terminal output via JLine3
;; ---------------------------------------------------------------------------

(def ^:dynamic *terminal* nil)

(defn create-terminal []
  (-> (TerminalBuilder/builder)
      (.system true)
      (.jansi true)
      (.build)))

(defn styled [text & styles]
  (let [sb    (AttributedStringBuilder.)
        style (reduce (fn [s kw]
                        (case kw
                          :bold    (.bold s)
                          :green   (.foreground s AttributedStyle/GREEN)
                          :red     (.foreground s AttributedStyle/RED)
                          :yellow  (.foreground s AttributedStyle/YELLOW)
                          :cyan    (.foreground s AttributedStyle/CYAN)
                          :magenta (.foreground s AttributedStyle/MAGENTA)
                          :white   (.foreground s AttributedStyle/WHITE)
                          :dim     (.faint s)
                          s))
                      AttributedStyle/DEFAULT
                      styles)]
    (.style sb style)
    (.append sb (str text))
    (.style sb AttributedStyle/DEFAULT)
    (if *terminal*
      (.toAnsi sb *terminal*)
      (str text))))

(defn tui-print [& parts]
  (let [line (apply str parts)]
    (if *terminal*
      (let [w (.writer *terminal*)]
        (.println w line)
        (.flush w))
      (println line))))

(defn tui-print-no-nl [& parts]
  (let [line (apply str parts)]
    (if *terminal*
      (let [w (.writer *terminal*)]
        (.print w line)
        (.flush w))
      (print line))))

(defn clear-line []
  (tui-print-no-nl "\r\033[2K"))

;; ---------------------------------------------------------------------------
;; TUI — request tracking for server mode
;; ---------------------------------------------------------------------------

(def request-stats (atom {:total 0 :active 0 :errors 0 :last-requests []}))

(defn track-request-start [method uri]
  (swap! request-stats (fn [s]
                         (-> s
                             (update :total inc)
                             (update :active inc)))))

(defn track-request-end [method uri status latency-ms]
  (swap! request-stats (fn [s]
                         (-> s
                             (update :active dec)
                             (cond-> (>= status 400) (update :errors inc))
                             (update :last-requests
                                     (fn [reqs]
                                       (take 20 (cons {:method  method
                                                       :uri     uri
                                                       :status  status
                                                       :latency latency-ms
                                                       :time    (java.time.LocalTime/now)}
                                                      reqs))))))))

(defn format-status [status]
  (cond
    (< status 300) (styled (str status) :green)
    (< status 400) (styled (str status) :yellow)
    :else          (styled (str status) :red)))

(defn format-method [method]
  (styled (str/upper-case (name method)) :cyan :bold))

(defn format-latency [ms]
  (cond
    (< ms 100)  (styled (format "%4dms" ms) :green)
    (< ms 1000) (styled (format "%4dms" ms) :yellow)
    :else        (styled (format "%4dms" ms) :red)))

(defn log-request [method uri status latency-ms]
  (tui-print (styled (str (java.time.LocalTime/now)) :dim)
             " "
             (format-method method)
             " " uri
             " " (format-status status)
             " " (format-latency latency-ms)))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def worker-threads 4)

;; ---------------------------------------------------------------------------
;; TUI — startup display
;; ---------------------------------------------------------------------------

(defn spinner-frames [] ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn with-spinner [message work-fn]
  (let [frames  (spinner-frames)
        running (atom true)
        result  (promise)
        spin-thread (Thread.
                     (fn []
                       (loop [i 0]
                         (when @running
                           (clear-line)
                           (tui-print-no-nl (styled (nth frames (mod i (count frames))) :cyan)
                                            " " message)
                           (Thread/sleep 80)
                           (recur (inc i))))))]
    (.start spin-thread)
    (try
      (let [r (work-fn)]
        (reset! running false)
        (.join spin-thread 200)
        (clear-line)
        r)
      (catch Exception e
        (reset! running false)
        (.join spin-thread 200)
        (clear-line)
        (throw e)))))

(defn print-banner [server-port]
  (tui-print)
  (tui-print (styled "smar" :cyan :bold) (styled " — small agent harness" :dim))
  (tui-print)
  (tui-print (styled "  server   " :dim) (styled (str "http://0.0.0.0:" server-port) :white :bold))
  (tui-print (styled "  threads  " :dim) (styled (str worker-threads) :white))
  (tui-print (styled "  backend  " :dim) (styled "per-request via smar_target" :dim))
  (tui-print)
  (tui-print (styled "  Endpoints:" :dim))
  (tui-print (styled "    POST " :cyan) "/v1/chat/completions")
  (tui-print (styled "    POST " :cyan) "/v1/completions")
  (tui-print (styled "    GET  " :cyan) "/v1/models" (styled "?target=..." :dim))
  (tui-print (styled "    GET  " :cyan) "/admin/health" (styled "?target=..." :dim))
  (tui-print)
  (tui-print (styled "  Ctrl-C to stop" :dim))
  (tui-print))

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

(def backend-cache (atom {}))

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
  (if-let [cached (get @backend-cache base-url)]
    cached
    (let [detected (probe-backend base-url)
          backend  {:type detected :base-url base-url}]
      (swap! backend-cache assoc base-url backend)
      backend)))

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

(defn error-response [status message]
  (json-response status {:error {:message message :type "invalid_request_error"}}))

(defn extract-smar-fields [parsed-body]
  (let [target (:smar_target parsed-body)
        schema (:smar_schema parsed-body)]
    (when target
      {:target target
       :schema schema
       :body   (dissoc parsed-body :smar_target :smar_schema)})))

(defn extract-target-from-query [req]
  (when-let [qs (:query-string req)]
    (some (fn [pair]
            (let [[k v] (str/split pair #"=" 2)]
              (when (= k "target") v)))
          (str/split qs #"&"))))

(defn get-response-schema [openai-req]
  (when-let [rf (:response_format openai-req)]
    (when (= (:type rf) "json_schema")
      (or (:schema rf)
          (get-in rf [:json_schema :schema])))))

(defn handle-chat-completions [req]
  (let [parsed (parse-body req)]
    (if-let [{:keys [target schema body]} (extract-smar-fields parsed)]
      (let [backend      (get-backend target)
            backend-type (:type backend)
            openai-req   body
            json-schema  (or schema (get-response-schema openai-req))
            headers      (into {} (map (fn [[k v]] [(str/lower-case (name k)) v])
                                       (:headers req)))
            strategy     (when json-schema
                           (choose-strategy backend-type headers))]
        (cond
          (= strategy :grammar)
          (let [translated (-> (translate-request backend-type openai-req)
                               (inject-grammar json-schema))
                raw-resp   (forward-request target translated)
                response   (translate-response backend-type raw-resp)]
            (json-response 200 response))

          (= strategy :validate)
          (let [response (complete-with-validation
                          target backend-type openai-req json-schema 3)]
            (json-response 200 response))

          :else
          (let [translated (translate-request backend-type openai-req)
                raw-resp   (forward-request target translated)
                response   (translate-response backend-type raw-resp)]
            (json-response 200 response))))
      (error-response 400 "Missing required field: smar_target"))))

(defn handle-completions [req]
  (let [parsed (parse-body req)]
    (if-let [{:keys [target body]} (extract-smar-fields parsed)]
      (let [backend      (get-backend target)
            backend-type (:type backend)
            openai-req   body
            template-key (detect-template-from-model (:model openai-req))
            prompt       (or (:prompt openai-req)
                             (apply-template template-key
                                             [{:role "user" :content (:prompt openai-req "")}]))
            translated   (translate-request backend-type
                                             (assoc openai-req
                                                    :messages [{:role "user" :content prompt}]))
            raw-resp     (forward-request target translated)
            response     (translate-response backend-type raw-resp)]
        (json-response 200 response))
      (error-response 400 "Missing required field: smar_target"))))

(defn handle-models [req]
  (if-let [target (extract-target-from-query req)]
    (let [backend (get-backend target)
          models  (list-models-remote (:type backend) target)]
      (json-response 200 {:object "list" :data models}))
    (error-response 400 "Missing required query parameter: target")))

(defn handle-health [req]
  (if-let [target (extract-target-from-query req)]
    (let [backend (get-backend target)]
      (json-response 200 {:status "ok"
                          :backend_type (name (:type backend))
                          :base_url target}))
    (error-response 400 "Missing required query parameter: target")))

(defn wrap-request-tracking [handler]
  (fn [req]
    (let [method (:request-method req)
          uri    (:uri req)
          start  (System/currentTimeMillis)]
      (track-request-start method uri)
      (try
        (let [resp    (handler req)
              latency (- (System/currentTimeMillis) start)
              status  (:status resp)]
          (track-request-end method uri status latency)
          (log-request method uri status latency)
          resp)
        (catch Exception e
          (let [latency (- (System/currentTimeMillis) start)]
            (track-request-end method uri 500 latency)
            (log-request method uri 500 latency)
            (json-response 500 {:error {:message (.getMessage e)
                                         :type "internal_error"}})))))))

(defn router []
  (fn [req]
    (let [uri    (:uri req)
          method (:request-method req)]
      (cond
        (and (= method :post) (= uri "/v1/chat/completions"))
        (handle-chat-completions req)

        (and (= method :post) (= uri "/v1/completions"))
        (handle-completions req)

        (and (= method :get) (= uri "/v1/models"))
        (handle-models req)

        (and (= method :get) (= uri "/admin/health"))
        (handle-health req)

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
                    (tui-print "  " (styled "PASS" :green) " " label))
                (do (swap! fail inc)
                    (tui-print "  " (styled "FAIL" :red :bold) " " label))))
            (section [title]
              (tui-print)
              (tui-print (styled (str "── " title " ──") :cyan :bold)))]

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
      (check "detect llama3 model"
             (= :llama3 (detect-template-from-model "meta-llama3-8b")))
      (check "detect mistral model"
             (= :mistral (detect-template-from-model "Mistral-7B")))
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
             (= :grammar (choose-strategy :llamacpp {})))
      (check "validate when not supported"
             (= :validate (choose-strategy :ollama {})))
      (check "header override to validate"
             (= :validate (choose-strategy :llamacpp {"x-smar-strategy" "validate"})))
      (check "header override to grammar"
             (= :grammar (choose-strategy :ollama {"x-smar-strategy" "grammar"})))

      (section "Smar fields extraction")
      (let [parsed {:smar_target "http://localhost:1234"
                    :smar_schema {"type" "object" "properties" {"x" {"type" "integer"}}}
                    :model "test" :messages []}
            result (extract-smar-fields parsed)]
        (check "extracts target" (= "http://localhost:1234" (:target result)))
        (check "extracts schema" (= {"type" "object" "properties" {"x" {"type" "integer"}}}
                                    (:schema result)))
        (check "strips smar fields from body"
               (and (not (contains? (:body result) :smar_target))
                    (not (contains? (:body result) :smar_schema))
                    (= "test" (:model (:body result))))))
      (let [parsed {:smar_target "http://localhost:1234" :model "test"}
            result (extract-smar-fields parsed)]
        (check "schema is nil when absent" (nil? (:schema result))))

      (section "Routing")
      (let [handler (router)]
        (check "404 for unknown route"
               (= 404 (:status (handler {:uri "/nonexistent" :request-method :get}))))
        (check "400 when smar_target missing (chat)"
               (= 400 (:status (handler {:uri "/v1/chat/completions"
                                          :request-method :post
                                          :body (java.io.ByteArrayInputStream.
                                                 (.getBytes "{\"model\":\"test\",\"messages\":[]}"))}))))
        (check "400 when target query missing (models)"
               (= 400 (:status (handler {:uri "/v1/models"
                                          :request-method :get})))))

      (tui-print)
      (let [p @pass f @fail t @total]
        (if (zero? f)
          (tui-print (styled (str p "/" t " passed") :green :bold))
          (tui-print (styled (str p "/" t " passed, " f " failed") :red :bold))))
      (tui-print)
      (when (pos? @fail)
        (System/exit 1)))))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (binding [*terminal* (create-terminal)]
    (try
      (cond
        (some #{"--self-test"} args)
        (run-self-test)

        (= 1 (count args))
        (let [server-port (Integer/parseInt (first args))]
          (print-banner server-port)
          (http/run-server (wrap-request-tracking (router))
                           {:port server-port :thread worker-threads})
          @(promise))

        :else
        (do
          (tui-print (styled "Usage:" :bold) " bb smar.bb.clj <server-port>")
          (tui-print (styled "       " :bold) " bb smar.bb.clj --self-test")
          (System/exit 1)))
      (finally
        (.close *terminal*)))))

(apply -main *command-line-args*)
