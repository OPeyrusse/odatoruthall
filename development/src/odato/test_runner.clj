(ns odato.test-runner
  "Runs every brick's tests. `clojure -M:dev:test`.

  With `ODATO_TEST_REPORT` set to a file path, the report is also written
  there as JUnit XML — the form CircleCI reads into its Tests tab."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [clojure.test.junit :as junit]))

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

(defn- run-tests-junit [report nss]
  (io/make-parents report)
  (with-open [w (io/writer report)]
    (binding [test/*test-out* w]
      (junit/with-junit-output
        (apply test/run-tests nss)))))

(defn run [& _]
  (let [nss (test-namespaces)
        report (System/getenv "ODATO_TEST_REPORT")]
    (println "running" (count nss) "test namespaces")
    (doseq [n nss] (require n))
    (let [{:keys [test pass fail error] :as summary}
          (if (str/blank? report)
            (apply test/run-tests nss)
            (run-tests-junit report nss))]
      (when-not (str/blank? report)
        (println "Ran" test "tests," (+ pass fail error) "assertions:" fail "failures," error "errors; report in" report))
      (shutdown-agents)
      (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))
      summary)))

(defn -main [& args] (apply run args))
