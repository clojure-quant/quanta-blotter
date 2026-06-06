(ns demo.oms
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.core :refer [create-order-manager start-order-manager! stop-order-manager! create-order]]
   [quanta.blotter.account-manager :refer [add-edn-accounts]]
   ; demo order flow
   [quanta.blotter.util :refer [push-flow-to-rdv]]
   [demo.util.orderflow-simulated :refer [demo-order-action-flow]]
   [quanta.blotter.oms.flow.print :refer [start-open-positions-working-order-logger!]]
   ; persistence
   [quanta.blotter.oms.db :as db]
   [quanta.blotter.oms.db-transactor :as db-transactor]
   ; side effects
   [quanta.blotter.paper.broker] ; side effect: brings in paper broker implementation
   )
  (:import [missionary Cancelled]))

(def oms  (create-order-manager {:log-file "log/oms-trace.txt"
                                 :transaction-log-file "log/oms-transaction.txt"}))

oms

(add-edn-accounts (:account-manager oms) "demo-accounts.edn")



(def dispose-wo-op-logger (start-open-positions-working-order-logger! oms "log/oms-wo-op.txt"))


;; persistence: open the datahike trade-db and stream all OMS flows into it.
(def trade-db (db/trade-db-start "trade-db"))


(def db-transactor (db-transactor/start-db-transactor oms trade-db))

(def dispose-orderflow-simulated
  (push-flow-to-rdv (:order-rdv oms) demo-order-action-flow))

(start-order-manager! oms)


(m/?
 (create-order oms {:account/id 3
                    :order-type :limit
                    :asset "USDJPY"
                    :side :buy
                    :limit 110.30M 
                    :qty 10000.0M}))

(m/?
 (create-order oms {:account/id 3
                    :order-type :limit
                    :asset "USDJPY"
                    :side :sell
                    :limit 120.51M 
                    :qty 5000.0M}))


(m/?
 (create-order oms {:account/id 4
                    :order-type :limit
                    :asset "EURUSD"
                    :side :buy
                    :limit 1.2051M
                    :qty 6000.0M}))

(comment
  ;; inspect what has been persisted to datahike
  (db/query-messages trade-db)
  (db/query-orders trade-db)
  (db/query-fills trade-db)
  (db/query-positions trade-db)

  ;; stop persistence + close the db
  (db-transactor/stop-db-transactor db-transactor)
  (db/trade-db-stop trade-db)
  ;
  )