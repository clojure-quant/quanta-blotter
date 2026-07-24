(ns quanta.stresstest.near-market-limit-order
  (:require
   [missionary.core :as m]
   [quanta.quote.core :as quote]))

(defn round-to-ref-digits
  "Round `price` to the same number of fractional digits as `reference`."
  [^BigDecimal price ^BigDecimal reference]
  (.setScale price (.scale reference) java.math.RoundingMode/HALF_UP))

(defn near-market-limit-order
  "Returns a task yielding a limit/stop order offset from the current market.

   Positive `:offset-prct` prices buys below the bid and sells above the ask
   (resting). Negative `:offset-prct` prices buys above the bid and sells below
   the ask (aggressive / fillable). The order map must include `:asset`,
   `:side`, and `:offset-prct`; optional `:order-type` is `:limit` (default) or
   `:stop`. Quote lookup uses the quote manager in the OMS context.
   `:limit` is rounded to the same fractional digits as the reference bid/ask."
  [oms {:keys [asset side offset-prct order-type timeout-ms]
        :or {timeout-ms 5000
             order-type :limit}
        :as order}]
  (m/sp
   (let [quote-manager (get-in oms [:ctx :quote-manager])]
     (when-not quote-manager
       (throw (ex-info "OMS context has no quote manager" {:oms oms})))
     (when-not (contains? #{:buy :sell} side)
       (throw (ex-info "Near-market limit order side must be :buy or :sell"
                       {:side side})))
     (when-not (contains? #{:limit :stop} order-type)
       (throw (ex-info "Near-market order-type must be :limit or :stop"
                       {:order-type order-type})))
     (when-not (and (number? offset-prct)
                    (not (zero? offset-prct))
                    (< (Math/abs (double offset-prct)) 100))
       (throw (ex-info "Near-market limit order offset percentage must be non-zero and |offset| < 100"
                       {:offset-prct offset-prct})))
     (let [current-quote (m/? (quote/quote-snapshot quote-manager timeout-ms asset))]
       (when-not current-quote
         (throw (ex-info (str "Quote Timeout " timeout-ms)
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
                            :sell (+ 1M offset))
               limit (round-to-ref-digits (* ref-price multiplier) ref-price)]
           (-> order
               (dissoc :offset-prct :timeout-ms)
               (assoc :order-type order-type
                      :limit limit))))))))
