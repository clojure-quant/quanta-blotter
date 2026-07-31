(ns quanta.blotter.oms.report.text-logger
  (:require
   [missionary.core :as m]
   [tick.core :as t]
   [quanta.missionary.logger :as logger]
   [quanta.blotter.oms.print :as print]))

(defn- print-working-order-snapshots [s snapshots opts]
  (reduce
   (fn [s {:keys [account/id orders]}]
     (if (empty? orders)
       (str s "\r\nbroker report working orders account id " id " - None.")
       (str s "\r\nbroker report working orders account id " id ":\r\n"
            (print/working-orders-table orders opts))))
   s
   snapshots))

(defn- print-open-position-snapshots [s snapshots opts]
  (reduce
   (fn [s {:keys [account/id positions]}]
     (if (empty? positions)
       (str s "\r\nbroker report open positions account id " id " - None.")
       (str s "\r\nbroker report open positions account id " id ":\r\n"
            (print/open-positions-table positions opts))))
   s
   snapshots))

(defn- print-state [{:keys [schema-error trader trade order-closed position-closed
                            working-order open-position]
                     :as state}]
  (let [opts {:max-width 300}
        s (str "\r\n trading-state as of " (print/format-ts-ms (t/inst)) "\r\n")

        s (if (empty? schema-error)
            s
            (str s "\r\nschema errors:\r\n" (print/schema-errors-table schema-error opts)))

        s (if (empty? trader)
            s
            (str s "\r\ntrader requests:\r\n" (print/trader-requests-table trader opts)))

        s (print-working-order-snapshots
           s (get state :broker/working-orders) opts)

        s (print-open-position-snapshots
           s (get state :broker/open-positions) opts)

        s (if (empty? trade)
            s
            (str s "\r\ntrades:\r\n" (print/trades-table trade opts)))

        s (if (empty? order-closed)
            s
            (str s "\r\nfinished orders:\r\n" (print/working-orders-table order-closed opts)))

        s (if (empty? position-closed)
            s
            (str s "\r\nfinished positions:\r\n" (print/open-positions-table position-closed opts)))

        s (if (some? working-order)
            (str s "\r\nworking-order:\r\n" (print/working-orders-table (vals working-order) opts))
            s)

        s (if (some? open-position)
            (str s "\r\nopen-position:\r\n" (print/open-positions-table (vals open-position) opts))
            s)]
    s))

(defn- acc-out-msg [state out-msg]
  (let [msg (:msg out-msg)]
    (cond-> state
      (= :broker/orderupdate-schema-error (:type msg))
      (update :schema-error conj msg)

      (contains? out-msg :trader)
      (update :trader conj (:trader out-msg))

      (contains? out-msg :broker/working-orders)
      (update :broker/working-orders conj (:broker/working-orders out-msg))

      (contains? out-msg :broker/open-positions)
      (update :broker/open-positions conj (:broker/open-positions out-msg))

      (contains? out-msg :trade)
      (update :trade conj (:trade out-msg))

      (contains? out-msg :order-closed)
      (update :order-closed conj (:order-closed out-msg))

      (contains? out-msg :position-closed)
      (update :position-closed conj (:position-closed out-msg))

      (contains? out-msg :working-order)
      (assoc :working-order (:working-order out-msg))

      (contains? out-msg :open-position)
      (assoc :open-position (:open-position out-msg)))))

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
                               (m/reduce acc-out-msg {:schema-error [] :trader []
                                                      :broker/working-orders []
                                                      :broker/open-positions []
                                                      :trade [] :order-closed [] :position-closed []
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
