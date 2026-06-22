(ns quanta.blotter.paper.orderfiller
  (:require
   [missionary.core :as m]
   [nano-id.core :refer [nano-id]]
   [tick.core :as t])
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

(def ^:private market-price-min 50.0M)
(def ^:private market-price-max 100.0M)

(defn random-market-price
  "Returns a random BigDecimal fill price in [50.0M, 100.0M]."
  []
  (let [span (- market-price-max market-price-min)]
    (+ market-price-min (* span (bigdec (rand))))))

(defn- fill-price [{:keys [order-type limit]}]
  (case order-type
    :market (random-market-price)
    :limit limit))

(defn ->fill
  "Builds a :broker/order-filled message for the given slice quantity."
  [{:keys [order-id side asset order-type] :as order} slice-qty]
  {:type :broker/order-filled
   :account/id (:account/id order)
   :order-id order-id
   :fill-id (nano-id 6)
   :date (t/instant)
   :asset asset
   :qty slice-qty
   :side side
   :price (fill-price order)})

(defn fill?
  "Probabilistic decision whether a fill happens this cycle.
   A fill-probability of 100 guarantees a fill."
  [fill-probability]
  (< (rand-int 100) fill-probability))

(defn random-fill-flow
  "Returns a flow of fills.
   Each cycle first waits `wait-seconds`, then probabilistically (fill-probability)
   emits the next fill slice (sized via fill-qty-prct). When all slices have been
   emitted the flow stops. If cancelled while waiting, a :broker/order-canceled is
   emitted instead."
  [{:keys [fill-probability
           wait-seconds
           fill-qty-prct]}
   log-fn
   {:keys [order-id qty] :as order}]
  (let [log (fn [& data]
              (log-fn (str "random-fill order-id [" order-id "] :" (vec data))))
        slices (fill-slices fill-qty-prct qty)]
    (m/ap (log "order created. fill slices: " slices)
          (loop [remaining slices]
            (if (empty? remaining)
              (m/amb)
              (let [cancelled? (try
                                 (log "waiting " wait-seconds " seconds for next fill")
                                 (m/? (m/sleep (* 1000 wait-seconds) false))
                                 (catch Cancelled _ true))]
                (if cancelled?
                  (do (log "cancelled")
                      (m/amb {:type :broker/order-canceled
                              :order-id order-id
                              :date (t/instant)}))
                  (if (fill? fill-probability)
                    (let [fill (->fill order (first remaining))]
                      (log "filled: " fill)
                      (m/amb fill (recur (rest remaining))))
                    (m/amb (recur remaining))))))))))

(comment
  (def order {:order-id 2
              :account/id 3
              :asset "BTCUSDT"
              :side :buy
              :limit 100.0
              :qty 0.001})

  (defn log-fn [s] (println "log orderfiller: " s))

  (fill-slices [50 25 25] 0.001)
  ;; => (5.0E-4 2.5E-4 2.5E-4)

  (def fill-flow (random-fill-flow {:reject-probability 0
                                    :fill-probability 100
                                    :fill-qty-prct [50 25 25]
                                    :wait-seconds 1}
                                   log-fn
                                   order))

  (defn log-progress [r order-update]
    (println "order-update: " order-update)
    (conj r order-update))

  (def print-progress-task
    (m/reduce log-progress [] fill-flow))

  (def dispose!
    (print-progress-task
     #(println "order history: " %)
     #(prn ::crash %)))

  (dispose!)

; 
  )
