(ns demo.account-status
  (:require
   [taoensso.timbre :refer [info]]
   [missionary.core :as m]
   [quanta.blotter.oms.server :refer [make-account-status-request]]
   [quanta.blotter.oms.print :as print]))

(defn run
  "Request open positions and working orders for `:account-id`, print results, exit."
  [{:keys [account-id running-system]
    :or {account-id 1000}}]
  (info "waiting 7s for brokers to connect...")
  (m/? (m/sleep 7000))
  (let [oms-server (:oms-server running-system)
        {:keys [orders positions]} (m/? (make-account-status-request oms-server account-id))]
    (if (nil? positions)
      (info "positions: timeout")
      (info "positions:\n" (print/open-positions-table positions)))
    (if (nil? orders)
      (info "orders: timeout")
      (info "orders:\n" (print/working-orders-table orders)))
    (m/? (m/sleep 20000))))
