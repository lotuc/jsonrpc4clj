(ns lsp4clj.server-test
  (:require
   [clojure.core.async :as async]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [lsp4clj.lsp.requests :as lsp.requests]
   [lsp4clj.lsp.responses :as lsp.responses]
   [lsp4clj.server :as server]
   [lsp4clj.test-helper :as h]
   [promesa.core :as p]))

(deftest should-process-messages-received-before-start
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (async/put! input-ch (lsp.requests/request 1 "foo" {}))
    (server/start server nil)
    (h/assert-take output-ch)
    (server/shutdown server)))

(deftest should-process-sent-messages-before-closing
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/request 2 "bar" {}))
    (server/shutdown server)
    (h/assert-take output-ch)))

(deftest should-close-when-asked-to
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (server/start server nil)
    (is (= :done (server/shutdown server)))
    ;; output-ch also closes
    (is (nil? (h/take-or-timeout output-ch)))))

(deftest should-close-when-input-ch-closes
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        join (server/start server nil)]
    (async/close! input-ch)
    (is (= :done (deref join 100 :timed-out)))
    ;; output-ch also closes
    (is (nil? (h/take-or-timeout output-ch)))))

(deftest should-receive-responses
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        _ (server/start server nil)
        req (server/send-request server "req" {:body "foo"})
        client-rcvd-msg (h/assert-take output-ch)]
    (async/put! input-ch (lsp.responses/response (:id client-rcvd-msg) {:processed true}))
    (is (= {:processed true} (server/deref-or-cancel req 1000 :test-timeout)))
    (server/shutdown server)))

(deftest should-passes-jsonrpc-message-metadata-around
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server
                 {:output-ch output-ch
                  :input-ch input-ch
                  :handle-request (fn [_ _ _] "hello")})
        _ (server/start server nil)
        req (->> (vary-meta (lsp4clj.lsp.requests/request 42 "req" {:body "foo"})
                            (fn [m] (assoc m :key0 "val0")))
                 (server/send-request server))
        client-rcvd-msg (h/assert-take output-ch)]

    ;; we can attach metadata to jsonrpc request
    (is (= "val0" (:key0 (meta client-rcvd-msg))))

    ;; can also attach metadata on jsonrpc response
    (async/put! input-ch
                (vary-meta
                  (lsp.responses/response (:id client-rcvd-msg) {:processed true})
                  #(assoc % :key1 "val1")))
    (is (= {:processed true} (server/deref-or-cancel req 1000 :test-timeout)))
    (is (p/done? (:response req)))
    (is (= "val1" (:key1 (meta @(:response req)))))

    ;; the jsonrpc response attaches jsonrpc request's metadata
    (let [request (vary-meta
                    (lsp.requests/request 42 "foo" "bar")
                    #(assoc % :key3 "val3"))]
      (async/put! input-ch request)
      (let [v (h/assert-take output-ch)]
        (is (= {:jsonrpc "2.0", :id 42, :result "hello"} v))
        (is (= request (:request (meta v))))
        ;; preserves the metadata
        (is (= "val3" (:key3 (meta (:request (meta v))))))))

    (server/shutdown server)))

(deftest should-respond-to-requests
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/request 1 "foo" {}))
    (is (= 1 (:id (h/assert-take output-ch))))
    (server/shutdown server)))

(deftest should-be-able-to-place-request-while-receiving-request
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (server/start server nil)
    (with-redefs [server/receive-request (fn [method _context _params]
                                           (p/future
                                             (is (= "initialize" method))
                                             (let [req (server/send-request server "window/showMessageRequest" {})
                                                   resp (server/deref-or-cancel req 100 :timeout)]
                                               (if (= :timeout resp)
                                                 {:error :timeout}
                                                 {:client-response (:response resp)}))))]
      (async/put! input-ch (lsp.requests/request 1 "initialize" {}))
      (let [client-rcvd-msg-1 (h/assert-take output-ch)]
        (is (= "window/showMessageRequest" (:method client-rcvd-msg-1)))
        (async/put! input-ch (lsp.responses/response (:id client-rcvd-msg-1) {:response "ok"}))
        (is (= {:jsonrpc "2.0"
                :id 1
                :result {:client-response "ok"}}
               (h/take-or-timeout output-ch 200)))))
    (server/shutdown server)))

(deftest should-use-deferred-responses
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (server/start server nil)
    (with-redefs [server/receive-request (fn [_method _context _params]
                                           (p/future "initialized"))]
      (async/put! input-ch (lsp.requests/request 1 "initialize" {}))
      (is (= {:jsonrpc "2.0"
              :id 1
              :result "initialized"}
             (h/take-or-timeout output-ch 200))))
    (server/shutdown server)))

(deftest should-reply-with-method-not-found-for-unexpected-messages
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/request 1 "foo" {}))
    (is (= {:jsonrpc "2.0"
            :id 1
            :error {:code -32601, :message "Method not found", :data {:method "foo"}}}
           (h/assert-take output-ch)))
    (server/shutdown server)))

(deftest should-return-nil-after-sending-notifications
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (server/start server nil)
    (is (nil? (server/send-notification server "req" {:body "foo"})))
    (server/shutdown server)))

(deftest should-receive-response-to-request-sent-while-processing-notification
  ;; https://github.com/clojure-lsp/clojure-lsp/issues/1500
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        client-resp (promise)]
    (server/start server nil)
    (with-redefs [server/receive-notification (fn [& _args]
                                                (let [req (server/send-request server "server-sent-request" {:body "foo"})
                                                      _ (Thread/sleep 100)
                                                      resp (server/deref-or-cancel req 1000 :broke-deadlock)]
                                                  (deliver client-resp resp)))]
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
        (is (= "server-sent-request" (:method client-rcvd-request)))
        (async/put! input-ch (lsp.responses/response (:id client-rcvd-request) {:processed true})))
      (is (= {:processed true} (deref client-resp 10000 :timed-out))))
    (server/shutdown server)))

(deftest should-fail-pending-requests-if-too-many-inbound-messages-are-buffered
  ;; * If the server sends a request, and blocks waiting for the response,
  ;; * and if the client sends too many other messages before responding,
  ;; * then the server's buffer of unprocessed inbound messages will fill up.
  ;; To avoid dropping messages or buffering endlessly, the server eventually
  ;; aborts its request.
  (let [input-buffer-size 5
        input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :input-buffer-size 5})
        client-req-id* (atom 0)
        client-req (fn [body]
                     (async/put! input-ch (lsp.requests/request (swap! client-req-id* inc)
                                                                "client-sent-request"
                                                                body)))]
    (server/start server nil)
    (with-redefs [server/receive-request (fn [_ _ {:keys [server-action] :as client-req}]
                                           (if (= :block server-action)
                                             (let [req (server/send-request server "server-sent-request" {:body "foo"})]
                                               (try
                                                 (deref req)
                                                 {:processed client-req}
                                                 (catch Throwable _
                                                   {:error {:result :deref-aborted}})))
                                             {:processed client-req}))]
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
      (is (h/assert-no-take output-ch))
      ;; The client sends one more mesage, which is too many for the server to buffer.
      (client-req {:server-action :overflow})
      ;; To avoid blocking the client's inbound messages, the server's outbound
      ;; request is aborted, causing it to stop waiting for a client response.
      (is (= {:jsonrpc "2.0", :id 1, :error {:result :deref-aborted}}
             (h/assert-take output-ch)))
      ;; Now the server can process every other message from the client.
      (dotimes [n input-buffer-size]
        (is (= {:processed {:server-action :buffer, :input n}}
               (:result (h/assert-take output-ch)))))
      (is (= {:processed {:server-action :overflow}}
             (:result (h/assert-take output-ch)))))
    (server/shutdown server)))

(deftest should-cancel-request-when-cancellation-notification-receieved
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (server/start server nil)
    (with-redefs [server/receive-request (fn [& _args]
                                           (p/future (Thread/sleep 1000) "foo response"))]
      (async/put! input-ch (lsp.requests/request 1 "foo" {}))
      (async/put! input-ch (lsp.requests/notification "$/cancelRequest" {:id 1}))
      (is (= {:jsonrpc "2.0",
              :id 1,
              :error {:code -32800,
                      :message "The request {:id 1, :method \"foo\"} has been cancelled.",
                      :data {:id 1, :method "foo"}}}
             (h/assert-take output-ch))))
    (server/shutdown server)))

(deftest should-inform-handler-when-request-is-cancelled
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        task-completed (promise)]
    (server/start server nil)
    (with-redefs [server/receive-request (fn [_method context _params]
                                           (p/future
                                             (Thread/sleep 300)
                                             (deliver task-completed
                                                      (if @(:lsp4clj.server/req-cancelled? context)
                                                        :cancelled
                                                        :ran-anyway))))]
      (async/put! input-ch (lsp.requests/request 1 "initialize" {}))
      (async/put! input-ch (lsp.requests/notification "$/cancelRequest" {:id 1}))
      (is (= :cancelled (deref task-completed 1000 :timed-out))))
    (server/shutdown server)))

(deftest should-cancel-if-no-response-received
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        _ (server/start server nil)
        req (server/send-request server "req" {:body "foo"})]
    ;; client receives message, but doesn't reply
    (h/assert-take output-ch)
    (is (= :expected-timeout (server/deref-or-cancel req 100 :expected-timeout)))
    (is (= {:jsonrpc "2.0", :method "$/cancelRequest", :params {:id 1}}
           (h/assert-take output-ch)))
    (server/shutdown server)))

(deftest should-not-cancel-after-client-replies
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        _ (server/start server nil)
        req (server/send-request server "req" {:body "foo"})
        client-rcvd-msg (h/assert-take output-ch)]
    (async/put! input-ch (lsp.responses/response (:id client-rcvd-msg) {:processed true}))
    (is (= {:processed true} (server/deref-or-cancel req 1000 :test-timeout)))
    (h/assert-no-take output-ch)
    (is (not (future-cancel req)))
    (server/shutdown server)))

(deftest should-send-only-one-cancellation
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        _ (server/start server nil)
        req (server/send-request server "req" {:body "foo"})]
    (h/assert-take output-ch)
    (is (future-cancel req))
    (is (= "$/cancelRequest" (:method (h/assert-take output-ch))))
    (is (future-cancel req))
    (h/assert-no-take output-ch)
    (server/shutdown server)))

(deftest request-should-behave-like-a-clojure-future
  (testing "before being handled"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      (is (not (realized? req)))
      (is (not (future-done? req)))
      (is (not (future-cancelled? req)))
      (server/shutdown server)))
  (testing "after response"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})
          client-rcvd-msg (h/assert-take output-ch)]
      (async/put! input-ch (lsp.responses/response (:id client-rcvd-msg) {:processed true}))
      (is (= {:processed true} (server/deref-or-cancel req 1000 :test-timeout)))
      (is (realized? req))
      (is (future-done? req))
      (is (not (future-cancelled? req)))
      (server/shutdown server)))
  (testing "after cancellation"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      (future-cancel req)
      (is (realized? req))
      (is (future-done? req))
      (is (future-cancelled? req))
      (server/shutdown server))))

(deftest request-should-behave-like-a-java-future
  (testing "before being handled"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      (is (thrown? java.util.concurrent.TimeoutException
                   (.get req 500 java.util.concurrent.TimeUnit/MILLISECONDS)))
      (server/shutdown server)))
  (testing "after response"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})
          client-rcvd-msg (h/assert-take output-ch)]
      (async/put! input-ch (lsp.responses/response (:id client-rcvd-msg) {:processed true}))
      (is (= {:processed true} (.get req 100 java.util.concurrent.TimeUnit/MILLISECONDS)))
      (server/shutdown server)))
  (testing "after cancellation"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      (future-cancel req)
      (is (thrown? java.util.concurrent.CancellationException
                   (.get req 100 java.util.concurrent.TimeUnit/MILLISECONDS)))
      (server/shutdown server))))

(deftest request-should-behave-like-a-promesa-promise
  (testing "before being handled"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (p/promise (server/send-request server "req" {:body "foo"}))]
      (is (not (p/done? req)))
      (server/shutdown server)))
  (testing "after response"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (p/promise (server/send-request server "req" {:body "foo"}))
          client-rcvd-msg (h/assert-take output-ch)]
      (async/put! input-ch (lsp.responses/response (:id client-rcvd-msg) {:result "good"}))
      (is (= {:result :client-success
              :value 2
              :resp {:result "good"}}
             (-> req
                 (p/then (fn [resp] {:result :client-success
                                     :value 1
                                     :resp resp}))
                 (p/catch (fn [error-resp-ex] {:result :client-error
                                               :value 10
                                               :resp (ex-data error-resp-ex)}))
                 (p/timeout 1000 {:result :timeout
                                  :value 100})
                 (p/then #(update % :value inc))
                 (deref))))
      (is (p/done? req))
      (is (p/resolved? req))
      (is (not (p/rejected? req)))
      (is (not (p/cancelled? req)))
      (server/shutdown server)))
  (testing "after timeout"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (p/promise (server/send-request server "req" {:body "foo"}))]
      (is (= {:result :timeout
              :value 101}
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
                 (deref))))
      (is (not (p/done? req)))
      (is (not (p/resolved? req)))
      (is (not (p/rejected? req)))
      (is (not (p/cancelled? req)))
      (server/shutdown server)))
  (testing "after client error"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (p/promise (server/send-request server "req" {:body "foo"}))
          client-rcvd-msg (h/assert-take output-ch)]
      (async/put! input-ch
                  (-> (lsp.responses/response (:id client-rcvd-msg))
                      (lsp.responses/error {:code 1234
                                            :message "Something bad"
                                            :data {:body "foo"}})))
      (is (= {:result :client-error
              :value 11
              :resp {:jsonrpc "2.0",
                     :id 1,
                     :error {:code 1234,
                             :message "Something bad",
                             :data {:body "foo"}}}}
             (-> req
                 (p/then (fn [resp] {:result :client-success
                                     :value 1
                                     :resp resp}))
                 (p/catch (fn [error-resp-ex] {:result :client-error
                                               :value 10
                                               :resp (ex-data error-resp-ex)}))
                 (p/timeout 1000 {:result :timeout
                                  :value 100})
                 (p/then #(update % :value inc))
                 (deref))))
      (is (p/done? req))
      (is (not (p/resolved? req)))
      (is (p/rejected? req))
      (is (not (p/cancelled? req)))
      (server/shutdown server)))
  (testing "after cancellation"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (p/promise (server/send-request server "req" {:body "foo"}))]
      (h/assert-take output-ch)
      (p/cancel! req)
      (is (p/done? req))
      (is (p/cancelled? req))
      (is (= {:jsonrpc "2.0", :method "$/cancelRequest", :params {:id 1}}
             (h/assert-take output-ch)))
      (server/shutdown server))))

(defn- core-async-dispatch-thread? [^Thread thread]
  (re-matches #"async-dispatch-\d+" (.getName thread)))

(deftest can-determine-core-async-dispatch-thread
  (testing "current thread"
    (is (not (core-async-dispatch-thread? (Thread/currentThread)))))
  (testing "thread running go blocks"
    (let [thread (async/<!! (async/go (Thread/currentThread)))]
      (is (core-async-dispatch-thread? thread))))
  (testing "thread running core.async thread macro"
    (let [thread (async/<!! (async/thread (Thread/currentThread)))]
      (is (not (core-async-dispatch-thread? thread))))))

(deftest request-should-complete-on-a-suitable-executor
  (testing "successful completion"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          thread-p (-> (server/send-request server "req" {:body "foo"})
                       (p/then (fn [_] (Thread/currentThread))))
          client-rcvd-msg (h/assert-take output-ch)
          _ (async/put! input-ch (lsp.responses/response (:id client-rcvd-msg) {:result "good"}))
          thread (deref thread-p 100 nil)]
      (is (not (core-async-dispatch-thread? thread)))
      (is (instance? java.util.concurrent.ForkJoinWorkerThread thread)
          "completes on default ForkJoinPool executor")
      (server/shutdown server)))
  (testing "exceptional completion"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          thread-p (-> (server/send-request server "req" {:body "foo"})
                       (p/catch (fn [_] (Thread/currentThread))))
          client-rcvd-msg (h/assert-take output-ch)
          _ (async/put! input-ch
                        (-> (lsp.responses/response (:id client-rcvd-msg))
                            (lsp.responses/error {:code 1234
                                                  :message "Something bad"
                                                  :data {:body "foo"}})))
          thread (deref thread-p 100 nil)]
      (is (not (core-async-dispatch-thread? thread)))
      (is (instance? java.util.concurrent.ForkJoinWorkerThread thread)
          "completes on default ForkJoinPool executor")
      (server/shutdown server)))
  (testing "completion with :current-thread executor for legacy behavior"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch
                                      :response-executor :current-thread})
          _ (server/start server nil)
          thread-p (-> (server/send-request server "req" {:body "foo"})
                       (p/then (fn [_] (Thread/currentThread))))
          client-rcvd-msg (h/assert-take output-ch)
          _ (async/put! input-ch (lsp.responses/response (:id client-rcvd-msg) {:result "good"}))
          thread (deref thread-p 100 nil)]
      (is (core-async-dispatch-thread? thread) "completes on core.async dispatch thread")
      (server/shutdown server))))

(def fixed-clock
  (-> (java.time.LocalDateTime/of 2022 03 05 13 35 23 0)
      (.toInstant java.time.ZoneOffset/UTC)
      (java.time.Clock/fixed (java.time.ZoneId/systemDefault))))

(defn trace-log [lines]
  [:debug (string/join "\n" (into lines ["" "" ""]))])

(defn messages-trace-log [lines]
  [:debug (string/join "\n" lines)])

(deftest should-trace-received-notifications
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace-level "verbose"
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/notification "foo" {:result "body"}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received notification 'foo'"
                       "Params: {"
                       "  \"result\" : \"body\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-received-requests
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace-level "verbose"
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/request 1 "foo" {:result "body"}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received request 'foo - (1)'"
                       "Params: {"
                       "  \"result\" : \"body\""
                       "}"])
           (h/assert-take trace-ch)))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Sending response 'foo - (1)'. Request took 0ms. Request failed: Method not found (-32601)."
                       "Error data: {"
                       "  \"method\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-sent-requests
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace-level "verbose"
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)
        _ (server/start server nil)
        _ (server/send-request server "req" {:body "foo"})
        client-rcvd-msg (h/assert-take output-ch)]
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Sending request 'req - (1)'"
                       "Params: {"
                       "  \"body\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (async/put! input-ch (lsp.responses/response (:id client-rcvd-msg) {:processed true}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received response 'req - (1)'. Request took 0ms."
                       "Result: {"
                       "  \"processed\" : true"
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-sent-requests-with-error-responses
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace-level "verbose"
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)
        _ (server/start server nil)
        _ (server/send-request server "req" {:body "foo"})
        client-rcvd-msg (h/assert-take output-ch)]
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Sending request 'req - (1)'"
                       "Params: {"
                       "  \"body\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (async/put! input-ch
                (-> (lsp.responses/response (:id client-rcvd-msg))
                    (lsp.responses/error {:code 1234
                                          :message "Something bad"
                                          :data {:body "foo"}})))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received response 'req - (1)'. Request took 0ms. Request failed: Something bad (1234)."
                       "Error data: {"
                       "  \"body\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-unmatched-responses
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace-level "verbose"
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (async/put! input-ch (lsp.responses/response 100 {:processed true}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received response for unmatched request"
                       "Body: {"
                       "  \"jsonrpc\" : \"2.0\","
                       "  \"id\" : 100,"
                       "  \"result\" : {"
                       "    \"processed\" : true"
                       "  }"
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-unmatched-cancellations
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace-level "verbose"
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/notification "$/cancelRequest" {:id 1}))
    (h/assert-take trace-ch) ;; trace of Received notification '$/cancelRequest'
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received cancellation notification for unmatched request (1):"
                       "Params: {"
                       "  \"id\" : 1"
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-sent-notifications
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace-level "verbose"
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (server/send-notification server "req" {:body "foo"})
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Sending notification 'req'"
                       "Params: {"
                       "  \"body\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-log-unexpected-requests
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        log-ch (:log-ch server)]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/request 1 "foo" {}))
    (is (= [:warn "received unexpected request" "foo"]
           (h/assert-take log-ch)))
    (server/shutdown server)))

(deftest should-log-unexpected-notifications
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        log-ch (:log-ch server)]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/notification "foo" {}))
    (is (= [:warn "received unexpected notification" "foo"]
           (h/assert-take log-ch)))
    (server/shutdown server)))

(deftest should-log-parse-errors
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        log-ch (:log-ch server)]
    (server/start server nil)
    (async/put! input-ch :parse-error)
    (is (= [:error "Error reading message: Parse error (-32700)"]
           (h/assert-take log-ch)))
    (server/shutdown server)))

(deftest should-log-and-respond-to-internal-errors-during-requests
  (testing "when not deferred"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          log-ch (:log-ch server)]
      (server/start server nil)
      (with-redefs [server/receive-request (fn [& _args]
                                             (throw (ex-info "internal error" {:redef :data})))]
        (async/put! input-ch (lsp.requests/request 1 "foo" {}))
        (is (= {:jsonrpc "2.0",
                :id 1,
                :error {:code -32603,
                        :message "Internal error",
                        :data {:id 1, :method "foo"}}}
               (h/assert-take output-ch))))
      (let [[level e message] (h/assert-take log-ch)]
        (is (= :error level))
        (is (= {:redef :data} (ex-data e)))
        (is (= "Error receiving message: Internal error (-32603)\n{:id 1, :method \"foo\"}" message)))
      (server/shutdown server)))
  (testing "when deferred"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          log-ch (:log-ch server)]
      (server/start server nil)
      (with-redefs [server/receive-request (fn [& _args]
                                             (p/future
                                               (throw (ex-info "internal error" {:redef :data}))))]
        (async/put! input-ch (lsp.requests/request 1 "foo" {}))
        (is (= {:jsonrpc "2.0",
                :id 1,
                :error {:code -32603,
                        :message "Internal error",
                        :data {:id 1, :method "foo"}}}
               (h/assert-take output-ch))))
      (let [[level e message] (h/assert-take log-ch)]
        (is (= :error level))
        (is (= {:redef :data} (ex-data e)))
        (is (= "Error receiving message: Internal error (-32603)\n{:id 1, :method \"foo\"}" message)))
      (server/shutdown server))))

(deftest should-log-internal-errors-during-notifications
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        log-ch (:log-ch server)]
    (server/start server nil)
    (with-redefs [server/receive-notification (fn [& _args]
                                                (throw (ex-info "internal error" {:redef :data})))]
      (async/put! input-ch (lsp.requests/notification "foo" {}))
      (h/assert-no-take output-ch))
    (let [[level e message] (h/assert-take log-ch)]
      (is (= :error level))
      (is (= {:redef :data} (ex-data e)))
      (is (= "Error receiving message: Internal error (-32603)\n{:method \"foo\"}" message)))
    (server/shutdown server)))

(deftest should-log-malformed-messages
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        log-ch (:log-ch server)]
    (server/start server nil)
    (async/put! input-ch {:jsonrpc "1.0"})
    (is (= [:error "Error reading message: Invalid Request (-32600)"]
           (h/assert-take log-ch)))
    (server/shutdown server)))

(deftest should-merge-logs-and-traces-if-requested
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        merged-ch (async/chan (async/sliding-buffer 20))
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :log-ch merged-ch
                                    :trace-ch merged-ch
                                    :clock fixed-clock})]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/notification "foo" {:result "body"}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received notification 'foo'"
                       "Params: {"
                       "  \"result\" : \"body\""
                       "}"])
           (h/assert-take merged-ch)))
    (is (= [:warn "received unexpected notification" "foo"]
           (h/assert-take merged-ch)))
    (server/shutdown server)))

(deftest should-trace-at-verbose-level-when-deprecated-flag-is-set
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace? true ;; deprecated
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/notification "foo" {:result "body"}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received notification 'foo'"
                       "Params: {"
                       "  \"result\" : \"body\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-at-messages-level
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace-level "messages"
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/notification "foo" {:result "body"}))
    (is (= (messages-trace-log ["[Trace - 2022-03-05T13:35:23Z] Received notification 'foo'"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-at-off-level
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace-level "off"
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/notification "foo" {:result "body"}))
    (h/assert-no-take trace-ch)
    (server/shutdown server)))

(deftest should-change-trace-level
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace-level "verbose"
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (async/put! input-ch (lsp.requests/notification "foo" {:result "body"}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received notification 'foo'"
                       "Params: {"
                       "  \"result\" : \"body\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/set-trace-level server "messages")
    (async/put! input-ch (lsp.requests/notification "foo" {:result "body"}))
    (is (= (messages-trace-log ["[Trace - 2022-03-05T13:35:23Z] Received notification 'foo'"])
           (h/assert-take trace-ch)))
    (server/set-trace-level server "off")
    (async/put! input-ch (lsp.requests/notification "foo" {:result "body"}))
    (h/assert-no-take trace-ch)
    (server/set-trace-level server "verbose")
    (async/put! input-ch (lsp.requests/notification "foo" {:result "body"}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received notification 'foo'"
                       "Params: {"
                       "  \"result\" : \"body\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))
