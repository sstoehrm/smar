#!/usr/bin/env bb
;; End-to-end integration test for smar against a real Gemma 4 model.
;;
;; Requires a running backend (default: KoboldCPP on http://localhost:5001).
;; Override via environment:
;;   SMAR_TARGET   - backend URL (default http://localhost:5001)
;;   SMAR_BACKEND  - backend type (default koboldcpp)
;;   SMAR_MODEL    - model identifier (default koboldcpp/gemma-4-E4B-it-UD-Q6_K_XL)
;;
;; Exits 0 on success, non-zero = number of failed checks, 2 if backend unreachable.

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def target  (or (System/getenv "SMAR_TARGET")  "http://localhost:5001"))
(def backend (or (System/getenv "SMAR_BACKEND") "koboldcpp"))
(def model   (or (System/getenv "SMAR_MODEL")   "koboldcpp/gemma-4-E4B-it-UD-Q6_K_XL"))

(def script-file (or (System/getProperty "babashka.file") *file*))
(def repo-root (.. (io/file script-file) getAbsoluteFile getParentFile getParentFile getAbsolutePath))
(def smar-path (str repo-root "/smar.bb.clj"))

(def pass (atom 0))
(def fail (atom 0))
(def failures (atom []))

(defn check [label cond]
  (if cond
    (do (swap! pass inc) (println "  PASS:" label))
    (do (swap! fail inc)
        (swap! failures conj label)
        (println "  FAIL:" label))))

(defn section [title]
  (println)
  (println "--" title "--"))

(defn sh-smar [args stdin-str]
  (let [opts (cond-> {:out :string :err :string :continue true}
               stdin-str (assoc :in stdin-str))]
    (apply p/shell opts "bb" smar-path args)))

(defn parse-json-safe [s]
  (try (json/parse-string s true) (catch Exception _ nil)))

(println "smar integration test")
(println "  target: " target)
(println "  backend:" backend)
(println "  model:  " model)

(section "Preflight")
(let [{:keys [out exit err]}
      (sh-smar ["preflight" (json/generate-string {:smar_target target})] nil)
      parsed (parse-json-safe out)]
  (when-not (zero? exit)
    (println)
    (println "ERROR: backend not reachable at" target)
    (println (str/trim err))
    (println)
    (println "Set SMAR_TARGET, SMAR_BACKEND, SMAR_MODEL env vars to configure.")
    (System/exit 2))
  (check "preflight returns valid JSON" (some? parsed))
  (check "preflight detects a known backend"
         (#{"ollama" "koboldcpp" "llamacpp"} (:backend_type parsed)))
  (when (not= backend (:backend_type parsed))
    (println (str "  NOTE: auto-detected backend is '" (:backend_type parsed)
                  "' but SMAR_BACKEND=" backend " will be used for completions.")))
  (check "preflight reports a model" (seq (:models parsed))))

(section "Plain completion")
(let [{:keys [out exit]}
      (sh-smar ["complete"]
               (json/generate-string
                {:smar_target target
                 :smar_backend backend
                 :smar_model_family "gemma4"
                 :model model
                 :messages [{:role "user" :content "Say hello in one word."}]
                 :max_tokens 32}))
      parsed  (parse-json-safe out)
      content (get-in parsed [:choices 0 :message :content])]
  (check "complete exits 0" (zero? exit))
  (check "response is valid OpenAI envelope"
         (and parsed
              (= "chat.completion" (:object parsed))
              (seq (:choices parsed))))
  (check "content is present" (and content (not (str/blank? content))))
  (check "content has no channel tag"
         (not (str/includes? (or content "") "<channel|>"))))

(section "Structured output with schema + enum")
(let [enum-vals   ["neutral" "happy" "sad" "angry" "surprised" "amused"]
      schema      {:type "object"
                   :required ["content" "expression"]
                   :properties {:content {:type "string"
                                          :description "What Greta says"}
                                :expression {:type "string"
                                             :enum enum-vals
                                             :description "Greta's expression"}}}
      {:keys [out exit]}
      (sh-smar ["complete"]
               (json/generate-string
                {:smar_target target
                 :smar_backend backend
                 :smar_model_family "gemma4"
                 :model model
                 :messages [{:role "system"
                             :content "You are Greta, a tavern bartender. Respond in one sentence."}
                            {:role "user" :content "Hello!"}]
                 :smar_schema schema
                 :max_tokens 256}))
      parsed       (parse-json-safe out)
      content-str  (get-in parsed [:choices 0 :message :content])
      content-json (parse-json-safe (or content-str ""))]
  (check "complete exits 0" (zero? exit))
  (check "content parses as JSON object" (map? content-json))
  (when (map? content-json)
    (check "has :content key (no colon prefix)" (contains? content-json :content))
    (check "has :expression key (no colon prefix)" (contains? content-json :expression))
    (check ":content value is a non-empty string"
           (and (string? (:content content-json))
                (not (str/blank? (:content content-json)))))
    (check ":expression value is in enum"
           (boolean ((set enum-vals) (:expression content-json))))
    (check "no stray keys leaked from schema properties"
           (empty? (disj (set (map name (keys content-json))) "content" "expression"))))
  (check "no channel tag in content"
         (not (str/includes? (or content-str "") "<channel|>")))
  (check "no CoT markers in content"
         (not (re-find #"(?i)constraint checklist|confidence score|(?m)^plan:|\*\s+\*\*analyze"
                       (or content-str "")))))

(section "Summary")
(println "  " @pass "passed," @fail "failed")
(when (pos? @fail)
  (println)
  (println "  Failed checks:")
  (doseq [f @failures] (println "   -" f)))
(System/exit @fail)
