(ns quanta.blotter.cli.client
  "Babashka websocket client for the flowy OMS server.

   Exposes a small request/response layer on top of the flowy `:op :exec`
   protocol: every request is tagged with an `:id`, and the matching server
   response (`{:op :exec :id <id> :result <rows>}`) is delivered back to a
   promise so callers can use a synchronous request/response style."
  (:require
   [cognitect.transit :as transit]
   [clojure.java.io :as io]
   [babashka.http-client.websocket :as bws])
  (:import
   (java.io ByteArrayOutputStream)
   (java.nio ByteBuffer)))

(defn decode-str
  "Decode a transit-json string into clojure data."
  [s]
  (let [in (io/input-stream (.getBytes ^String s))
        reader (transit/reader in :json)]
    (transit/read reader)))

(defn encode
  "Encode a clojure value to a transit-json string."
  [v]
  (let [out (ByteArrayOutputStream.)
        writer (transit/writer out :json)]
    (transit/write writer v)
    (.toString out)))

;; ---------------------------------------------------------------------------
;; incoming messages

(defn- handle-decoded
  "Route a decoded server message to the waiting request promise (if any)."
  [conn msg]
  (when-let [id (and (map? msg) (:id msg))]
    (when-let [p (get @(:pending conn) id)]
      (swap! (:pending conn) dissoc id)
      (deliver p msg))))

(defn- on-message
  "Accumulate (possibly fragmented) websocket frames, decode complete
   transit messages, and dispatch them. Ignores the `HEARTBEAT` keepalive."
  [conn msg last?]
  (let [^StringBuilder sb (:buf conn)]
    (.append sb (if (instance? ByteBuffer msg)
                  (String. (.array ^ByteBuffer msg))
                  (str msg)))
    (when last?
      (let [text (.toString sb)]
        (.setLength sb 0)
        (when-not (= "HEARTBEAT" text)
          (try
            (handle-decoded conn (decode-str text))
            (catch Exception e
              (println "client decode error:" (ex-message e)))))))))

;; ---------------------------------------------------------------------------
;; keepalive

(defn- start-keepalive!
  "Send a `HEARTBEAT` text frame periodically so the server (59s timeout)
   keeps the connection open while the TUI sits idle. Returns a stop atom."
  [conn]
  (let [stop (atom false)]
    (doto (Thread.
           (fn []
             (while (not @stop)
               (Thread/sleep 20000)
               (when-not @stop
                 (try
                   (bws/send! @(:ws conn) "HEARTBEAT")
                   (catch Exception _ (reset! stop true)))))))
      (.setDaemon true)
      (.start))
    stop))

;; ---------------------------------------------------------------------------
;; connection + requests

(defn connect!
  "Open a websocket connection to the flowy server. Returns a `conn` map with
   `:ws`, `:pending` and `:counter` used by `request!` / `request-sync!`."
  ([] (connect! "ws://localhost:9000/flowy"))
  ([ws-url]
   (let [conn {:pending (atom {})
               :counter (atom 0)
               :buf (StringBuilder.)
               :ws (atom nil)}
         ws (bws/websocket
             {:uri ws-url
              :on-open (fn [_ws]
                         (println "WebSocket connection established."))
              :on-message (fn [_ws msg last?]
                            (on-message conn msg last?))
              :on-close (fn [_ws status reason]
                          (println "WebSocket closed! status:" status "reason:" reason))})]
     (reset! (:ws conn) ws)
     (assoc conn :keepalive (start-keepalive! conn)))))

(defn request!
  "Send an `:exec` request for `fun` with optional `args`, returning a promise
   that resolves to the decoded server response message."
  ([conn fun] (request! conn fun nil))
  ([conn fun args]
   (let [id (swap! (:counter conn) inc)
         p (promise)
         req (cond-> {:op :exec :fun fun :id id}
               (seq args) (assoc :args args))]
     (swap! (:pending conn) assoc id p)
     (bws/send! @(:ws conn) (encode req))
     p)))

(defn request-sync!
  "Issue a request and block until the response arrives (or `timeout-ms`).
   Returns the `:result` rows, or throws on timeout / server error."
  ([conn fun] (request-sync! conn fun nil 10000))
  ([conn fun args] (request-sync! conn fun args 10000))
  ([conn fun args timeout-ms]
   (let [p (request! conn fun args)
         msg (deref p timeout-ms ::timeout)]
     (cond
       (= ::timeout msg)
       (throw (ex-info "request timed out" {:fun fun :args args}))

       (:error msg)
       (throw (ex-info "server error" {:fun fun :error (:error msg)}))

       (contains? msg :err)
       (throw (ex-info "server error" {:fun fun :error (:err msg)}))

       :else
       (:result msg)))))

(defn close!
  "Stop the keepalive thread and close the websocket."
  [conn]
  (when-let [stop (:keepalive conn)]
    (reset! stop true))
  (when-let [ws @(:ws conn)]
    (bws/close! ws)))
