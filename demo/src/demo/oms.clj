(ns demo.oms
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.core :refer [create-order-manager start-order-manager! stop-order-manager! create-limit-order]]
   [quanta.blotter.account-manager :refer [add-edn-accounts]]
   ; demo order flow
   [quanta.blotter.util :refer [push-flow-to-rdv]]
   [demo.util.orderflow-simulated :refer [demo-order-action-flow]]
   [quanta.blotter.oms.print :refer [start-open-positions-working-order-logger!]]
   ; side effects
   [quanta.blotter.paper.broker] ; side effect: brings in paper broker implementation
   )
  (:import [missionary Cancelled]))

(def oms  (create-order-manager {:log-file "log/oms-trace.txt"
                                 :transaction-log-file "log/oms-transaction.txt"}))

oms

(add-edn-accounts (:account-manager oms) "demo-accounts.edn")


(def dispose-wo-op-logger (start-open-positions-working-order-logger! oms "log/oms-wo-op.txt"))

(def dispose-orderflow-simulated
  (push-flow-to-rdv (:order-rdv oms) demo-order-action-flow))

(start-order-manager! oms)


(m/?
 (create-limit-order oms {:account/id 2
                          :asset "EURUSD"
                          :side :buy
                          :limit 1.1030M 
                          :qty 10000.0M}))

(m/?
 (create-limit-order oms {:account/id 2
                          :asset "EURUSD"
                          :side :sell
                          :limit 1.2051M 
                          :qty 6000.0M}))


(m/?
 (create-limit-order oms {:account/id 2
                          :asset "EURUSD"
                          :side :altered-mind
                          :limit 1.2051M
                          :qty 6000.0M}))