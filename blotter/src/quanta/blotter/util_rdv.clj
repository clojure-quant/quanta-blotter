(ns quanta.blotter.util-rdv
  "Named rendez-vous wrappers for clearer missionary cancellation messages."
  (:require
   [missionary.core :as m])
  (:import
   [missionary Cancelled]))

(defn- wrap-cancelled-f!
  "Replace generic missionary rdv cancellation messages with a named variant."
  [f! rdv-name side]
  (fn [e]
    (if (and (instance? Cancelled e)
             (= (str "Rendez-vous " side " cancelled.") (.getMessage ^Cancelled e)))
      (f! (Cancelled. (str "Rendez-vous " side " " rdv-name " cancelled.")))
      (f! e))))

(defn create-rdv
  "Create a named rendez-vous. Behaves like `m/rdv`, but cancellation messages
   include `rdv-name` so logs identify which channel was aborted.

   Give side:  `((rdv value) s! f!)`
   Take side:  `(rdv s! f!)`"
  [rdv-name]
  (let [rdv (m/rdv)]
    (fn
      ([s! f!]
       (rdv s! (wrap-cancelled-f! f! rdv-name "take")))
      ([t]
       (let [give (rdv t)]
         (fn [s! f!]
           (give s! (wrap-cancelled-f! f! rdv-name "give"))))))))
