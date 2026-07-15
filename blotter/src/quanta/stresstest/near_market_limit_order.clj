(ns quanta.stresstest.near-market-limit-order
  (:require
   [missionary.core :as m]
   [quanta.quote.core :as quote]))

(defn near-market-limit-order
  "Returns a task yielding a limit order offset from the current market.

   Buy orders are priced below the bid and sell orders above the ask. The
   order map must include `:asset`, `:side`, and `:offset-prct`; quote lookup
   uses the quote manager in the OMS context."
  [oms {:keys [asset side offset-prct timeout-ms]
        :or {timeout-ms 5000}
        :as order}]
  (m/sp
   (let [quote-manager (get-in oms [:ctx :quote-manager])]
     (when-not quote-manager
       (throw (ex-info "OMS context has no quote manager" {:oms oms})))
     (when-not (contains? #{:buy :sell} side)
       (throw (ex-info "Near-market limit order side must be :buy or :sell"
                       {:side side})))
     (when-not (and (number? offset-prct)
                    (pos? offset-prct)
                    (< offset-prct 100))
       (throw (ex-info "Near-market limit order offset percentage must be greater than 0 and less than 100"
                       {:offset-prct offset-prct})))
     (let [current-quote (m/? (quote/quote-snapshot quote-manager timeout-ms asset))]
       (when-not current-quote
         (throw (ex-info "Timed out waiting for asset quote"
                         {:asset asset :timeout-ms timeout-ms})))
       (let [reference-price (case side
                               :buy (:bid current-quote)
                               :sell (:ask current-quote))]
         (when-not reference-price
           (throw (ex-info "Asset quote has no price for order side"
                           {:asset asset :side side :quote current-quote})))
         (let [ref-price (if (decimal? reference-price)
                           reference-price
                           (bigdec reference-price))
               offset (/ (bigdec offset-prct) 100M)
               multiplier (case side
                            :buy (- 1M offset)
                            :sell (+ 1M offset))]
           (-> order
               (dissoc :offset-prct :timeout-ms)
               (assoc :order-type :limit
                      :limit (* ref-price multiplier)))))))))
