(ns quanta.blotter.protocol)

(defmulti create-trade-account
  "a trade-account must implement this method to create it.
   ctx - opaque context map (e.g. {:quote-manager ...}) passed from OMS
   account-config is a map that must contain 
      :account/id  - unique identifier (long)
      :account/api - the trade api identifier (keyword)s
      :account/settings - settings as required by the api
   pull - a missionary task that receives order actions 
   push - a missionary task of order updates that are sent back
   log - a function that can log messages
   "
  (fn [ctx account-config pull push log]
    (:account/api account-config)))

(defprotocol trade-messaging
  (api-order [this normalized-order-msg-in])
  (blotter-order-update [this broker-orderupdate-msg-in]))

(defmulti create-trade-messaging
  "a tradeaccount must implement this method to create it.
   each quotefeed implementation must have a unique :type.
     A quotefeed must implement subscription-topic protocol."
  (fn [account-config asset-converter log]
    (:account/api account-config)))
