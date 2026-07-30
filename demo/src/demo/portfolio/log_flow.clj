(ns demo.portfolio.log-flow
  "Replay demo/data/combined.edn through portfolio and log working orders
   and open positions to log/print-demo.log.

  Run from the demo directory:
    clojure -X:print-trading-state-flow"
  (:require
   [clojure.string :as str]
   [missionary.core :as m]
   [ednx.edn :refer [read-edn]]
   [ednx.tick.edn :refer [add-tick-edn-handlers!]]
   [quanta.blotter.oms.flow.campaign :as campaign]
   [quanta.blotter.oms.portfolio :as portfolio]
   [quanta.blotter.oms.report.text-logger :refer [start-trading-state-logger!]]))

(add-tick-edn-handlers!)

(defn load-combined-edn
  "Read all channel messages from a multi-form EDN file."
  []
  (->> (slurp "data/combined.edn")
       str/split-lines
       (remove str/blank?)
       (mapv read-edn)))

(defn create-combined-flow []
  (let [messages (load-combined-edn)
        immediate-flow (m/seed messages)
        tagged-flow (campaign/campaign-tagged-combined-flow immediate-flow)]
    (m/ap (let [v (m/?> tagged-flow)]
            (m/? (m/sleep 100 v))))))

(defn run-demo!
  "Read combined.edn, start portfolio + wo/op logger, replay messages."
  [_]
  (let [channel-flow (create-combined-flow)
        portfolio (portfolio/portfolio-create channel-flow)
        dispose-logger! (start-trading-state-logger! portfolio "log/print-demo.log" 1000 true)
        _ (portfolio/portfolio-start! portfolio)]
    (try
      (m/? (m/sleep 50000))
      (finally
        (dispose-logger!)
        (portfolio/portfolio-stop! portfolio)
        (println "see log in log/print-demo.log")
        ))))

(comment
  (run-demo! {}))
