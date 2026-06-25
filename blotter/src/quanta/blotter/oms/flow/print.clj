(ns quanta.blotter.oms.flow.print
  (:require
   [missionary.core :as m]
   [tick.core :as t]
   [quanta.missionary :refer [mix-tagged mix]]
   [quanta.missionary.logger :as logger]
   [quanta.blotter.oms.validation.flow :as vf]
   [quanta.blotter.oms.flow.open-positions :as op]
   [quanta.blotter.oms.flow.working-orders :as wo]
   [quanta.blotter.oms.print :as print]))

(defn- print-state [{:keys [trade order position working-order open-position] :as _state}]
  (let [s (str "\r\n trading-state as of " (t/instant) "\r\n")

        s (if (empty? trade)
            s
            (str s "\r\ntrades:\r\n" (print/trades-table trade)))

        s (if (empty? order)
            s
            (str s "\r\nfinished orders:\r\n" (print/working-orders-table order {:max-width 300})))

        s (if (empty? position)
            s
            (str s "\r\nfinished positions:\r\n" (print/open-positions-table position {:max-width 300})))

        s (if (some? working-order)
            (str s "\r\nworking-order:\r\n" (print/working-orders-table (vals working-order) {:max-width 300}))
            s)

        s (if (some? open-position)
            (str s "\r\nopen-position:\r\n" (print/open-positions-table (vals open-position) {:max-width 300}))
            s)]
    s))

(defn- acc-state [state [k v]]
  (case k
    :trade (update state :trade conj v)
    :order (update state :order conj v)
    :position (update state :position conj v)
    :working-order (assoc state :working-order v)
    :open-position (assoc state :open-position v)))

(defn trading-state-print-flow [{:keys [order-change-flow fill-flow position-change-flow
                                        working-order-dict-flow open-position-dict-flow]
                                 :as trading-state} interval-ms]
  (assert (map? trading-state) "trading-state-print-flow trading-state needs to be a map")
  (let [mixed-f (mix-tagged {:trade fill-flow
                             ;:order order-change-flow
                             :order (wo/closed-order-list-flow order-change-flow)
                             ;:position position-change-flow
                             :position (op/closed-position-list-flow position-change-flow)
                             :working-order working-order-dict-flow
                             :open-position open-position-dict-flow})
        batched-combined-f (m/ap
                            (let [[_ batch] (m/?> (m/group-by {} mixed-f))]
                              (m/? (->> (m/ap (m/amb= (m/?> batch)
                                                      (m/? (m/sleep interval-ms))))
                                        (m/eduction (take-while some?))
                                        (m/reduce acc-state {:trade [] :order [] :position []
                                                             :working-order nil :open-position nil})))))]
    (m/ap
     (print-state (m/?> batched-combined-f)))))

; (vf/bad-message-with-explaination combined-flow)

(defn start-trading-state-logger! [trading-state log-file interval-ms console?]
  (assert trading-state "start-trading-state-logger! needs :trading-state")
  (println "trading-state keys:" (keys trading-state))
  (let [l (logger/create-logger log-file console?)
        log-f (trading-state-print-flow trading-state interval-ms)
        dispose! (logger/start-log-flow-to-logger l log-f)]
    dispose!))