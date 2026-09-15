(ns odato.test-runner
  "Runs every brick's tests. `clojure -M:dev:test`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]))

(defn- test-namespaces []
  (->> (System/getProperty "java.class.path")
       (#(str/split % (re-pattern (System/getProperty "path.separator"))))
       (filter #(str/includes? % "/test"))
       (map io/file)
       (filter #(.isDirectory %))
       (mapcat (fn [root]
                 (->> (file-seq root)
                      (filter #(and (.isFile %) (str/ends-with? (.getName %) "_test.clj")))
                      (map (fn [f]
                             (-> (subs (.getPath f) (inc (count (.getPath root))))
                                 (str/replace #"\.clj$" "")
                                 (str/replace "/" ".")
                                 (str/replace "_" "-")
                                 symbol))))))
       distinct
       sort))

(defn run [& _]
  (let [nss (test-namespaces)]
    (println "running" (count nss) "test namespaces")
    (doseq [n nss] (require n))
    (let [{:keys [fail error] :as summary} (apply test/run-tests nss)]
      (shutdown-agents)
      (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))
      summary)))

(defn -main [& args] (apply run args))
