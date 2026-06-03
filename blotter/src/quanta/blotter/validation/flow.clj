(ns quanta.blotter.validation.flow
  (:require
   [missionary.core :as m]
   [quanta.blotter.validation.schema :as s]))

(defn filter-valid-messages [f]
  (m/eduction
   (filter s/validate-message) f))

(defn bad-message-with-explaination [f]
  (m/eduction
   (remove s/validate-message)
   (map (fn [msg]
          {:bad-message msg
           :validation-error (s/human-error-message msg)}))
   f))