(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.clojure-quant/quanta-blotter-cli)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn- basis [deploy?]
  (if deploy?
    (b/create-basis {:project "deps.edn"
                     :extra-deps {'io.github.clojure-quant/quanta-blotter
                                  {:mvn/version version}}})
    (b/create-basis {:project "deps.edn" :aliases [:dev]})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [opts]
  (clean nil)
  (let [deploy? (boolean (:deploy opts))
        basis (basis deploy?)]
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]
                  :pom-data [[:licenses
                              [:license
                               [:name "Eclipse Public License 1.0"]
                               [:url "https://www.eclipse.org/legal/epl-v10.html"]
                               [:distribution "repo"]]]]
                  :scm {:url "https://github.com/clojure-quant/quanta-blotter"
                        :connection "scm:git:git://github.com/clojure-quant/quanta-blotter.git"
                        :developerConnection "scm:git:ssh://git@github.com/clojure-quant/quanta-blotter.git"
                        :tag (str "v" version)}})
    (b/jar {:class-dir class-dir :jar-file jar-file})))

(defn deploy [_]
  (jar {:deploy true})
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
