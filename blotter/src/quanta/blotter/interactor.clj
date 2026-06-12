(ns quanta.blotter.interactor
  (:require
   [missionary.core :as m]
   [quanta.blotter.protocol :as p])
  (:import missionary.Cancelled))

(def ^:private max-req-age-ms 5000)

(defn- fresh-request? [req]
  (let [ts (:ts req)]
    (and ts (<= (- (System/currentTimeMillis) (.toEpochMilli ^java.time.Instant ts))
                max-req-age-ms))))

(defn- request-loop
  [trade-message-processor req-rdv push log]
  (m/sp
   (loop []
     (let [req (m/? req-rdv)
           _ (println "request: " req)
           fix-payload (p/api-order trade-message-processor req)]
       (when fix-payload   ;(fresh-request? req)
         (try
           (m/? (push fix-payload))
           (catch Exception ex
             (log {:type :order-failure
                   :direction :out
                   :data {:request req :error (ex-message ex)}}))))
       (recur)))))

(defn- message-loop
  [trade-message-processor pull log res-rdv]
  (m/sp
   (try
     (loop []
       (when-let [fix-payload (m/? (pull))]
         (when-let [update (p/blotter-order-update trade-message-processor fix-payload)]
           (m/? (res-rdv update))))
       (recur))
     (catch Cancelled _
       true))))

(defn create-trade-interactor
  [req-rdv res-rdv]
  (fn [account _connection-id push pull log asset-converter]
    (let [trade-message-processor (p/create-trade-messaging account asset-converter log)]
      (m/sp
       (m/? (m/join vector
                    (request-loop trade-message-processor req-rdv push log)
                    (message-loop trade-message-processor pull log res-rdv)))))))
