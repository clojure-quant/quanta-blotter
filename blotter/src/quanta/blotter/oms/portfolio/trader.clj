(ns quanta.blotter.oms.portfolio.trader)

(defn trader?
  "True for trader request messages."
  [msg]
  (let [t (:type msg)]
    (or (= t :trader/new-order)
        (= t :trader/cancel-order)
        (= t :trader/modify-order))))
