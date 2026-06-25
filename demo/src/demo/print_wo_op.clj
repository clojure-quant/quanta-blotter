(ns demo.print-wo-op
  "Replay demo/data/combined.edn through a stubbed OMS and log working orders
   and open positions to log/print-demo.log.

  Run from the demo directory:
    clojure -X:print-wo-op-demo"
  (:require
   [clojure.string :as str]
   [missionary.core :as m]
   [ednx.edn :refer [read-edn]]
   [ednx.tick.edn :refer [add-tick-edn-handlers!]]
   [quanta.blotter.oms.flow.campaign :as campaign]
   [quanta.blotter.oms.flow.trading-state :as trading-state]
   [quanta.blotter.oms.flow.print :refer [start-trading-state-logger!]]))

(add-tick-edn-handlers!)

(defn load-combined-edn
  "Read all channel messages from a multi-form EDN file."
  []
  (->> (slurp "data/combined.edn")
       str/split-lines
       (remove str/blank?)
       (mapv read-edn)))

(defn create-combined-flow  []
  (let [messages (load-combined-edn)
        immediate-flow (m/seed messages)
        tagged-flow (campaign/campaign-tagged-combined-flow immediate-flow)]
    (m/ap (let [v (m/?> tagged-flow)]
            (m/? (m/sleep 100 v))))))

(defn run-demo!
  "Read combined.edn, start trading-state + wo/op logger, replay messages."
  [& _]
  (let [channel-flow (create-combined-flow)
        trading-state (trading-state/create-trading-state! channel-flow)
        dispose! (start-trading-state-logger! trading-state "log/print-demo.log" 1000 true)]
    (try
      (m/? (m/sleep 50000))
      (finally
        (dispose!)))))

(comment
  (run-demo!))
