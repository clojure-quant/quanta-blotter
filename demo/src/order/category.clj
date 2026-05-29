(ns demo.order.category
  (:require
   [missionary.core :as m]
   [quanta.market.protocol :as p]
   [demo.env :refer [tm pm]]
   [quanta.market.portfolio :refer [get-working-orders]]))

(def assets
  [; spot
   "BTCUSDT.S" "BTCUSDC.S"
   ; linear
   "BTC-27JUN25.L"
   "BTC-26JUL24.L"
   "BTC-02AUG24.L"
   "BTC-09AUG24.L"
   "BTC-30AUG24.L"
   "BTC-27SEP24.L"
   "BTC-27DEC24.L"
   "BTC-28MAR25.L"
   "BTCPERP.L"
   "BTCUSDT.L"
   ; inverse
   "BTCUSD.I"
   "BTCUSDU24.I"
   "BTCUSDZ24.I"])

(def order-spot-limit
  {:account :rene/test4
   :asset "BTCUSDT.S"
   :side :buy
   :qty 0.001
   :ordertype :limit
   :limit 66800.0})

(def order-spot-market-buy
  {:account :rene/test4
   :asset "BTCUSDT.S"
   :side :buy
   :qty 0.002
   :ordertype :market})

(def order-spot-market-sell
  {:account :rene/test4
   :asset "BTCUSDT.S"
   :side :sell
   :qty 0.002
   :ordertype :market})

(m/? (p/order-create! tm order-spot-limit))
(m/? (p/order-create! tm order-spot-market-buy))
(m/? (p/order-create! tm order-spot-market-sell))

(m/? (p/order-create! pm order-spot-limit))
(m/? (p/order-create! pm order-spot-market-buy))

(m/? (p/order-create! pm order-spot-market-sell))

(m/?
 (p/order-create!
  pm
  {:account :rene/test4
   :asset "BTCUSDT.S"
   :side :sell
   :qty 0.001
   :ordertype :limit
   :limit 66330.0}))

(def order-linear
  (assoc order-spot-market-buy :asset "BTC-27SEP24.L"))

(def order-linear-sell-limit
  (assoc order-spot-limit
         :asset "BTC-27SEP24.L"
         :side :sell
         :limit 69475.0))

(m/? (p/order-create! pm order-linear-sell-limit))
(m/? (p/order-create! pm order-linear))

(def cancel
  {:account :rene/test4
   :asset "BTCUSDT"
   :order-id "mBDN7iIP"})

(m/? (p/order-cancel! pm cancel))

(get-working-orders pm)

(require '[clojure.pprint :refer [print-table]])

#_(->> (get-working-orders pm)
       (map :order)
       (map #(select-keys % [:date-created :order-id]))
       (sort-by :date-created)
       print-table)

(comment

  (format "%f" 3.4)
  (str (:qty order-spot))
  (format "%f" (:qty order-spot))
  (format "%f" (:limit  order-spot))

  (def order-inverse
    (assoc order-spot :asset "BTCUSD.I"))

; 
  )







