(ns quanta.blotter.oms.report.text-logger
  (:require
   [missionary.core :as m]
   [tick.core :as t]
   [quanta.missionary.logger :as logger]
   [quanta.blotter.oms.print :as print]))

(defn- print-state [{:keys [trader trade order position working-order open-position] :as _state}]
  (let [opts {:max-width 300}
        s (str "\r\n trading-state as of " (print/format-ts-ms (t/instant)) "\r\n")

        s (if (empty? trader)
            s
            (str s "\r\ntrader requests:\r\n" (print/trader-requests-table trader opts)))

        s (if (empty? trade)
            s
            (str s "\r\ntrades:\r\n" (print/trades-table trade opts)))

        s (if (empty? order)
            s
            (str s "\r\nfinished orders:\r\n" (print/working-orders-table order opts)))

        s (if (empty? position)
            s
            (str s "\r\nfinished positions:\r\n" (print/open-positions-table position opts)))

        s (if (some? working-order)
            (str s "\r\nworking-order:\r\n" (print/working-orders-table (vals working-order) opts))
            s)

        s (if (some? open-position)
            (str s "\r\nopen-position:\r\n" (print/open-positions-table (vals open-position) opts))
            s)]
    s))

(defn- acc-out-msg [state out-msg]
  (cond-> state
    (contains? out-msg :trader)
    (update :trader conj (:trader out-msg))

    (contains? out-msg :trade)
    (update :trade conj (:trade out-msg))

    (contains? out-msg :order)
    (update :order conj (:order out-msg))

    (and (contains? out-msg :position-change)
         (false? (get-in out-msg [:position-change :position/open])))
    (update :position conj (:position-change out-msg))

    (contains? out-msg :working-order)
    (assoc :working-order (:working-order out-msg))

    (contains? out-msg :open-position)
    (assoc :open-position (:open-position out-msg))))

(defn portfolio-print-flow
  "Batch portfolio :out-flow events every `interval-ms` and pretty-print."
  [{:keys [out-flow] :as portfolio} interval-ms]
  (assert (map? portfolio) "portfolio-print-flow needs a portfolio map")
  (assert out-flow "portfolio-print-flow needs :out-flow")
  (let [batched-f (m/ap
                   (let [[_ batch] (m/?> (m/group-by {} out-flow))]
                     (m/? (->> (m/ap (m/amb= (m/?> batch)
                                             (m/? (m/sleep interval-ms))))
                               (m/eduction (take-while some?))
                               (m/reduce acc-out-msg {:trader []
                                                      :trade [] :order [] :position []
                                                      :working-order nil :open-position nil})))))]
    (m/ap
     (print-state (m/?> batched-f)))))

(defn start-trading-state-logger!
  "Start periodic logger for a portfolio (or legacy trading-state map with :out-flow)."
  [portfolio log-file interval-ms console?]
  (assert portfolio "start-trading-state-logger! needs portfolio")
  (let [l (logger/create-logger log-file console?)
        log-f (portfolio-print-flow portfolio interval-ms)
        dispose! (logger/start-log-flow-to-logger l log-f)]
    dispose!))
