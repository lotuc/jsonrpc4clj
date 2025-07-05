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

(defprotocol ^{:doc "

`typ`: `:sent` | `:received`

`remove-pending` removes given request from store & returns it.

How `ChanServer` uses the store:
- Before sending request to `output-ch`, saves it with jsonrpc request map as `k`
- On receiving request's response from `input-ch`, removes & gets the saved
  request with the jsonrpc response as `k`
- On receiving request from `input-ch`, saves it with jsonrpc request map as `k`
  before handling
- Done handling the receivied request, removes with jsonrpc request map as `k`

 "}
 IPendingRequestStore
  (save-pending [this typ k pending-req])
  (get-pending [this typ k])
  (remove-pending [this typ k])
  (seq-pending [this typ]))
