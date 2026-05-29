(ns quanta.blotter.schema
  (:require
   [tick.core :as t]
   [malli.core :as m]
   [malli.registry :as mr]
   [malli.error :as me]
   [malli.experimental.time :as time]))

(def r
  (mr/composite-registry
   m/default-registry
   (mr/registry (time/schemas))))

(def above-zero 0.0000000000000001)

(def Order
  [:map
   [:account :keyword]
   [:asset :string]
   [:side [:enum :buy :sell]]
   [:qty [:double {:min above-zero}]]
   [:ordertype [:enum :market :limit]]
   [:limit {:optional true} [:double {:min above-zero}]]])

(def OrderUpdate
  [:map
   [:order-id :string]
   ;[:account :keyword]
   ;[:asset :string]
   [:orderupdatetype [:enum :new-order
                      :rejected
                      :canceled
                      :expired
                      :trade
                      :comment]]

   [:trade-qty {:optional true} [:double {:min above-zero}]]
   [:trade-value {:optional true} [:double {:min above-zero}]]

   ;[:qty [:double {:min above-zero}]]
   ])

(def BrokerOrderStatus
  [:map
   [:order-id :string]
   [:broker-order-status [:map
                          [:status [:enum :open :closed]]
                          [:fill-qty {:optional true} [:double]]
                          [:fill-value {:optional true} [:double]]]]])

(def Trade
  [:map
   [:account :keyword]
   [:asset :string]
   [:side [:enum :buy :sell]]
   [:qty [:double {:min above-zero}]]])

(def Position
  [:map
   [:account :keyword]
   [:asset :string]
   [:qty [:double]]])

(defn validate-order [order]
  (m/validate Order order {:registry r}))

(defn human-error-order [order]
  (->> (m/explain Order order {:registry r})
       (me/humanize)))

(defn validate-broker-order-status [order-status]
  (m/validate BrokerOrderStatus order-status {:registry r}))

(defn human-error-broker-order-status [order-status]
  (->> (m/explain BrokerOrderStatus order-status {:registry r})
       (me/humanize)))

(comment

  (def order {:account :test1
              ;:asset "QQQ" 
              ;:side :long
              :qty 0.001
              :type :limit
              :limit 1000.0})

  (validate-order order)
  (human-error-order order)

  (def order-status {:order-id "34"
                     :broker-order-status {:status :closed
                                           :fill-qty 3.0
                                           :fill-value 50.0}})

  (validate-broker-order-status order-status)
  (human-error-broker-order-status order-status)

  (require '[malli.generator :as mg])
  (mg/generate Order {:registry r})
  ;; => {:account :HE,
  ;;     :asset "3aCd5MlsWn2VJZkEGFnmLO95",
  ;;     :side :long,
  ;;     :qty 0.43434885144233704,
  ;;     :type :limit,
  ;;     :limit 6.073498904705048}

  ;; => {:account :c6+-,
  ;;     :asset "LG6St8xK2vnHZ",
  ;;     :side :long,
  ;;     :qty 100.71820831298828,
  ;;     :type :market,
  ;;     :limit 0.01611560583114624}

;  
  )
