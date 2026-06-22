(ns quanta.asset.mapper)

(defprotocol asset-mapper
  (to-api [this asset])
  (from-api [this asset-api]))

(defmulti create-asset-mapper
  (fn [account log]
    (:account/session account)))