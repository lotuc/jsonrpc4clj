(ns lsp4clj.test-helper
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [is]]
   [promesa.core :as p]))

(defn take-or-timeout
  ([ch]
   (take-or-timeout ch 100))
  ([ch timeout-ms]
   (take-or-timeout ch timeout-ms :timeout))
  ([ch timeout-ms timeout-val]
   (let [timeout (async/timeout timeout-ms)
         r (p/deferred)]
     (async/go
       (let [[result ch] (async/alts! [ch timeout])]
         (if (= ch timeout)
           (p/resolve! r timeout-val)
           (p/resolve! r result))))
     r)))

(defn assert-no-take [ch]
  (-> (take-or-timeout ch 500 :nothing)
      (p/then (fn [v] (is (= :nothing v))))))

(defn assert-take [ch]
  (-> (take-or-timeout ch)
      (p/then (fn [result]
                (is (not= :timeout result))
                result))))
