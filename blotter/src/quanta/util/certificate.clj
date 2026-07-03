(ns quanta.util.certificate
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io])
  (:import
   (io.netty.handler.ssl SslContextBuilder)))

(def ^:private nix-ca-certificate-candidates
  ["$NIX_SSL_CERT_FILE"
   "$SSL_CERT_FILE"
   "/etc/ssl/certs/ca-bundle.crt"
   "/etc/ssl/certs/ca-certificates.crt"
   "/etc/pki/tls/certs/ca-bundle.crt"])

(defn- env-value [k]
  (let [v (System/getenv k)]
    (when (and v (not (str/blank? v)))
      v)))

(defn- expand-candidate [candidate]
  (case candidate
    "$NIX_SSL_CERT_FILE" (env-value "NIX_SSL_CERT_FILE")
    "$SSL_CERT_FILE" (env-value "SSL_CERT_FILE")
    candidate))

(defn ca-cert-file
  "Resolves the CA certificate bundle file from NixOS/Linux defaults."
  []
  (some (fn [candidate]
          (when-let [path (expand-candidate candidate)]
            (let [f (io/file path)]
              (when (.isFile f)
                (.getAbsolutePath f)))))
        nix-ca-certificate-candidates))

(defn create-client-ssl-context
  "Creates a Netty client SSL context using the system CA certificate bundle."
  []
  (if-let [ca-path (ca-cert-file)]
    (let [ca-file (io/file ca-path)]
      (println "ssl certificate manage using TLS-CA path:" (.getAbsolutePath ca-file))
      (.. (SslContextBuilder/forClient)
          (trustManager ca-file)
          (build)))
    (throw (ex-info "Could not resolve CA certificate bundle for TLS."
                    {:candidates nix-ca-certificate-candidates
                     :env {:NIX_SSL_CERT_FILE (System/getenv "NIX_SSL_CERT_FILE")
                           :SSL_CERT_FILE (System/getenv "SSL_CERT_FILE")}}))))

(defn create-certificate-manager
  "Returns certificate manager configuration for Aleph TCP client."
  []
  {:ssl-context (create-client-ssl-context)})
