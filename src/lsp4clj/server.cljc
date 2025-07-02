(ns lsp4clj.server
  (:require
   #?(:cljs [goog.string :refer [format]])
   #?(:cljs [goog.string.format])
   #?(:cljs [promesa.impl.promise :as pimpl])
   [clojure.core.async :as async]
   [clojure.pprint :as pprint]
   [lsp4clj.coercer :as coercer]
   [lsp4clj.lsp.errors :as lsp.errors]
   [lsp4clj.lsp.requests :as lsp.requests]
   [lsp4clj.lsp.responses :as lsp.responses]
   [lsp4clj.protocols :as protocols]
   [lsp4clj.trace :as trace]
   [promesa.core :as p]
   [promesa.exec :as p.exec]
   [promesa.protocols :as p.protocols])
  (:import
   #?(:clj (java.util.concurrent CancellationException))))

(defn kw-identical? [a b]
  #?(:clj (identical? a b)
     :cljs (cljs.core/keyword-identical? a b)))

#?(:clj (set! *warn-on-reflection* true))

#?(:cljs (defrecord CancellationException []))

(defn- cancellation-exception? [e]
  (or (instance? CancellationException e)
      #?(:cljs (pimpl/isCancellationError e))))

(defn- resolve-ex-data [p]
  (p/catch p (fn [ex] (or (ex-data ex) (p/rejected ex)))))

#?(:clj (extend-type java.time.Clock
          protocols/IClock
          (instant [v] (.instant v))))

#?(:clj (extend-type java.time.Instant
          protocols/IInstant
          (to-epoch-milli [v] (.toEpochMilli v))
          (truncate-to-millis-iso-string [v] (str (.truncatedTo v java.time.temporal.ChronoUnit/MILLIS)))))

#?(:cljs (extend-type js/Date
           ;; the constant clock
           protocols/IClock
           (instant [v] v)
           protocols/IInstant
           (to-epoch-milli [v] (.getTime v))
           (truncate-to-millis-iso-string [v] (.toISOString v))))
#?(:cljs (defrecord SystemClock []
           protocols/IClock
           (instant [_] (js/Date.))))

(defrecord GenIntId [id*]
  protocols/IGenId
  (gen-id [_] (swap! id* inc)))

(defn build-default-clock []
  #?(:clj (java.time.Clock/systemDefaultZone)
     :cljs (SystemClock.)))

(defprotocol IBlockingDerefOrCancel
  (deref-or-cancel [this timeout-ms timeout-val]))

#?(:clj (defrecord PendingRequest [p request response started cancelled? on-cancel]
          clojure.lang.IDeref
          (deref [_] (deref p))
          clojure.lang.IBlockingDeref
          (deref [_ timeout-ms timeout-val]
            (deref (resolve-ex-data p) timeout-ms timeout-val))
          IBlockingDerefOrCancel
          (deref-or-cancel [this timeout-ms timeout-val]
            (let [result (deref (resolve-ex-data p) timeout-ms ::timeout)]
              (if (identical? ::timeout result)
                (do (p/cancel! this)
                    timeout-val)
                result)))
          clojure.lang.IPending
          (isRealized [_] (p/done? p))
          java.util.concurrent.Future
          (get [_] (deref p))
          (get [_ timeout unit]
            (let [result (deref p (.toMillis unit timeout) ::timeout)]
              (if (identical? ::timeout result)
                (throw (java.util.concurrent.TimeoutException.))
                result)))
          (isCancelled [_] @cancelled?)
          (isDone [_] (p/done? p))
          (cancel [_ _interrupt?]
            (p/cancel! p)
            (p/cancelled? p))
          p.protocols/IPromiseFactory
          (-promise [_] p))
   :cljs (defrecord PendingRequest [p request response started cancelled? on-cancel]
           IBlockingDerefOrCancel
           (deref-or-cancel [this timeout-ms timeout-val]
             (let [p' (resolve-ex-data p)]
               (-> (p/timeout p' timeout-ms)
                   (p/handle (fn [v e]
                               (if e
                                 (if (and (instance? p/TimeoutException e)
                                          (not (p/done? p')))
                                   (do (p/cancel! this) timeout-val)
                                   (throw e))
                                 v))))))
           p.protocols/ICancellable
           (-cancel! [_]
             (when-not (p/done? p)
               (try (on-cancel) (catch :default _ nil))
               (reset! cancelled? true)))
           (-cancelled? [_]
             @cancelled?)

           p.protocols/IPromiseFactory
           (-promise [_] p)
           p.protocols/IState
           (-extract [_] (p.protocols/-extract p))
           (-resolved? [_] (p.protocols/-resolved? p))
           (-rejected? [_] (p.protocols/-rejected? p))
           (-pending? [_] (p.protocols/-pending? p))))

(defmethod pprint/simple-dispatch PendingRequest [{:keys [request started]}]
  (pprint/pprint {:request (select-keys request [:id :method]) :started started}))

;;; https://github.com/thi-ng/color/issues/10
#?(:clj (prefer-method print-method java.util.Map clojure.lang.IDeref))

(defn pending-request
  [request started on-cancel]
  (let [p (p/deferred)
        cancelled? (atom false)
        on-cancel* #(do #?(:cljs (p/reject! p (CancellationException.)))
                        (when-not (first (reset-vals! cancelled? true)) (on-cancel)))]

    ;; Add cancellation callback to PendingRequest for ClojureScript migration.
    ;; ClojureScript's promesa promise does not support cancellation.

    ;; To avoid leak (someone cancel on the `p` field), we still register the
    ;; callback onto `p`.
    #?(:clj (p/catch p cancellation-exception? (fn [_] (on-cancel*))))

    ;; Currently, PendingRequest's cancellation delegates to
    ;; java.util.concurrent.Future, which implements the
    ;; `promesa.protocols/ICancellable`
    (map->PendingRequest {:p p :request request :response (p/deferred) :started started
                          :cancelled? cancelled? :on-cancel on-cancel*})))

(defn ^:private format-error-code [description error-code]
  (let [{:keys [code message]} (lsp.errors/by-key error-code)]
    (format "%s: %s (%s)" description message code)))

(defn ^:private log-error-receiving [server e message]
  (let [message-details (select-keys message [:id :method])
        log-title (format-error-code "Error receiving message" :internal-error)]
    (protocols/log server :error e (str log-title "\n" message-details))))

(defn thread-loop [buf-or-n f]
  (let [ch (async/chan buf-or-n)]
    #?(:clj
       (async/thread
         (loop []
           (when-let [arg (async/<!! ch)]
             (f arg)
             (recur))))
       :cljs
       (async/go
         (loop []
           (when-let [arg (async/<! ch)]
             (f arg)
             (recur)))))
    ch))

(def +input-buffer-size+
  ;; (Jacob Maine): This number is picked out of thin air. I have no idea how to
  ;; estimate how big the buffer could or should be. LSP communication tends to
  ;; be very quiet, then very chatty, so it depends a lot on what the client and
  ;; server are doing. I also don't know how many messages we could store
  ;; without running into memory problems, since this is dependent on so many
  ;; variables, not just the size of the JVM's memory, but also the size of the
  ;; messages, which can be anywhere from a few bytes to several megabytes.
  1024)

;; Expose endpoint methods to language servers

(def start protocols/start)
(def shutdown protocols/shutdown)
(def send-request protocols/send-request)
(def send-notification protocols/send-notification)

(defn ^:private internal-error-response [resp req]
  (let [error-body (lsp.errors/internal-error (select-keys req [:id :method]))]
    (lsp.responses/error resp error-body)))

(defn ^:private cancellation-response [resp req]
  (let [message-details (select-keys req [:id :method])
        error-body (lsp.errors/body :request-cancelled
                                    (format "The request %s has been cancelled."
                                            (pr-str message-details))
                                    message-details)]
    (lsp.responses/error resp error-body)))

(defn trace [{:keys [tracer* trace-ch]} trace-f & params]
  (when-let [trace-body (apply trace-f @tracer* params)]
    (async/put! trace-ch [:debug trace-body])))

(defrecord PendingReceivedRequest [result-promise request cancelled?]
  p.protocols/ICancellable
  (-cancel! [_]
    (p/cancel! result-promise)
    (reset! cancelled? true))
  (-cancelled? [_]
    @cancelled?))

(defn pending-received-request [{:keys [handle-request] :as server} context req]
  (let [cancelled? (atom false)
        ;; coerce result/error to promise
        context (assoc context ::req-cancelled? cancelled?)
        result-promise (p/promise (handle-request server context req))]
    (map->PendingReceivedRequest
      {:result-promise result-promise
       :request req
       :cancelled? cancelled?})))

(def ^:dynamic *send-advice* identity)

(defrecord ChanServer [input-ch
                       output-ch
                       log-ch
                       trace-ch
                       tracer*
                       clock
                       response-executor
                       on-close
                       request-id*
                       join
                       on-cancel-request
                       handle-request
                       handle-notification
                       input-buffer-size

                       pending-received-requests*
                       save-pending-received-request!
                       remove-pending-received-request!

                       pending-sent-requests*
                       reject-pending-sent-requests!
                       save-pending-sent-request!
                       remove-pending-sent-request!]
  protocols/IEndpoint
  (start [this context]
    ;; Start receiving messages.
    (let [client-initiated-in-ch
          (thread-loop
            input-buffer-size
            (fn [[message-type message]]
              (if (kw-identical? :request message-type)
                (protocols/receive-request this context message)
                (protocols/receive-notification this context message))))

          pipeline
          (async/go-loop []
            (if-let [message (async/<! input-ch)]
              (let [message-type (coercer/input-message-type message)]
                (case message-type
                  (:parse-error :invalid-request)
                  (protocols/log this :error (format-error-code "Error reading message" message-type))
                  (:response.result :response.error)
                  (protocols/receive-response this message)
                  (:request :notification)
                  (when-not (async/offer! client-initiated-in-ch [message-type message])
                    ;; Buffers full. Fail any waiting pending requests and...
                    (reject-pending-sent-requests!
                      this (ex-info "Buffer of client messages exhausted." {}))
                    ;; ... try again, but park this time.
                    (async/>! client-initiated-in-ch [message-type message])))
                (recur))
              (async/close! client-initiated-in-ch)))]
      (async/go
        ;; Wait to stop receiving messages.
        (async/<! pipeline)
        ;; The pipeline has closed, indicating input-ch has closed. We're done
        ;; receiving. Do cleanup.

        (reject-pending-sent-requests!
          this (ex-info "Server shutting down. Input is closed so no response is possible." {}))

        (async/close! output-ch)
        (async/close! log-ch)
        (some-> trace-ch async/close!)
        (on-close)
        #?(:clj (deliver join :done)
           :cljs (p/resolve! join :done))))
    ;; invokers can deref the return of `start` to stay alive until server is
    ;; shut down
    join)
  (shutdown [_this]
    ;; Closing input-ch will drain pipeline then close it which, in turn,
    ;; triggers additional cleanup.
    (async/close! input-ch)
    #?(:clj (deref join 10e3 :timeout)
       :cljs (p/timeout join 10e3 :done)))
  (log [_this level arg1]
    (async/put! log-ch [level arg1]))
  (log [_this level arg1 arg2]
    (async/put! log-ch [level arg1 arg2]))
  (send-request [this method body]
    (let [id (protocols/gen-id request-id*)
          now (protocols/instant clock)
          req (vary-meta (lsp.requests/request id method body) *send-advice*)
          pending-request (pending-request req now #(on-cancel-request this req))]
      (trace this trace/sending-request req now)
      ;; Important: record request before sending it, so it's sure to be
      ;; available during receive-response.
      (save-pending-sent-request! this pending-request)
      ;; respect back pressure from clients that are slow to read; (go (>!)) will not suffice
      #?(:clj (do
                (async/>!! output-ch req)
                pending-request)
         :cljs (let [p (p/deferred)]
                 (async/go (async/>! output-ch req)
                           (p/resolve! p :done))
                 (p/then p (fn [_] pending-request))))))
  (send-notification [this method body]
    (let [now (protocols/instant clock)
          notif (vary-meta (lsp.requests/notification method body) *send-advice*)]
      (trace this trace/sending-notification notif now)
      ;; respect back pressure from clients that are slow to read; (go (>!)) will not suffice
      #?(:clj (do (async/>!! output-ch notif)
                  nil)
         :cljs (let [p (p/deferred)]
                 (async/go (async/>! output-ch notif)
                           (p/resolve! p nil))
                 p))))
  (receive-response [this {:keys [id error result] :as resp}]
    (try
      (let [now (protocols/instant clock)]
        (if-let [{:keys [p request response started] :as req}
                 (remove-pending-sent-request! this resp)]
          (do
            (trace this trace/received-response request resp started now)
            ;; Note that we are called from the server's pipeline, a core.async
            ;; go-loop, and therefore must not block. Callbacks of the pending
            ;; request's promise (`p`) will be executed in the completing
            ;; thread, whatever that thread is. Since the callbacks are not
            ;; under our control, they are under our users' control, they could
            ;; block. Therefore, we do not want the completing thread to be our
            ;; thread. This is very easy for users to miss, therefore we
            ;; complete the promise using an explicit executor.
            (p.exec/submit! response-executor
                            (fn []
                              (if error
                                (let [e (ex-info "Received error response" resp)]
                                  (p/reject! p e)
                                  (p/reject! response e))
                                (do (p/resolve! p result)
                                    (p/resolve! response resp))))))
          (trace this trace/received-unmatched-response resp now)))
      (catch #?(:clj Throwable :cljs :default) e
        (log-error-receiving this e resp))))
  (receive-request [this context {:keys [id method params] :as req}]
    (let [started (protocols/instant clock)
          resp (lsp.responses/response id)]
      (try
        (trace this trace/received-request req started)
        (let [pending-req (pending-received-request this context req)]
          (save-pending-received-request! this pending-req)
          (-> pending-req
              :result-promise
              ;; convert result/error to response
              (p/then
                (fn [result]
                  (if (kw-identical? ::method-not-found result)
                    (do
                      (protocols/log this :warn "received unexpected request" method)
                      (lsp.responses/error resp (lsp.errors/not-found method)))
                    (lsp.responses/infer resp result))))
              ;; Handle
              ;; 1. Exceptions thrown within p/future created by receive-request.
              ;; 2. Cancelled requests.
              (p/catch
               (fn [e]
                 (if (cancellation-exception? e)
                   (cancellation-response resp req)
                   (do
                     (log-error-receiving this e req)
                     (internal-error-response resp req)))))
              (p/finally
                (fn [resp _error]
                  (remove-pending-received-request! this req)
                  (trace this trace/sending-response req resp started (protocols/instant clock))
                  (let [resp (vary-meta resp assoc :request req)]
                    #?(:clj (async/>!! output-ch resp)
                       :cljs (async/go (async/>! output-ch resp))))))))
        (catch #?(:clj Throwable :cljs :default) e ;; exceptions thrown by receive-request
          (log-error-receiving this e req)
          (let [resp (vary-meta (internal-error-response resp req) assoc :request req)]
            #?(:clj (async/>!! output-ch resp)
               :cljs (let [p (p/deferred)]
                       (async/go
                         (p/resolve! p (async/>! output-ch resp)))
                       p)))))))
  (receive-notification [this context {:keys [method params] :as notif}]
    (try
      (let [now (protocols/instant clock)]
        (trace this trace/received-notification notif now)
        (let [result (handle-notification this context notif)]
          (when (kw-identical? ::method-not-found result)
            (protocols/log this :warn "received unexpected notification" method))))
      (catch #?(:clj Throwable :cljs :default) e
        (log-error-receiving this e notif)))))

(defn set-trace-level [server trace-level]
  (update server :tracer* reset! (trace/tracer-for-level trace-level)))

;; Let language servers implement their own message receivers. These are
;; slightly different from the identically named protocols versions, in
;; that they receive the message params, not the whole message.

(defmulti receive-request (fn [method _context _params] method))
(defmulti receive-notification (fn [method _context _params] method))

(defmethod receive-request :default [_method _context _params] ::method-not-found)
(defmethod receive-notification :default [_method _context _params] ::method-not-found)

(defn on-cancel-send-cancelRequest
  "https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#cancelRequest"
  [server {:keys [id]}]
  (protocols/send-notification server "$/cancelRequest" {:id id}))

(defn on-notification-cancelRequest
  "https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#cancelRequest"
  [server _context {:keys [params] :as notif}]
  (if-let [pending-req (get @(:pending-received-requests* server) (:id params))]
    (p/cancel! pending-req)
    (let [now (protocols/instant (:clock server))]
      (trace server trace/received-unmatched-cancellation-notification notif now))))

(defn chan-server
  [{:keys [output-ch input-ch log-ch trace? trace-level trace-ch clock on-close
           response-executor on-cancel-request handle-request handle-notification
           input-buffer-size save-pending-sent-request! remove-pending-sent-request!
           reject-pending-sent-requests! save-pending-received-request!
           remove-pending-received-request!]
    :or {clock (build-default-clock)
         on-close (constantly nil)
         response-executor :default}}]
  (let [;; before defaulting trace-ch, so that default is "off"
        tracer (trace/tracer-for-level (or trace-level
                                           (when (or trace? trace-ch) "verbose")
                                           "off"))
        log-ch (or log-ch (async/chan (async/sliding-buffer 20)))
        trace-ch (or trace-ch (async/chan (async/sliding-buffer 20)))
        on-cancel-request
        (or on-cancel-request
            on-cancel-send-cancelRequest)
        handle-request
        (or handle-request
            (fn [_server context {:keys [method params]}]
              (receive-request method context params)))
        handle-notification
        (or handle-notification
            (fn [server context {:keys [method params] :as notif}]
              (case method
                "$/cancelRequest"
                (on-notification-cancelRequest server context notif)
                (receive-notification method context params))))

        ;; Here we can utilize the attached metadata (user attached metadata on
        ;; request, or transport implemenetation attached metadata on response).

        pending-received-requests* (atom {})
        save-pending-received-request!
        (or save-pending-received-request!
            (fn [_server pending-req]
              (swap! pending-received-requests* assoc
                     (get-in pending-req [:request :id]) pending-req)))
        remove-pending-received-request!
        (or remove-pending-received-request!
            (fn [_server req]
              (swap! pending-received-requests* dissoc (:id req))))
        pending-sent-requests* (atom {})
        ;; Save request before sending
        save-pending-sent-request!
        (or save-pending-sent-request!
            (fn [_server pending-request]
              (swap! pending-sent-requests* assoc
                     (get-in pending-request [:request :id])
                     pending-request)))
        ;; Get saved request base on received response. SHOULD clear the saved
        ;; request to avoid handling multiple response for same request.
        remove-pending-sent-request!
        (or remove-pending-sent-request!
            (fn [_server {:keys [id] :as resp}]
              (let [[pending-requests _] (swap-vals! pending-sent-requests* dissoc id)]
                (get pending-requests id))))
        reject-pending-sent-requests!
        (or reject-pending-sent-requests!
            (fn [_server exception]
              (doseq [pending-request (vals @pending-sent-requests*)]
                (p/reject! (:p pending-request) exception))))]
    (map->ChanServer
      {:output-ch output-ch
       :input-ch input-ch
       :log-ch log-ch
       :trace-ch trace-ch
       :tracer* (atom tracer)
       :clock clock
       :on-close on-close
       :response-executor response-executor
       :request-id* (GenIntId. (atom 0))
       :join #?(:clj (promise) :cljs (p/deferred))
       :handle-request handle-request
       :handle-notification handle-notification
       :on-cancel-request on-cancel-request
       :input-buffer-size (or input-buffer-size +input-buffer-size+)

       :pending-received-requests* pending-received-requests*
       :save-pending-received-request! save-pending-received-request!
       :remove-pending-received-request! remove-pending-received-request!

       :pending-sent-requests* (atom {})
       :reject-pending-sent-requests! reject-pending-sent-requests!
       :save-pending-sent-request! save-pending-sent-request!
       :remove-pending-sent-request! remove-pending-sent-request!})))
