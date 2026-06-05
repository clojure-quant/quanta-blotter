(ns demo.position-pl
  "Compare FIFO vs average position P/L and derived :position/avg-exit-price.

  Run from the demo directory:
    clojure -M -e \"(require 'demo.position-pl) (demo.position-pl/run-demo!)\""
  (:require
   [clojure.pprint :refer [print-table]]
   [missionary.core :as m]
   [quanta.blotter.oms.flow.fill :as fill]
   [quanta.blotter.oms.flow.open-positions :as op]))

(defn- fill-msg [account asset side qty price]
  {:type :broker/order-filled
   :account/id account
   :asset asset
   :side side
   :qty qty
   :price price})

(defn- emissions [fills method]
  (let [flow (m/seed (map (partial apply fill-msg) fills))]
    (m/? (m/reduce conj [] (op/position-change-flow (fill/fill-flow flow) {:method method})))))

(defn- row [scenario method step pos]
  (let [side (:position/side pos)
        entry (:position/average-entry-price pos)
        realized (:position/realized-pl pos)
        max-qty (:position/qty pos)
        qty-open (:position/qty-open pos)
        avg-exit (:position/avg-exit-price pos)
        formula-pl (when (and max-qty (pos? max-qty) entry avg-exit)
                     (case side
                       :long (* max-qty (- avg-exit entry))
                       :short (* max-qty (- entry avg-exit))
                       nil))]
    {:scenario scenario
     :method (name method)
     :step step
     :side (name (or side :flat))
     :open (:position/open pos)
     :qty-open qty-open
     :max-qty max-qty
     :avg-entry entry
     :realized-pl realized
     :avg-exit avg-exit
     :formula-pl formula-pl
     :match? (= realized formula-pl)}))

(defn- analyze-scenario [label fills]
  (mapcat
   (fn [method]
     (map-indexed
      (fn [i pos] (row label method (inc i) pos))
      (emissions fills method)))
   [:fifo :average]))

(def scenarios
  {"partial-multi-lot"
   [[1 "X" :buy 50.0 10.0]
    [1 "X" :buy 50.0 12.0]
    [1 "X" :sell 60.0 15.0]]

   "full-close-simple"
   [[1 "X" :buy 100.0 10.0]
    [1 "X" :sell 100.0 15.0]]

   "full-close-multi-lot"
   [[1 "X" :buy 50.0 10.0]
    [1 "X" :buy 50.0 12.0]
    [1 "X" :sell 100.0 15.0]]

   "flip"
   [[1 "X" :buy 100.0 10.0]
    [1 "X" :sell 110.0 11.0]]})

(defn run-demo!
  "Print FIFO vs average comparison tables for scripted fill sequences."
  [& _]
  (doseq [[name fills] scenarios]
    (println \newline "===" name "===")
    (print-table [:scenario :method :step :side :open :qty-open :max-qty
                  :avg-entry :realized-pl :avg-exit :formula-pl :match?]
                 (analyze-scenario name fills)))
  (flush))

(comment
  (run-demo!))
