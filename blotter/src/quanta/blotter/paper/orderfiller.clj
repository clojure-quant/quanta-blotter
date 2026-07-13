(ns quanta.blotter.paper.orderfiller
  (:require
   [missionary.core :as m]
   [nano-id.core :refer [nano-id]]
   [tick.core :as t]
   [quanta.quote.core :refer [asset-quote-flow]])
  (:import [missionary Cancelled]))

(defn fill-slices
  "Splits `qty` into a sequence of fill quantities according to `fill-qty-prct`,
   a vector of percentages of the original order quantity (e.g. [50 25 25]).
   The last slice gets the remainder so that all slices sum exactly to `qty`."
  [fill-qty-prct qty]
  (let [prcts (or (seq fill-qty-prct) [100])]
    (loop [[p & more] prcts
           filled 0
           slices []]
      (if (nil? p)
        slices
        (let [slice (if (seq more)
                      (/ (* qty p) 100)
                      (- qty filled))]
          (recur more (+ filled slice) (conj slices slice)))))))

(defn quote-price
  "Current price used for fill decisions and fill price — always :bid."
  [quote]
  (:bid quote))

(defn wait-elapsed?
  "True when this is the first fill (last-fill-date nil), or when quote-ts is
   at least wait-seconds after last-fill-date."
  [last-fill-date quote-ts wait-seconds]
  (or (nil? last-fill-date)
      (let [elapsed (t/seconds (t/between last-fill-date quote-ts))]
        (>= elapsed wait-seconds))))

(defn stop-triggered?
  "Buy stop triggers when bid is above limit; sell stop when bid is below limit."
  [side limit bid]
  (case side
    :buy (> bid limit)
    :sell (< bid limit)))

(defn order-executable?
  "Whether the order can fill at the given bid for the (possibly converted) order-type."
  [order-type side limit bid]
  (case order-type
    :market true
    :limit (case side
             :buy (>= limit bid)
             :sell (<= limit bid))
    :stop false))

(defn ->fill
  "Builds a :broker/order-filled message for the given slice quantity at `price`."
  [{:keys [order-id side asset] :as order} slice-qty price date]
  {:type :broker/order-filled
   :account/id (:account/id order)
   :order-id order-id
   :fill-id (nano-id 6)
   :date (or date (t/instant))
   :asset asset
   :qty slice-qty
   :side side
   :price price})

(defn fill?
  "Probabilistic decision whether a fill happens this cycle.
   A fill-probability of 100 guarantees a fill."
  [fill-probability]
  (< (rand-int 100) fill-probability))

(defn simulated-fill-flow
  "Returns a flow of fills driven by asset quotes from the quote-manager in `ctx`.

   On each quote:
   - waits until quote :ts is nil-last-fill or >= wait-seconds after last fill
   - stop orders convert to market when triggered (buy: bid > limit; sell: bid < limit)
     and may fill on the same quote
   - market fills at :bid; limit fills when limit is on the market side of :bid
   - fill-probability is an extra gate after price conditions pass
   - only the next slice is filled per successful cycle

   State is kept in atoms (m/?> must not be mixed with loop/recur).
   Quote consumption stops via (m/?> (m/eduction (take-while remaining?) quote-f))
   once remaining-slices is empty.

   If cancelled while slices remain, emits :broker/order-canceled."
  [ctx
   {:keys [fill-probability
           wait-seconds
           fill-qty-prct]}
   log-fn
   {:keys [order-id asset qty side limit order-type] :as order}]
  (let [log (fn [& data]
              (log-fn {:order/fill order-id :data (vec data)}))
        remaining-slices (atom (fill-slices fill-qty-prct qty))
        last-fill-date (atom nil)
        current-type (atom order-type)
        quote-f (asset-quote-flow (:quote-manager ctx) asset)]
    (log {:message (str "order created. fill slices: " @remaining-slices)})
    (m/eduction
     (remove nil?)
     (m/ap
      (try
        (let [quote (m/?> (m/eduction
                           (take-while (fn [_] (seq @remaining-slices)))
                           quote-f))
              bid (quote-price quote)
              quote-ts (or (:ts quote) (t/instant))
              order-type* (if (and (= :stop @current-type)
                                   (stop-triggered? side limit bid))
                            (do (log {:message "stop triggered → market"
                                      :bid bid :limit limit})
                                (reset! current-type :market)
                                :market)
                            @current-type)]
          (when (and (wait-elapsed? @last-fill-date quote-ts wait-seconds)
                     (order-executable? order-type* side limit bid)
                     (fill? fill-probability))
            (let [slice (first @remaining-slices)
                  fill (->fill order slice bid quote-ts)]
              (swap! remaining-slices rest)
              (reset! last-fill-date quote-ts)
              (log "filled: " fill)
              fill)))
        (catch Cancelled _
          (log "cancelled")
          (when (seq @remaining-slices)
            {:type :broker/order-canceled
             :order-id order-id
             :date (t/instant)})))))))
