(ns lsp4clj.protocols)

(defprotocol IEndpoint
  (start [this context])
  (shutdown [this])
  (log [this level arg1] [this level arg1 arg2])
  (send-request [this method body] [this request])
  (send-notification [this method body] [this notif])
  (receive-response [this resp])
  (receive-request [this context req])
  (receive-notification [this context notif]))

(defprotocol IClock
  (instant [_]))

(defprotocol IInstant
  (to-epoch-milli [_])
  (truncate-to-millis-iso-string [_]))

(defprotocol IGenId
  (gen-id [_]))

(defprotocol IPendingReceivedRequestStore
  (-save-received! [_ pending-req])
  (-remove-received! [_ jsonrpc-req])
  (-get-by-jsonrpc-request-or-notification [_ jsonrpc-req])
  (-seq-received [_]))

(defprotocol IPendingSentRequestStore
  (-save-sent! [_ pending-req])
  (-remove-sent-by-resp! [_ jsonrpc-resp])
  (-seq-sent [_]))
