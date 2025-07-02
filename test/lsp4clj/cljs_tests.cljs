(ns lsp4clj.cljs-tests
  (:require
   [cljs.test :as t]
   [lsp4clj.server-test]))

(defn run-tests []
  (t/run-tests 'lsp4clj.server-test))

(defn init [])

(comment
  (run-tests)
  #_())
