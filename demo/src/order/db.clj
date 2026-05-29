(ns demo.order.db
  (:require
   [missionary.core :as m]
   [quanta.market.trade.msg-logger :refer [create-logger!]]
   [quanta.market.trade :refer [query-messages print-messages]]
   [clojure.pprint :refer [print-table]]
   [jsonista.core :as j] ; json read/write
   [clojure.edn :refer [read-string]]
   [demo.tm :refer [db tm]]))

; test storing messages from a flow

(def conn {:msg-flow (m/seed [1 2 3])})

(def dispose!
  (create-logger! db :florian/test1 :in conn))

dispose!

(dispose!)

(defn print-history [messages]
  (print-table [:message/timestamp
                :message/direction
                :message/data] messages))

(print-messages tm {:account :florian/test1})

(print-messages tm {:account :rene/test4})

(print-messages tm {:account :rene/test4-orderupdate})

(print-messages tm {:account :bybit})

(->> (query-messages tm {:account :rene/test4})
     (take-last 20))

(defn json->msg [json]
  (j/read-value json j/keyword-keys-object-mapper))

(defn patch-data [msg]
  (update msg :message/data read-string))

(defn ping? [msg]
  (let [data (:message/data msg)
        op (or (:op data) (get data "op"))]
    (or (= op "ping")
        (= op "pong"))))

(->> (query-messages tm {:account :rene/test4-orderupdate})
    ;(take-last 500)
     (map patch-data)
     (remove ping?)
     ;last
     pr-str
     (spit "orderupdate-bybit.edn"))

; {:creationTime 1721421749151, 
;  :topic \"order\", 
;  :id \"102782796_20000_1449259437\", 
;  :data [{:cumExecQty \"0.000000\", 
;          :category \"spot\", 
;          :triggerBy \"\", 
;          :rejectReason \"EC_NoError\",
;          :tpTriggerBy \"\", 
;          :tpLimitPrice \"0.00\", 
;          :smpGroup 0,
;          :triggerDirection 0, 
;          :closeOnTrigger false, 
;          :stopOrderType \"\", 
;          :symbol \"BTCUSDT\", 
;          :orderType \"Limit\", 
;          :marketUnit \"\", 
;          :smpType \"None\", 
;          :reduceOnly false, 
;          :placeType \"\", 
;          :isLeverage \"0\", 
;          :cumExecFee \"0\", 
;          :lastPriceOnCreated \"65619.60\", 
;          :orderStatus \"New\", 
;          :cumExecValue \"0.00000000\", 
;          :positionIdx 0, 
;          :avgPrice \"\", 
;          :smpOrderId \"\", 
;          :leavesQty \"0.000100\", 
;          :blockTradeId \"\", 
;          :feeCurrency \"\", 
;          :leavesValue \"0.10000000\", 
;          :slTriggerBy \"\", 
;          :createdTime \"1721421749146\", 
;          :orderIv \"\", 
;          :updatedTime \"1721421749149\", 
;          :slLimitPrice \"0.00\", 
;          :side \"Buy\", 
;          :qty \"0.000100\", 
;          :cancelType \"UNKNOWN\", 
;          :stopLoss \"0.00\", 
;          :takeProfit \"0.00\", 
;          :timeInForce \"PostOnly\", 
;          :price \"1000.00\", 
;          :orderLinkId \"1733805728772483073\", 
;          :triggerPrice \"0.00\", 
;          :orderId \"1733805728772483072\"}]}"}

