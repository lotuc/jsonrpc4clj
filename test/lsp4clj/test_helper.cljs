(ns lsp4clj.test-helper
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [is]]))

(defn take-or-timeout
  ([cb ch]
   (take-or-timeout cb ch 100))
  ([cb ch timeout-ms]
   (take-or-timeout cb ch timeout-ms :timeout))
  ([cb ch timeout-ms timeout-val]
   (async/go
     (let [timeout (async/timeout timeout-ms)
           [result ch] (async/alts! [ch timeout])]
       (if (= ch timeout)
         (cb timeout-val)
         (cb result))))))

(defn assert-no-take [ch]
  (take-or-timeout #(is (= :nothing %)) ch 500 :nothing))

(defn assert-take [cb ch]
  (take-or-timeout
    (fn [result] (is (not= :timeout result))
      (cb result))
    ch))
