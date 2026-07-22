(ns quanta.market-sim.quote-random
  (:require
   [missionary.core :as m]
   [quanta.quote.protocol :as p]
   [quanta.quote.interactor :refer [subscription-watcher]]))

(def default-settings
  {:initial-price 100.0
   :random-change-prct 0.2
   :trend-change-prct 0.2
   :trend-clamp-prct 0.4
   :quote-tick-interval-ms 100})

(defn random-return-value
  "Uniform random return in [-change-prct/2, +change-prct/2], converted from percent to fraction."
  [change-prct]
  (/ (- (rand change-prct) (/ change-prct 2.0)) 100.0))

(defn- round-2 [n]
  (/ (Math/round (* n 100.0)) 100.0))

(defn clamp [x lo hi]
  (min hi (max lo x)))

(defn next-state [{:keys [trend-change-prct trend-clamp-prct random-change-prct]} {:keys [price trend]}]
  (let [trend-clamp (/ trend-clamp-prct 100.0)
        trend (-> (+ trend (random-return-value trend-change-prct))
                  (clamp (- trend-clamp) trend-clamp))
        price (round-2 (* price (+ 1.0 trend (random-return-value random-change-prct))))]
    {:price price :trend trend}))

(defn state-seq [settings start-state]
  (iterate #(next-state settings %) start-state))

(defn update-states [settings d]
  (->> d
       (map (fn [[k v]]
              [k (next-state settings v)]))
       (into {})))

(comment
  (random-return-value 0.2)
  (take 10 (state-seq default-settings {:price 100.0 :trend 0.0}))
  (->> {"A" {:price 100.0 :trend 0.0}
        "B" {:price 104.0 :trend 0.0}}
       (update-states default-settings)
       (update-states default-settings)
       (update-states default-settings)
       (update-states default-settings))

;
  )

;; update states and send quotes

(defn message-loop [account state-a subscription-a settings log send-quote]
  (let [{:keys [quote-tick-interval-ms]} settings
        symbol->quote (fn [[asset price]]
                        (let [bid (bigdec (round-2 price))
                              ask (bigdec (round-2 (+ bid 0.01)))]
                          {:account (:account/id account)
                           :asset asset
                           :bid bid
                           :ask ask}))]
    (m/sp
     (loop []
       (let [assets @subscription-a]
         ;(log {:account (:account/id account) :type :generate-quote :assets assets})
         (swap! state-a (fn [d] (merge d (update-states settings (select-keys d assets)))))
         (doseq [asset assets]
           (when-let [price (:price (get @state-a asset))]
             (send-quote (symbol->quote [asset price])))))
       (m/? (m/sleep quote-tick-interval-ms))
       (recur)))))

;; process subscription changes

(defn initial-state [initial-price]
  {:price initial-price :trend 0.0})

(defn repeat-nr [initial-price n]
  (vec (repeatedly n #(initial-state initial-price))))

(defn add-new-assets [d sub initial-price]
  (let [new-assets (filter #(not (contains? d %)) sub)]
    (merge d (zipmap new-assets (repeat-nr initial-price (count new-assets))))))

(comment 
  
  (add-new-assets {"A" {:price 100.0 :trend 0.0}
                   "B" {:price 104.0 :trend 0.0}}
                  #{"C" "D"} 100.0)
  #_{"A" {:price 100.0 :trend 0.0}
     "B" {:price 104.0 :trend 0.0}
     "C" {:price 100.0 :trend 0.0}
     "D" {:price 100.0 :trend 0.0}}
  ;
  )


(defrecord random-msg-processor [account log state-a initial-price]
  p/quote-messaging
  (subscribe-msg [_ sub]
    (swap! state-a add-new-assets sub initial-price)
    (log {:account (:account/id account) :type :subscribe :assets sub})
    nil)
  (unsubscribe-msg [_ unsub]
    ;; keep last simulated state in state-a for later re-subscribe
    (log {:account (:account/id account) :type :unsubscribe :assets unsub})
    nil)
  (read-quote [_ _conn-msg-in]
    nil))

;; create quote account

(defn drain-rdv [rdv]
  ; sub watcher will push data to be sent to the remote connection
  ; to not block the subscription watcher, we need to drain the rdv
  (m/sp
   (loop []
     (m/? rdv)
     (recur))))

(defmethod p/create-quote-account :random
  [account subscription-a send-quote log]
  (let [settings (merge default-settings (:account/settings account))
        push (m/rdv)
        state-a (atom {})
        rmp (random-msg-processor. account log state-a (:initial-price settings))]
    (m/sp
     (log {:type :random-quote-start :account (:account/id account) :settings settings})
     (m/? (m/join vector
                  (subscription-watcher account rmp subscription-a push log)
                  (message-loop account state-a subscription-a settings log send-quote)
                  (drain-rdv push))))))
