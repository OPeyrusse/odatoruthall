(ns build
  "Builds the one artefact this workspace ships: an uberjar that runs both
  applications in one process.

  `./scripts/build-jar.sh`, or `clojure -T:build uber` from this directory.

  Only `odato.server.main` is compiled ahead of time — enough to give the jar a
  Main-Class and a fast first request, without the surprises a whole-workspace
  AOT brings. The stylesheet and the Datastar bundle are ordinary resources of
  the server base, so they travel in the jar and the image needs nothing else."
  (:require [clojure.tools.build.api :as b]))

(def lib 'odatoruthall/server)
(def main-ns 'odato.server.main)
(def target "target")
(def class-dir (str target "/classes"))

(defn- version []
  (or (System/getenv "ODATO_VERSION")
      ;; Outside a checkout — inside the image build, say — there is no
      ;; revision to count, and that is not a reason to fail a build.
      (try (str "0.1." (b/git-count-revs nil))
           (catch Exception _ "0.1.0-dev"))))

(defn- basis []
  (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path target}))

(defn uber
  "The jar: `java -jar target/odatoruthall.jar`."
  [_]
  (let [version (version)
        basis (basis)
        jar (str target "/odatoruthall.jar")]
    (clean nil)
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis})
    (b/copy-dir {:src-dirs (:paths basis) :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :ns-compile [main-ns]
                    :class-dir class-dir
                    :compile-opts {:direct-linking false}})
    (b/uber {:class-dir class-dir
             :uber-file jar
             :basis basis
             :main main-ns})
    (println "built" jar "version" version)
    jar))
