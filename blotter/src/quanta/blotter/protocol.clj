(ns quanta.blotter.protocol)

(defmulti create-trade-account
  "a trade-account must implement this method to create it.
   account-config is a map that must contain 
      :account/id  - unique identifier (long)
      :account/api - the trade api identifier (keyword)s
      :account/settings - settings as required by the api
   pull - a missionary task that receives order actions 
   push - a missionary task of order updates that are sent back
   log - a function that can log messages
   "
  (fn [account-config pull push log]
    (:account/api account-config)))


