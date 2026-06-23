(ns demo.print-wo-op
  "Replay demo/data/combined.edn through a stubbed OMS and log working orders
   and open positions to log/print-demo.log.

  Run from the demo directory:
    clojure -X:print-wo-op-demo"
  (:require
   [clojure.string :as str]
   [ednx.edn :refer [read-edn]]
   [ednx.tick.edn :refer [add-tick-edn-handlers!]]
   [missionary.core :as m]
   [quanta.blotter.util :refer [flow-sender]]
   [quanta.blotter.oms.flow.print :refer [start-open-positions-working-order-logger!]]
   [quanta.blotter.oms.flow.trading-state :as trading-state]))

(add-tick-edn-handlers!)

(def default-combined-edn "data/combined.edn")
(def default-log-file "log/print-demo.log")

(defn load-combined-edn
  "Read all channel messages from a multi-form EDN file."
  ([]
   (load-combined-edn default-combined-edn))
  ([path]
   (->> (slurp path)
        str/split-lines
        (remove str/blank?)
        (mapv read-edn))))

(defn create-stub-oms
  "Minimal OMS map with a live combined-flow sender."
  []
  (let [{:keys [flow send]} (flow-sender)]
    {:combined-flow flow
     :send send}))

(defn- seed-combined! [{:keys [send]} messages]
  (doseq [msg messages]
    (send msg)))

(defn run-demo!
  "Read combined.edn, start trading-state + wo/op logger, replay messages."
  ([]
   (run-demo! {}))
  ([{:keys [combined-edn log-file wait-ms]
     :or {combined-edn default-combined-edn
          log-file default-log-file
          wait-ms 500}}]
   (let [messages (load-combined-edn combined-edn)
         oms (create-stub-oms)
         trading-state (trading-state/start-trading-state! (:combined-flow oms))
         oms (assoc oms :trading-state trading-state)
         {:keys [dispose]} (start-open-positions-working-order-logger! oms log-file)]
     (try
       (seed-combined! oms messages)
       (m/? (m/sleep wait-ms))
       (finally
         (dispose)
         (trading-state/stop-trading-state! trading-state))))))

(comment
  (run-demo!)
  )
