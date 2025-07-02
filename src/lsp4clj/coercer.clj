(ns lsp4clj.coercer
  (:require
   [clojure.spec.alpha :as s]))

(set! *warn-on-reflection* true)

(s/def :json-rpc.message/jsonrpc #{"2.0"})
(s/def :json-rpc.message/method string?)
(s/def :json-rpc.message/id (s/and (s/or :s string? :i nat-int? :n nil?)
                                   (s/conformer second)))

(s/def ::json-rpc.request
  (s/keys :req-un [:json-rpc.message/jsonrpc
                   :json-rpc.message/id
                   :json-rpc.message/method]
          :opt-un [:json-rpc.message/params]))
(s/def ::json-rpc.notification
  (s/keys :req-un [:json-rpc.message/jsonrpc
                   :json-rpc.message/method]
          :opt-un [:json-rpc.message/params]))
(s/def ::json-rpc.response.result
  (s/keys :req-un [:json-rpc.message/jsonrpc
                   :json-rpc.message/id
                   :json-rpc.message/result]))
(s/def ::json-rpc.response.error
  (s/keys :req-un [:json-rpc.message/jsonrpc
                   :json-rpc.message/id
                   ::error]))

(s/def ::json-rpc.input
  (s/or :request ::json-rpc.request
        :notification ::json-rpc.notification
        :response.result ::json-rpc.response.result
        :response.error ::json-rpc.response.error))

(defn input-message-type [message]
  (if (identical? :parse-error message)
    :parse-error
    (let [conformed-message (s/conform ::json-rpc.input message)]
      (if (identical? ::s/invalid conformed-message)
        :invalid-request
        (first conformed-message)))))
