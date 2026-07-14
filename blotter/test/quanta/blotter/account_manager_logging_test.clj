(ns quanta.blotter.account-manager-logging-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [quanta.blotter.account-manager :refer [create-account-manager start-account-manager
                                           add-account remove-account]]
   [quanta.market-sim.broker-paper]
   [quanta.blotter.util-rdv :refer [create-rdv]]))

(def paper-settings
  {:reject-probability 0
   :bad-orderupdate-probability 0
   :fill-probability 0
   :fill-qty-prct [100]
   :wait-seconds 60})

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory prefix (into-array java.nio.file.attribute.FileAttribute []))))

(deftest per-account-logging-test
  (let [log-dir (temp-dir "oms-account-log-")
        order-rdv (create-rdv "test/order")
        orderupdate-rdv (create-rdv "test/orderupdate")
        am (create-account-manager {:quote-manager ::test-quote-manager}
                                   order-rdv orderupdate-rdv {:account-log-dir (.getPath log-dir)})]
    (try
      (add-account am {:account/id 1 :account/api :paper :account/settings paper-settings})
      (add-account am {:account/id 2 :account/api :paper :account/settings paper-settings})
      (start-account-manager am)
      (Thread/sleep 600)
      (testing "each account writes to its own log file"
        (let [log-1 (java.io.File. log-dir "1.log")
              log-2 (java.io.File. log-dir "2.log")]
          (is (.exists log-1))
          (is (.exists log-2))
          (is (re-find #":paper/started" (slurp log-1)))
          (is (re-find #":paper/started" (slurp log-2))
              "account 2 log is independent of account 1")))
      (finally
        (remove-account am 1)
        (remove-account am 2)
        (.delete (java.io.File. log-dir "1.log"))
        (.delete (java.io.File. log-dir "2.log"))
        (.delete log-dir)))))
