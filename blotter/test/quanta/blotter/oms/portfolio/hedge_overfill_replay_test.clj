(ns quanta.blotter.oms.portfolio.hedge-overfill-replay-test
  "Replay of the AUDUSD messages recorded in demo/log/oms-server-transaction.txt.
   The account trades in hedge mode (the broker echoes :position-id on fills)."
  (:require
   [clojure.test :refer [deftest is]]
   [quanta.blotter.oms.portfolio :as portfolio]))

(def ^:private position-id "234644150")

(def ^:private messages
  [;; H7Zenj: buy 10000 limit, cancelled before any fill
   {:order-type :limit :date #inst "2026-07-31T17:52:41.797-00:00" :limit 0.70365M
    :account/id 1000 :type :trader/new-order :order-id "H7Zenj" :label :manual
    :campaign "manual order" :side :buy :qty 10000M :asset "AUDUSD"}
   {:order-type :limit :date #inst "2026-07-31T17:52:41.845-00:00" :limit 0.70365M
    :account/id 1000 :type :broker/order-confirmed :order-id "H7Zenj" :label :manual
    :position-id "234643980" :campaign "manual order" :side :buy :qty 10000M :asset "AUDUSD"}
   {:account/id 1000 :order-id "H7Zenj" :asset "AUDUSD" :type :trader/cancel-order
    :date #inst "2026-07-31T17:53:51.645-00:00" :campaign "manual order" :label :manual}
   {:type :broker/order-canceled :account/id 1000 :order-id "H7Zenj"
    :date #inst "2026-07-31T17:53:51.694-00:00" :campaign "manual order" :label :manual}

   ;; kylf_F: buy 10000 filled -> long 10000
   {:order-type :limit :date #inst "2026-07-31T17:53:57.195-00:00" :limit 0.70391M
    :account/id 1000 :type :trader/new-order :order-id "kylf_F" :label :manual
    :campaign "manual order" :side :buy :qty 10000M :asset "AUDUSD"}
   {:order-type :limit :date #inst "2026-07-31T17:53:57.241-00:00" :limit 0.70391M
    :account/id 1000 :type :broker/order-confirmed :order-id "kylf_F" :label :manual
    :position-id position-id :campaign "manual order" :side :buy :qty 10000M :asset "AUDUSD"}
   {:date #inst "2026-07-31T17:53:57.463-00:00" :account/id 1000 :type :broker/order-filled
    :fill-id "__dLfI87" :order-id "kylf_F" :label :manual :position-id position-id
    :campaign "manual order" :side :buy :qty 10000M :price 0.70387M :asset "AUDUSD"}

   ;; jHd1Sx: sell 6000 limit, cancelled before any fill
   {:order-type :limit :date #inst "2026-07-31T17:54:28.882-00:00" :limit 0.70381M
    :account/id 1000 :type :trader/new-order :order-id "jHd1Sx" :label :manual
    :position-id position-id :campaign "manual order" :side :sell :qty 6000M :asset "AUDUSD"}
   {:order-type :limit :date #inst "2026-07-31T17:54:28.928-00:00" :limit 0.70381M
    :account/id 1000 :type :broker/order-confirmed :order-id "jHd1Sx" :label :manual
    :position-id position-id :campaign "manual order" :side :sell :qty 6000M :asset "AUDUSD"}

   ;; bo-mzt: sell 4000 filled -> long 6000
   {:order-type :limit :date #inst "2026-07-31T17:55:55.684-00:00" :limit 0.70348M
    :account/id 1000 :type :trader/new-order :order-id "bo-mzt" :label :manual
    :position-id position-id :campaign "manual order" :side :sell :qty 4000M :asset "AUDUSD"}
   {:order-type :limit :date #inst "2026-07-31T17:55:55.729-00:00" :limit 0.70348M
    :account/id 1000 :type :broker/order-confirmed :order-id "bo-mzt" :label :manual
    :position-id position-id :campaign "manual order" :side :sell :qty 4000M :asset "AUDUSD"}
   {:date #inst "2026-07-31T17:55:56.028-00:00" :account/id 1000 :type :broker/order-filled
    :fill-id "__LlLYXp" :order-id "bo-mzt" :label :manual :position-id position-id
    :campaign "manual order" :side :sell :qty 4000M :price 0.70374M :asset "AUDUSD"}

   {:account/id 1000 :order-id "jHd1Sx" :asset "AUDUSD" :type :trader/cancel-order
    :date #inst "2026-07-31T17:56:11.868-00:00" :campaign "manual order" :label :manual}
   {:type :broker/order-canceled :account/id 1000 :order-id "jHd1Sx"
    :date #inst "2026-07-31T17:56:11.914-00:00" :campaign "manual order" :label :manual}

   ;; jT65Qc: sell 10000 limit, cancelled before any fill
   {:order-type :limit :date #inst "2026-07-31T17:56:55.308-00:00" :limit 0.70406M
    :account/id 1000 :type :trader/new-order :order-id "jT65Qc" :label :manual
    :position-id position-id :campaign "manual order" :side :sell :qty 10000M :asset "AUDUSD"}
   {:order-type :limit :date #inst "2026-07-31T17:56:55.353-00:00" :limit 0.70406M
    :account/id 1000 :type :broker/order-confirmed :order-id "jT65Qc" :label :manual
    :position-id position-id :campaign "manual order" :side :sell :qty 10000M :asset "AUDUSD"}
   {:account/id 1000 :order-id "jT65Qc" :asset "AUDUSD" :type :trader/cancel-order
    :date #inst "2026-07-31T17:57:00.460-00:00" :campaign "manual order" :label :manual}
   {:type :broker/order-canceled :account/id 1000 :order-id "jT65Qc"
    :date #inst "2026-07-31T17:57:00.504-00:00" :campaign "manual order" :label :manual}

   ;; 3IfRLb: sell 6000 limit, cancelled before any fill
   {:order-type :limit :date #inst "2026-07-31T17:57:08.828-00:00" :limit 0.70406M
    :account/id 1000 :type :trader/new-order :order-id "3IfRLb" :label :manual
    :position-id position-id :campaign "manual order" :side :sell :qty 6000M :asset "AUDUSD"}
   {:order-type :limit :date #inst "2026-07-31T17:57:08.882-00:00" :limit 0.70406M
    :account/id 1000 :type :broker/order-confirmed :order-id "3IfRLb" :label :manual
    :position-id position-id :campaign "manual order" :side :sell :qty 6000M :asset "AUDUSD"}
   {:account/id 1000 :order-id "3IfRLb" :asset "AUDUSD" :type :trader/cancel-order
    :date #inst "2026-07-31T18:06:55.298-00:00" :campaign "manual order" :label :manual}
   {:type :broker/order-canceled :account/id 1000 :order-id "3IfRLb"
    :date #inst "2026-07-31T18:06:55.343-00:00" :campaign "manual order" :label :manual}

   ;; EinmJg: buy 6000 filled -> long 12000
   {:order-type :market :date #inst "2026-07-31T18:07:30.842-00:00" :account/id 1000
    :type :trader/new-order :order-id "EinmJg" :label :manual :position-id position-id
    :campaign "manual order" :side :buy :qty 6000M :asset "AUDUSD"}
   {:order-type :market :date #inst "2026-07-31T18:07:30.890-00:00" :account/id 1000
    :type :broker/order-confirmed :order-id "EinmJg" :label :manual :position-id position-id
    :campaign "manual order" :side :buy :qty 6000M :asset "AUDUSD"}
   {:date #inst "2026-07-31T18:07:31.113-00:00" :account/id 1000 :type :broker/order-filled
    :fill-id "__hMutvr" :order-id "EinmJg" :label :manual :position-id position-id
    :campaign "manual order" :side :buy :qty 6000M :price 0.70357M :asset "AUDUSD"}

   ;; l61NbX: sell 16000 filled -> closes long 12000 and reverses into short 4000
   {:order-type :market :date #inst "2026-07-31T18:07:39.451-00:00" :account/id 1000
    :type :trader/new-order :order-id "l61NbX" :label :manual :position-id position-id
    :campaign "manual order" :side :sell :qty 16000M :asset "AUDUSD"}
   {:order-type :market :date #inst "2026-07-31T18:07:39.495-00:00" :account/id 1000
    :type :broker/order-confirmed :order-id "l61NbX" :label :manual :position-id position-id
    :campaign "manual order" :side :sell :qty 16000M :asset "AUDUSD"}
   {:date #inst "2026-07-31T18:07:39.752-00:00" :account/id 1000 :type :broker/order-filled
    :fill-id "__Ljb7b4" :order-id "l61NbX" :label :manual :position-id position-id
    :campaign "manual order" :side :sell :qty 16000M :price 0.70345M :asset "AUDUSD"}

   ;; lgVvvs: buy 4000 filled -> closes the short, account is flat
   {:order-type :market :date #inst "2026-07-31T18:10:04.723-00:00" :account/id 1000
    :type :trader/new-order :order-id "lgVvvs" :label :manual :position-id position-id
    :campaign "manual order" :side :buy :qty 4000M :asset "AUDUSD"}
   {:order-type :market :date #inst "2026-07-31T18:10:04.767-00:00" :account/id 1000
    :type :broker/order-confirmed :order-id "lgVvvs" :label :manual :position-id position-id
    :campaign "manual order" :side :buy :qty 4000M :asset "AUDUSD"}
   {:date #inst "2026-07-31T18:10:04.997-00:00" :account/id 1000 :type :broker/order-filled
    :fill-id "__n7Pt8q" :order-id "lgVvvs" :label :manual :position-id position-id
    :campaign "manual order" :side :buy :qty 4000M :price 0.70349M :asset "AUDUSD"}])

(defn- replay [messages]
  (reduce
   (fn [{:keys [state out-msgs]} msg]
     (let [{:keys [state out-msg]} (portfolio/process-message state msg)]
       {:state state
        :out-msgs (conj out-msgs out-msg)}))
   {:state (portfolio/empty-state) :out-msgs []}
   messages))

(defn- out-msg-for-fill [out-msgs fill-id]
  (first (filter #(= fill-id (:fill-id (:msg %))) out-msgs)))

(deftest audusd-hedge-replay-ends-flat
  (let [{:keys [state out-msgs]} (replay messages)]
    (is (empty? (:open-position state))
        "all AUDUSD quantity is closed, so no position may remain open")
    (is (empty? (:working-order state))
        "every order in the log is either filled or cancelled")

    (let [{:keys [positions-change position-closed]}
          (out-msg-for-fill out-msgs "__Ljb7b4")
          [closed opened] positions-change]
      (is (= 2 (count positions-change))
          "the overfill closes the long and opens a reverse short")
      (is (= closed position-closed))
      (is (= :long (:position/side closed)))
      (is (= 16000M (:position/qty-entry closed)))
      (is (= 16000M (:position/qty-exit closed)))
      (is (= 0M (:position/qty-open closed)))
      (is (= position-id (:position/position-id closed)))
      (is (= :short (:position/side opened)))
      (is (= 20000M (:position/qty-entry opened)))
      (is (= 16000M (:position/qty-exit opened)))
      (is (= 4000M (:position/qty-open opened)))
      (is (.equals (:position/average-entry-price closed)
                   (:position/avg-exit-price opened))
          "prior long entry average becomes the short exit average")
      (is (true? (:position/hedge opened)))
      (is (= position-id (:position/position-id opened))
          "the reverse position stays under the broker position-id"))

    (let [{:keys [positions-change position-closed]}
          (out-msg-for-fill out-msgs "__n7Pt8q")
          closed (first positions-change)]
      (is (= 1 (count positions-change)))
      (is (= closed position-closed))
      (is (= :short (:position/side closed)))
      (is (= 20000M (:position/qty-entry closed)))
      (is (= 20000M (:position/qty-exit closed)))
      (is (= 0M (:position/qty-open closed))))))
