(ns demo.oms
  (:require
   [missionary.core :as m]
   [quanta.blotter.core :refer [create-order-manager start-order-manager! stop-order-manager!]]
   [quanta.blotter.account-manager :refer [add-edn-accounts]]
   [quanta.blotter.util :refer [push-flow-to-rdv]]
   [demo.util.orderflow-simulated :refer [demo-order-action-flow]]
   [quanta.blotter.paper.broker] ; side effect: brings in paper broker implementation
   )
  (:import [missionary Cancelled]))

(def oms  (create-order-manager {:log-file "log/oms.txt"
                                 :transaction-log-file "log/oms-transaction.txt"}))

oms

(add-edn-accounts (:account-manager oms) "demo-accounts.edn")



(def dispose-orderflow-simulated
  (push-flow-to-rdv (:order-rdv oms) demo-order-action-flow))

(start-order-manager! oms)

