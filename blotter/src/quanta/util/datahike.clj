(ns quanta.util.datahike
  (:require
   [taoensso.timbre :as timbre :refer [info warn]]
   [datahike.api :as d]))

(defn- path->id
  "Deterministic store id derived from the path, so connect/create agree for a
   given path while different paths get distinct ids. (datahike requires a store :id)"
  [path]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str path) "UTF-8")))

(defn- file-cfg
  [schema path uuid]
  {:store {:backend :file
           :path path
           :id uuid}
   :keep-history? false
   :schema-flexibility :write
   :initial-tx schema})

(defn- mem-cfg [schema id]
  {:store {:backend :memory
           :id id}
   :keep-history? false
   :schema-flexibility :write
   :initial-tx schema})

(defn- create! [cfg]
  (warn "creating datahike db..")
  (when (d/database-exists? cfg)
    (d/delete-database cfg))
  (d/create-database cfg)
  (d/connect cfg))

#_(defn- ensure-schema! [conn schema]
  (d/transact conn schema))

(defn- run-seed-fns! [conn seed-fn]
  (when seed-fn
    (let [fns (if (vector? seed-fn) seed-fn [seed-fn])]
      (info "running seed fns .." (count fns))
      (doseq [f fns]
        (f conn))
      (info "seed fns done .."))))

(defn db-start
  [{:keys [schema db-path uuid seed-fn]}]
  (info "db starting at path: " db-path)
  (let [uuid (or uuid (path->id db-path))
        cfg (file-cfg schema db-path uuid)]
    (if (d/database-exists? cfg)
      (let [conn (d/connect cfg)]
        ;(ensure-schema! conn schema)
        conn)
      (let [conn (create! cfg)]
        (run-seed-fns! conn seed-fn)
        conn))))

(defn db-start-mem
  "Starts an in-memory datahike db. Useful for tests / repl.
   The optional id must be a UUID; a random one is generated otherwise."
  ([schema] (db-start-mem schema (java.util.UUID/randomUUID)))
  ([schema id]
   (let [id (if (uuid? id) id (java.util.UUID/randomUUID))]
     (info "db (mem) starting with id: " id)
     (create! (mem-cfg schema id)))))

(defn db-stop [conn]
  (when conn
    (info "db stopping ..")
    (d/release conn)))
