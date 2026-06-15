
(ns quanta.quote.random
  (:require
   [missionary.core :as m]
   [quanta.quote.protocol :as p]
   [quanta.quote.interactor :refer [subscription-watcher]]))

(defn zero-mean-random-value []
  (* (- (rand 0.005) 0.0025) 10.0))

(defn random-return-value []
  ;; mimicing a normal distribution
  (apply + (repeatedly 10 zero-mean-random-value)))

(defn random-return-multiplyer []
  (+ 1.0 (random-return-value)))

(defn next-price [p]
  (* p (random-return-multiplyer)))

(defn price-seq [start-price]
  (iterate next-price start-price))

(defn update-prices [d]
  (->> d
       (map (fn [[k v]]
              [k (next-price v)]))
       (into {})))

(comment
  (zero-mean-random-value)
  (random-return-multiplyer)
  (take 10 (price-seq 100.0))
  (->> {:a 100.0 :b 104.0}
       (update-prices)
       (update-prices)
       (update-prices)
       (update-prices))

;  
  )

;; update prices and send quotes

(defn message-loop [account price-a log send-quote]
  (let [symbol->quote (fn [[asset price]]
                        {:account (:account/id account)
                         :asset asset
                         :bid price
                         :ask (+ price 0.01)})]
    (m/sp
     (loop []
       (swap! price-a update-prices)
       (doseq [[asset price] @price-a]
         (send-quote (symbol->quote [asset price])))
       (m/? (m/sleep 250))
       (recur)))))

;; process subscription changes

(defn repeat-nr [n]
  (vec (repeatedly n (constantly 100.0))))

(defrecord random-msg-processor [account log price-a]
  p/quote-messaging
  (subscribe-msg [_ sub]
    (let [inital-prices (repeat-nr (count sub))
          new-subs (zipmap sub inital-prices)]
      (swap! price-a merge new-subs)
      (log {:account (:account/id account) :type :subscribe :assets (keys @price-a) :added sub})))
  (unsubscribe-msg [_ unsub]
    (swap! price-a (fn [d ks] (apply dissoc d ks)) unsub)
    (log {:account (:account/id account) :type :unsubscribe :assets (keys @price-a) :removed unsub}))
  (read-quote [_ fix-vec-in]
    nil))


;; create quote account

(defn dump-rdv [rdv]
  (m/sp
   (loop []
     (m/? rdv)
     (recur))))

(defmethod p/create-quote-account :random
  [account subscription-a send-quote log]
  (let [push (m/rdv)
        price-a (atom {})
        rmp (random-msg-processor. account log price-a)]
    (m/sp
     (log {:type :random-quote-start :account (:account/id account)})
     (m/? (m/join vector
                  (subscription-watcher account rmp subscription-a push log)
                  (message-loop account price-a log send-quote)
                  (dump-rdv push)
                  )))))

