(ns odato.server.system-test
  "Where the installation keeps the half of itself that outlives the process."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [odato.db.interface :as db]
            [odato.fake-turso :as fake]
            [odato.server.system :as system]
            [odato.time.interface :as t]
            [odato.work-item.interface :as items]
            [odato.worklog.interface :as worklog]))

(defn- settings [extra]
  (merge {:port 0 :demo true :db-file nil
          :turso {:url nil :token nil :table-name nil}}
         extra))

(deftest with-nothing-said-the-worklog-goes-with-the-process
  (let [sys (system/create (settings {}))]
    (is (= :memory (db/backend (:store sys))))))

(deftest a-file-keeps-it
  (let [file (io/file (System/getProperty "java.io.tmpdir")
                      (str "odato-system-" (random-uuid) ".edn"))]
    (try
      (is (= :file (db/backend (:store (system/create (settings {:db-file (str file)}))))))
      (finally (.delete file) (.delete (io/file (str file ".tmp")))))))

(deftest turso-keeps-it-and-is-preferred
  (fake/with-turso [turso]
    (let [config (settings {:turso (:options turso)
                            ;; Both given: Turso is what the operator asked for.
                            :db-file "/tmp/should-not-be-used.edn"})
          ;; The system runs on the real clock, so the day has to be one the
          ;; timesheet shows today — not a date written down once.
          day (atom nil)]
      (testing "what a day was filled with"
        (let [sys (system/create config)]
          (is (= :storage (db/backend (:store sys))))
          (reset! day (:starts-at (last (worklog/window sys))))
          (items/resolve! sys "WID-88")
          (worklog/add-task! sys @day "WID-88")
          (let [entry (first (:entries (worklog/day sys @day)))]
            (worklog/enter-time! sys (:id entry) "1h 30m"))
          (system/stop! sys)))
      (testing "is what the next process finds in Turso"
        (let [sys (system/create config)
              entry (first (:entries (worklog/day sys @day)))]
          (is (= "WID-88" (:work-item-key entry)))
          (is (= "1h 30m" (:entered-text entry)))
          (is (= (t/minutes 90) (:duration-ms entry)))
          (is (= "Cache the snapshot between renders" (get-in entry [:work-item :summary]))
              "and the work item Jira named with it")
          (is (empty? (db/all (db/db (db/volatile* (:store sys))) :pr/ident))
              "while nothing gitdesk collected came back")
          (system/stop! sys)))
      (is (not (.exists (io/file "/tmp/should-not-be-used.edn")))
          "and the file was never touched"))))

(deftest a-turso-that-cannot-be-reached-stops-the-process
  (fake/with-turso [turso]
    (swap! (:state turso) assoc :unreachable true)
    (is (thrown? clojure.lang.ExceptionInfo
                 (system/create (settings {:turso (:options turso)})))
        "writing the worklog somewhere the operator did not ask for is worse than not starting")))
