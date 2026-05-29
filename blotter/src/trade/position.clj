(ns quanta.market.trade.position
  (:require
   [missionary.core :as m]))

(defn trade-net-qty [{:keys [qty side]}]
  (case side
    :buy qty
    :sell (- 0.0 qty)))

(defn open-position
  "consumes trade-flow (at this point it should be filtered, at least
   by asset, possibly asset+account)
   returns open-position flow. 
   returns the net-qty of an open position.
   when net-qty is 0.0 this flow terminates with net-qty 0.0
   which indicates there is no longer an open position."
  [trade-flow]
  (m/reductions (fn [position trade]
                  (let [current-pos (if (double? position)
                                      position
                                      0.0)
                        new-position (+ current-pos (trade-net-qty trade))]
                    (if (= 0.0 new-position)
                      (reduced :close)
                      new-position)))
                :open
                trade-flow))

(defn position-change-flow [trade-flow]
  (m/ap
   (let [[k >x] (m/?> ##Inf (m/group-by (juxt :account :asset) trade-flow))
         position (m/?> 1 (open-position >x))]
     [k position])))

(defn open-positions-flow [position-change-flow]
  (m/ap
   (let [positions (atom {})
         [k net-qty] (m/?> 1 position-change-flow)]
     (case net-qty
       :open (m/amb)
       :close (swap! positions dissoc k)
       (swap! positions assoc k net-qty)))))


