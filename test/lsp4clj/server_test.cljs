(ns lsp4clj.server-test
  (:require
   [clojure.core.async :as async]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing] :as t]
   [lsp4clj.lsp.requests :as lsp.requests]
   [lsp4clj.lsp.responses :as lsp.responses]
   [lsp4clj.server :as server]
   [lsp4clj.test-helper :as h]
   [promesa.core :as p]))

(deftest should-process-messages-received-before-start
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})]
      (async/put! input-ch (lsp.requests/request 1 "foo" {}))
      (server/start server nil)
      (-> (h/assert-take output-ch)
          (p/handle (fn [_ _] (server/shutdown server)))
          (p/finally (fn [_ _] (done)))))))

(deftest should-process-sent-messages-before-closing
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})]
      (server/start server nil)
      (async/put! input-ch (lsp.requests/request 2 "bar" {}))
      (-> (server/shutdown server)
          (p/handle (fn [_ _] (h/assert-take output-ch)))
          (p/finally (fn [_ _] (done)))))))

(deftest should-close-when-asked-to
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})]
      (server/start server nil)
      (-> (server/shutdown server)
          (p/handle (fn [v _] (is (= :done v))))
          (p/then (fn [_] (h/take-or-timeout output-ch)))
          (p/then (fn [v]
                    ;; output-ch also closes
                    (is (nil? v))))
          (p/finally (fn [_ _] (done)))))))

(deftest should-close-when-input-ch-closes
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          join (server/start server nil)]
      (async/close! input-ch)
      (-> (p/timeout join 100 :timed-out)
          (p/handle (fn [v _] (is (= :done v))))
          (p/then (fn [_] (h/take-or-timeout output-ch)))
          (p/then (fn [v]
                    ;; output-ch also closes
                    (is (nil? v))))
          (p/finally (fn [_ _] (done)))))))

(deftest should-receive-responses
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})
          client-rcvd-msg (h/assert-take output-ch)]
      (-> client-rcvd-msg
          (p/then (fn [{:keys [id]}]
                    (async/put! input-ch (lsp.responses/response id {:processed true})))))
      (-> req
          (p/then (fn [req] (server/deref-or-cancel req 1000 :test-timeout)))
          (p/handle (fn [v _] (is (= {:processed true} v))))
          (p/finally (fn [_ _] (server/shutdown server) (done)))))))

(deftest should-passes-jsonrpc-message-metadata-around
  (t/async
    done

    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server
                   {:output-ch output-ch
                    :input-ch input-ch
                    :handle-request (fn [_ _ _] "hello")})
          _ (server/start server nil)]

      (-> (binding [server/*send-advice* (fn [m] (assoc m :key0 "val0"))]
            (server/send-request server "req" {:body "foo"}))
          (p/handle
            (fn [req _]
              (-> (h/assert-take output-ch)
                ;; we can attach metadata to jsonrpc request
                  (p/handle (fn [client-rcvd-msg _]
                              (is (= "val0" (:key0 (meta client-rcvd-msg))))
                              (:id client-rcvd-msg)))
                ;; can also attach metadata on jsonrpc response
                  (p/handle (fn [id _]
                              (async/put! input-ch
                                          (vary-meta
                                            (lsp.responses/response id {:processed true})
                                            #(assoc % :key1 "val1")))))
                  (p/handle (fn [_ _]
                              (server/deref-or-cancel req 1000 :test-timeout)))
                  (p/handle (fn [v _]
                              (is (= {:processed true} v))
                              (is (p/done? (:response req)))
                              (is (= "val1" (:key1 (meta @(:response req))))))))))

          ;; the jsonrpc response's metadata contains the jsonrpc request
          (p/handle (fn [_ _]
                      (let [request (vary-meta
                                      (lsp.requests/request 42 "foo" "bar")
                                      #(assoc % :key3 "val3"))]
                        (async/put! input-ch request)
                        (-> (h/assert-take output-ch)
                            (p/handle (fn [v _]
                                        (is (= {:jsonrpc "2.0", :id 42, :result "hello"} v))
                                        (is (= request (:request (meta v))))
                                        ;; preserves the metadata
                                        (is (= "val3" (:key3 (meta (:request (meta v))))))))))))
          (p/finally (fn [_ _] (server/shutdown server) (done)))))))

(deftest should-respond-to-requests
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})]
      (server/start server nil)
      (async/put! input-ch (lsp.requests/request 1 "foo" {}))
      (-> (h/assert-take output-ch)
          (p/then (fn [v] (is (= 1 (:id v)))))
          (p/finally (fn [_ _] (server/shutdown server) (done)))))))

(deftest should-be-able-to-place-request-while-receiving-request
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server
                   {:output-ch output-ch
                    :input-ch input-ch
                    :handle-request
                    (fn [server _context {:keys [method]}]
                      (is (= "initialize" method))
                      (-> (server/send-request server "window/showMessageRequest" {})
                          (p/then (fn [req] (server/deref-or-cancel req 100 :timeout)))
                          (p/then (fn [resp]
                                    (if (= :timeout resp)
                                      {:error :timeout}
                                      {:client-response (:response resp)})))))})]
      (server/start server nil)
      (async/put! input-ch (lsp.requests/request 1 "initialize" {}))

      (let [client-rcvd-msg-1 (h/assert-take output-ch)]
        (-> client-rcvd-msg-1
            (p/handle (fn [v _]
                        (is (= "window/showMessageRequest" (:method v)))
                        (:id v)))
            (p/handle (fn [id _]
                        (->> (lsp.responses/response id {:response "ok"})
                             (async/put! input-ch))))
            (p/handle (fn [_ _]
                        (h/take-or-timeout output-ch 200)))
            (p/handle (fn [v _]
                        (is (= {:jsonrpc "2.0"
                                :id 1
                                :result {:client-response "ok"}}
                               v))))
            (p/finally (fn [_ _] (server/shutdown server) (done))))))))

(deftest should-use-deferred-responses
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server
                   {:output-ch output-ch
                    :input-ch input-ch
                    :handle-request
                    (fn [_server _context _req]
                      (p/future "initialized"))})]
      (server/start server nil)
      (async/put! input-ch (lsp.requests/request 1 "initialize" {}))
      (-> (h/take-or-timeout output-ch 200)
          (p/then (fn [v] (is (= {:jsonrpc "2.0"
                                  :id 1
                                  :result "initialized"}
                                 v))))
          (p/finally (fn [_ _] (server/shutdown server) (done)))))))

(deftest should-reply-with-method-not-found-for-unexpected-messages
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})]
      (server/start server nil)
      (async/put! input-ch (lsp.requests/request 1 "foo" {}))

      (-> (h/assert-take output-ch)
          (p/then (fn [v]
                    (is (= {:jsonrpc "2.0"
                            :id 1
                            :error {:code -32601, :message "Method not found", :data {:method "foo"}}}
                           v))))
          (p/finally (fn [_ _] (server/shutdown server) (done)))))))

(deftest should-return-nil-after-sending-notifications
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})]
      (server/start server nil)
      (-> (server/send-notification server "req" {:body "foo"})
          (p/handle (fn [v e] (is (nil? v)) (is (nil? e))))
          (p/finally (fn [_ _] (server/shutdown server) (done)))))))

(deftest should-receive-response-to-request-sent-while-processing-notification
  ;; https://github.com/clojure-lsp/clojure-lsp/issues/1500
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          client-resp (p/deferred)
          server (server/chan-server
                   {:output-ch output-ch
                    :input-ch input-ch
                    :handle-notification
                    (fn [server _context _req]
                      (-> (server/send-request server "server-sent-request" {:body "foo"})
                          (p/handle (fn [req _]
                                      (-> (p/timeout (p/deferred) 100)
                                          (p/handle (fn [_ _] req)))))
                          (p/then (fn [req]
                                    (server/deref-or-cancel req 1000 :broke-deadlock)))
                          (p/handle (fn [resp _]
                                      (p/resolve! client-resp resp)))))})]

      (server/start server nil)
        ;; The first pass of this fix used a `(chan 1)` instead of
        ;; `(sliding-buffer 100)`. It worked when the client sent only two
        ;; messages before the server could finish the first, but not when it sent
        ;; 3 or more. The first notif made the server start sleeping, the second
        ;; filled up the channel's buffer, and the third blocked on putting onto
        ;; the channel, meaning the client response on the next few lines never
        ;; got through. So these lines check that "several" client messages can be
        ;; queued. See notes in lsp4clj.server for caveats about what "several"
        ;; means.
      (async/put! input-ch (lsp.requests/notification "client-sent-notif" {:input 1}))
      (async/put! input-ch (lsp.requests/notification "client-sent-notif" {:input 2}))
      (async/put! input-ch (lsp.requests/notification "client-sent-notif" {:input 3}))
      (let [client-rcvd-request (h/assert-take output-ch)]
        (-> client-rcvd-request
            (p/handle (fn [v _]
                        (is (= "server-sent-request" (:method v)))
                        (:id v)))
              ;; (p/then (fn [{:keys [id]}]
              ;;           (async/put! input-ch (lsp.responses/response (id) {:processed true}))))
              ;; (p/then (fn [_] (p/timeout client-resp 10000 :timed-out)))
              ;; (p/handle (fn [v _] (is (= {:processed true} v))))
            (p/finally (fn [_ _] (server/shutdown server) (done))))))))

;;; TODO: backpressure
#_(deftest should-fail-pending-requests-if-too-many-inbound-messages-are-buffered
  ;; * If the server sends a request, and blocks waiting for the response,
  ;; * and if the client sends too many other messages before responding,
  ;; * then the server's buffer of unprocessed inbound messages will fill up.
  ;; To avoid dropping messages or buffering endlessly, the server eventually
  ;; aborts its request.
    (t/async
      done
      (let [input-ch (async/chan 3)
            output-ch (async/chan 3)
            input-buffer-size 5
            server (server/chan-server
                     {:output-ch output-ch
                      :input-ch input-ch
                      :input-buffer-size input-buffer-size
                      :handle-request
                      (fn [server _context {:keys [params]}]
                        (let [{:keys [server-action] :as client-req} params]
                          (if (= :block server-action)
                            (-> (server/send-request server "server-sent-request" {:body "foo"})
                                (p/handle (fn [req _] req))
                                (p/handle (fn [_ e] (if e
                                                      {:error {:result :deref-aborted}}
                                                      {:processed client-req}))))
                            {:processed client-req})))})
            client-req-id* (atom 0)
            client-req (fn [body]
                         (async/put! input-ch (lsp.requests/request (swap! client-req-id* inc)
                                                                    "client-sent-request"
                                                                    body)))]
        (server/start server nil)
          ;; The client sends a request which causes the server to send its own
          ;; request. The server starts blocking, waiting for the client to respond.
        (client-req {:server-action :block})
          ;; The client receives the server's request but doesn't respond yet.
        (is (= "server-sent-request" (:method (h/assert-take output-ch))))
          ;; Before responding to the server's request, the client sends many other
          ;; messages. The server will buffer these messages.
        (dotimes [n input-buffer-size]
          (client-req {:server-action :buffer, :input n}))
          ;; The server is still blocking.
        (-> (h/assert-no-take output-ch)
            (p/handle (fn [v _] (is v)))
          ;; The client sends one more mesage, which is too many for the server to buffer.
            (p/handle (fn [_ _] (client-req {:server-action :overflow})))
            ;; To avoid blocking the client's inbound messages, the server's outbound
            ;; request is aborted, causing it to stop waiting for a client response.
            (p/handle (fn [_ _] (h/assert-take output-ch)))
            (p/handle (fn [v _] (is (= {:jsonrpc "2.0", :id 1, :error {:result :deref-aborted}}
                                       v))))
            ;; Now the server can process every other message from the client.
            ;; (p/handle (fn [_ _]
            ;;             (p/all
            ;;               (mapv (fn [n] (h/assert-take output-ch))
            ;;                     (range server/input-buffer-size)))
            ;;             (dotimes [n server/input-buffer-size]
            ;;               (is (= {:processed {:server-action :buffer, :input n}}
            ;;                      (:result (h/assert-take output-ch)))))))
            ;; (p/handle (fn [vs _]
            ;;             (is (= (mapv
            ;;                      (fn [n] {:processed {:server-action :buffer, :input n}})
            ;;                      (range server/input-buffer-size))
            ;;                    (mapv :result vs)))))
            ;; (p/handle (fn [_ _]
            ;;             (h/assert-take output-ch)))
            ;; (p/handle (fn [v _]
            ;;             (is (= {:processed {:server-action :overflow}}
            ;;                    (:result v)))))
            (p/finally (fn [_ _]
                         (server/shutdown server) (done)))))))

(deftest should-cancel-request-when-cancellation-notification-receieved
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server
                   {:output-ch output-ch
                    :input-ch input-ch
                    :handle-request
                    (fn [& _args]
                      (p/delay 1000 "foo response"))})]
      (server/start server nil)
      (async/put! input-ch (lsp.requests/request 1 "foo" {}))
      (async/put! input-ch (lsp.requests/notification "$/cancelRequest" {:id 1}))

      (-> (h/assert-take output-ch)
          (p/handle (fn [v _]
                      (is (= {:jsonrpc "2.0",
                              :id 1,
                              :error {:code -32800,
                                      :message "The request {:id 1, :method \"foo\"} has been cancelled.",
                                      :data {:id 1, :method "foo"}}}
                             v))))
          (p/finally (fn [_ _] (server/shutdown server) (done)))))))

(deftest should-inform-handler-when-request-is-cancelled
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          task-completed (p/deferred)
          server (server/chan-server
                   {:output-ch output-ch
                    :input-ch input-ch
                    :handle-request
                    (fn [_ context _]
                      (p/handle
                        (p/delay 300)
                        (fn [_ _]
                          (p/resolve! task-completed
                                      (if @(:lsp4clj.server/req-cancelled? context)
                                        :cancelled
                                        :ran-anyway)))))})]
      (server/start server nil)
      (async/put! input-ch (lsp.requests/request 1 "initialize" {}))
      (async/put! input-ch (lsp.requests/notification "$/cancelRequest" {:id 1}))
      (-> (p/timeout task-completed 1000 :timed-out)
          (p/handle (fn [v _] (is (= :cancelled v))))
          (p/finally (fn [_ _] (server/shutdown server) (done)))))))

(deftest should-cancel-if-no-response-received
  (t/async
    done

    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      ;; client receives message, but doesn't reply
      (h/assert-take output-ch)
      (-> req
          (p/handle (fn [req _] (server/deref-or-cancel req 100 :expected-timeout)))
          (p/handle (fn [v _] (is (= :expected-timeout v))))
          (p/handle (fn [_ _] (h/assert-take output-ch)))
          (p/handle (fn [v _] (is (= {:jsonrpc "2.0", :method "$/cancelRequest", :params {:id 1}} v))))
          (p/finally (fn [_ _] (server/shutdown server) (done)))))))

(deftest should-not-cancel-after-client-replies
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})
          client-rcvd-msg (h/assert-take output-ch)]
      (-> (p/all [req client-rcvd-msg])
          (p/handle
            (fn [[req {:keys [id]}] _]
              (async/put! input-ch (lsp.responses/response id {:processed true}))
              (-> (server/deref-or-cancel req 1000 :test-timeout)
                  (p/handle (fn [v _] (is (= {:processed true} v))))
                  (p/handle (fn [_ _] (h/assert-no-take output-ch)))
                  (p/handle (fn [_ _]
                              (p/cancel! req)
                              (is (not (p/cancelled? req))))))))
          (p/finally (fn [_ _] (server/shutdown server) (done)))))))

(deftest should-send-only-one-cancellation
  (t/async
    done
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)]
      (-> (server/send-request server "req" {:body "foo"})
          (p/handle
            (fn [req _]
              (-> (h/assert-take output-ch)
                  (p/handle (fn [_ _]
                              (p/cancel! req)
                              (is (p/cancelled? req))))
                  (p/handle (fn [_ _]
                              (h/assert-take output-ch)))
                  (p/handle (fn [v _]
                              (is (= "$/cancelRequest" (:method v)))))
                  (p/handle (fn [_ _]
                              (p/cancel! req)
                              (is (p/cancelled? req))))
                  (p/handle (fn [_ _]
                              (h/assert-no-take output-ch))))))
          (p/finally (fn [_ _] (server/shutdown server) (done)))))))

(deftest request-should-behave-like-a-promesa-promise
  (testing "before being handled"
    (t/async
      done
      (let [input-ch (async/chan 3)
            output-ch (async/chan 3)
            server (server/chan-server {:output-ch output-ch
                                        :input-ch input-ch})
            _ (server/start server nil)]
        (-> (server/send-request server "req" {:body "foo"})
            (p/handle (fn [req _]
                        (prn :>>> req)
                        (is (not (p/done? req)))))
            (p/finally (fn [_ _] (server/shutdown server) (done)))))))

  (testing "after response"
    (t/async
      done
      (let [input-ch (async/chan 3)
            output-ch (async/chan 3)
            server (server/chan-server {:output-ch output-ch
                                        :input-ch input-ch})
            _ (server/start server nil)]

        (-> (server/send-request server "req" {:body "foo"})
            (p/handle
              (fn [req _]
                (-> (h/assert-take output-ch)
                    (p/handle (fn [client-rcvd-msg _]
                                (async/put! input-ch (lsp.responses/response (:id client-rcvd-msg) {:result "good"}))
                                (-> req
                                    (p/then (fn [resp] {:result :client-success
                                                        :value 1
                                                        :resp resp}))
                                    (p/catch (fn [error-resp-ex] {:result :client-error
                                                                  :value 10
                                                                  :resp (ex-data error-resp-ex)}))
                                    (p/timeout 1000 {:result :timeout
                                                     :value 100})
                                    (p/then #(update % :value inc)))))
                    (p/handle (fn [v _]
                                (is (= {:result :client-success
                                        :value 2
                                        :resp {:result "good"}}
                                       v))))
                    (p/handle (fn [_ _]
                                (is (p/done? req))
                                (is (p/resolved? req))
                                (is (not (p/rejected? req)))
                                (is (not (p/cancelled? req))))))))
            (p/finally (fn [_ _] (server/shutdown server) (done)))))))

  (testing "after timeout"
    (t/async
      done
      (let [input-ch (async/chan 3)
            output-ch (async/chan 3)
            server (server/chan-server {:output-ch output-ch
                                        :input-ch input-ch})
            _ (server/start server nil)]
        (-> (server/send-request server "req" {:body "foo"})
            (p/handle
              (fn [req _]
                (-> req
                    (p/then (fn [resp] {:result :client-success
                                        :value 1
                                        :resp resp}))
                    (p/catch (fn [error-resp-ex] {:result :client-error
                                                  :value 10
                                                  :resp (ex-data error-resp-ex)}))
                    (p/timeout 300 {:result :timeout
                                    :value 100})
                    (p/then #(update % :value inc))
                    (p/handle (fn [v _]
                                (is (= {:result :timeout
                                        :value 101})
                                    v)))
                    (p/handle (fn [_ _]
                                (is (not (p/done? req)))
                                (is (not (p/resolved? req)))
                                (is (not (p/rejected? req)))
                                (is (not (p/cancelled? req))))))))
            (p/finally (fn [_ _] (server/shutdown server) (done)))))))

  (testing "after client error"
    (t/async
      done
      (let [input-ch (async/chan 3)
            output-ch (async/chan 3)
            server (server/chan-server {:output-ch output-ch
                                        :input-ch input-ch})
            _ (server/start server nil)]

        (-> (p/promise (server/send-request server "req" {:body "foo"}))
            (p/handle
              (fn [req _]

                (-> (h/assert-take output-ch)
                    (p/handle (fn [client-rcvd-msg _]
                                (async/put! input-ch
                                            (-> (lsp.responses/response (:id client-rcvd-msg))
                                                (lsp.responses/error {:code 1234
                                                                      :message "Something bad"
                                                                      :data {:body "foo"}})))))
                    (p/handle (fn [_ _] req))
                    (p/then (fn [resp] {:result :client-success
                                        :value 1
                                        :resp resp}))
                    (p/catch (fn [error-resp-ex] {:result :client-error
                                                  :value 10
                                                  :resp (ex-data error-resp-ex)}))
                    (p/timeout 1000 {:result :timeout
                                     :value 100})
                    (p/then #(update % :value inc))
                    (p/handle (fn [v _]
                                (is (= {:result :client-error
                                        :value 11
                                        :resp {:jsonrpc "2.0",
                                               :id 1,
                                               :error {:code 1234,
                                                       :message "Something bad",
                                                       :data {:body "foo"}}}}
                                       v))))
                    (p/finally (fn [_ _]
                                 (is (p/done? req))
                                 (is (not (p/resolved? req)))
                                 (is (p/rejected? req))
                                 (is (not (p/cancelled? req))))))))
            (p/finally (fn [_ _] (server/shutdown server) (done)))))))

  (testing "after cancellation"
    (t/async
      done

      (let [input-ch (async/chan 3)
            output-ch (async/chan 3)
            server (server/chan-server {:output-ch output-ch
                                        :input-ch input-ch})
            _ (server/start server nil)]
        (-> (p/promise (server/send-request server "req" {:body "foo"}))
            (p/handle
              (fn [req _]
                (-> (h/assert-take output-ch)
                    (p/handle (fn [_ _] (p/cancel! req)))
                    (p/handle (fn [_ _]
                                (is (p/done? req))
                                (is (p/cancelled? req))))
                    (p/handle (fn [_ _] (h/assert-take output-ch)))
                    (p/handle (fn [v _]
                                (is (= {:jsonrpc "2.0", :method "$/cancelRequest", :params {:id 1}}
                                       v)))))))

            (p/finally (fn [_ _] (server/shutdown server) (done))))))))
