(ns quanta.util.session)

(defmulti connect-and-run
  "a account must implement this method to create it.
   multimethods are based on :account/session"
  (fn [account-config _asset-converter _log]
    (:account/session account-config)))
