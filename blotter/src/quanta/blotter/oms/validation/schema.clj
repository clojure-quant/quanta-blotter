(ns quanta.blotter.oms.validation.schema
  (:require
   [malli.core :as m]
   [malli.registry :as mr]
   [malli.error :as me]
   [malli.experimental.time :as time]))

(def r
  (mr/composite-registry
   m/default-registry
   (mr/registry (time/schemas))))

(def above-zero 0.0000000000000001)

(def AccountId :int)

(def OrderId [:or :int :string])

(def FillId [:or :string :int])

(def Side [:enum :buy :sell])

(def OrderType [:enum :limit :market])

(defn limit-market-exclusive?
  "Limit orders require :limit; market orders must not include :limit."
  [{:keys [order-type limit]}]
  (case order-type
    :limit (some? limit)
    :market (nil? limit)
    false))

(def Decimal [:fn decimal?])

(def PositiveDecimal
  [:and Decimal [:fn {:error/message "must be greater than zero"}
                 #(pos? (double %))]])

(def Instant :time/instant)

(def TraderNewOrder
  [:and
   [:map
    [:type [:= :trader/new-order]]
    [:account/id AccountId]
    [:order-id OrderId]
    [:asset :string]
    [:side Side]
    [:qty PositiveDecimal]
    [:order-type OrderType]
    [:limit {:optional true} PositiveDecimal]
    [:campaign {:optional true} :string]
    [:label {:optional true} :keyword]]
   [:fn {:error/message "limit orders require :limit; market orders must not include :limit"}
    limit-market-exclusive?]])

(def TraderCancelOrder
  [:map
   [:type [:= :trader/cancel-order]]
   [:account/id AccountId]
   [:order-id OrderId]])

(def BrokerOrderFilled
  [:map
   [:type [:= :broker/order-filled]]
   [:account/id AccountId]
   [:order-id OrderId]
   [:fill-id FillId]
   [:date Instant]
   [:asset :string]
   [:qty PositiveDecimal]
   [:side Side]
   [:price PositiveDecimal]])

(def BrokerOrderConfirmed
  [:and
   [:map
    [:type [:= :broker/order-confirmed]]
    [:account/id AccountId]
    [:order-id OrderId]
    [:asset :string]
    [:side Side]
    [:qty PositiveDecimal]
    [:order-type OrderType]
    [:limit {:optional true} PositiveDecimal]
    [:campaign {:optional true} :string]
    [:label {:optional true} :keyword]
    [:date Instant]
    [:message {:optional true} :string]]
   [:fn {:error/message "limit orders require :limit; market orders must not include :limit"}
    limit-market-exclusive?]])

(def BrokerOrderRejected
  [:map
   [:type [:= :broker/order-rejected]]
   [:account/id AccountId]
   [:order-id OrderId]
   [:date {:optional true} Instant]
   [:message {:optional true} :string]])

(def BrokerCancelConfirmed
  [:map
   [:type [:= :broker/cancel-confirmed]]
   [:account/id AccountId]
   [:order-id OrderId]
   [:message {:optional true} :string]])

(def BrokerCancelRejected
  [:map
   [:type [:= :broker/cancel-rejected]]
   [:account/id AccountId]
   [:order-id OrderId]
   [:message {:optional true} :string]])

(def BrokerOrderCanceled
  [:map
   [:type [:= :broker/order-canceled]]
   [:order-id OrderId]
   [:date Instant]
   [:account/id {:optional true} AccountId]])

(def Message
  [:multi {:dispatch :type}
   [:trader/new-order TraderNewOrder]
   [:trader/cancel-order TraderCancelOrder]
   [:broker/order-filled BrokerOrderFilled]
   [:broker/order-confirmed BrokerOrderConfirmed]
   [:broker/order-rejected BrokerOrderRejected]
   [:broker/cancel-confirmed BrokerCancelConfirmed]
   [:broker/cancel-rejected BrokerCancelRejected]
   [:broker/order-canceled BrokerOrderCanceled]])

(defn validate-message [message]
  (m/validate Message message {:registry r}))

(defn explain-message [message]
  (m/explain Message message {:registry r}))

(defn human-error-message [message]
  (->> (explain-message message)
       (me/humanize)))

(comment

  (require  '[ednx.edn :refer [slurp-edn read-edn]]
            '[ednx.tick.edn :refer [add-tick-edn-handlers!]])

  (add-tick-edn-handlers!)

  (def messages
    (slurp-edn "data/channel-paper.edn"))

  (doseq [msg messages]
    (println (:type msg) (validate-message msg) (human-error-message msg)))

  ;
  )
