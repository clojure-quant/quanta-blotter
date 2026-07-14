(ns quanta.quote.account-manager-logging-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [quanta.quote.account-manager :refer [create-account-manager add-account remove-account]]
   [quanta.quote.random]))

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory prefix (into-array java.nio.file.attribute.FileAttribute []))))

(deftest per-account-logging-test
  (let [log-dir (temp-dir "quote-account-log-")
        am (create-account-manager {:account-log-dir (.getPath log-dir)})]
    (try
      (add-account am {:account/id 1
                       :account/api :random
                       :account/settings {:quote-tick-interval-ms 1000}})
      (add-account am {:account/id 2
                       :account/api :random
                       :account/settings {:quote-tick-interval-ms 1000}})
      (Thread/sleep 700)
      (testing "each account writes to its own log file"
        (let [log-1 (java.io.File. log-dir "1.log")
              log-2 (java.io.File. log-dir "2.log")]
          (is (.exists log-1))
          (is (.exists log-2))
          (is (re-find #":random-quote-start" (slurp log-1)))
          (is (re-find #":random-quote-start" (slurp log-2))
              "account 2 log is independent of account 1")))
      (finally
        (remove-account am 1)
        (remove-account am 2)
        (.delete (java.io.File. log-dir "1.log"))
        (.delete (java.io.File. log-dir "2.log"))
        (.delete log-dir)))))
