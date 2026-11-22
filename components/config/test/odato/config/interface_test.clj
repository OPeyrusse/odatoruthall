(ns odato.config.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [odato.config.interface :as config]
            [odato.events.interface :as events]
            [odato.testing :as testing*]
            [odato.time.interface :as t]))

(def a-valid-proposal
  {:repositories #{{:owner "acme" :name "widget"}}
   :members #{"ada"}
   :refresh-cadence-ms (t/minutes 60)
   :window {:opens-hour 5 :closes-hour 21
            :active-days #{:monday :tuesday :wednesday :thursday :friday}}
   :thresholds {:aging-ms (t/days 15) :comments 15 :commits 10
                :failing-commits 10 :merged-window-ms (t/days 3)}})

(deftest a-fresh-installation-starts-on-the-reference-defaults
  (let [sys (testing*/system)
        c (config/current sys)]
    (is (= (t/minutes 60) (:refresh-cadence-ms c)))
    (is (= {:opens-hour 5 :closes-hour 21
            :active-days #{:monday :tuesday :wednesday :thursday :friday}}
           (:window c)))
    (is (= {:aging-ms (t/days 15) :comments 15 :commits 10
            :failing-commits 10 :merged-window-ms (t/days 3)}
           (:thresholds c)))
    (is (not (config/collectable? sys)) "with nothing to collect yet")))

(deftest saving-says-when-it-takes-effect
  (let [sys (testing*/system)]
    (is (:ok (config/save! sys a-valid-proposal)))
    (is (testing*/saw? sys :config/collection-settings-changed))
    (is (config/collectable? sys))
    (testing "a threshold changed is a presentation change and nothing else"
      (let [bus-before (count (testing*/events-of sys :config/collection-settings-changed))]
        (config/save! sys (assoc-in a-valid-proposal [:thresholds :comments] 20))
        (is (= 20 (get-in (config/current sys) [:thresholds :comments])))
        (is (testing*/saw? sys :config/presentation-settings-changed))
        (is (= bus-before
               (count (testing*/events-of sys :config/collection-settings-changed)))
            "and never a collection change")))))

(deftest a-refused-save-changes-nothing
  (let [sys (testing*/system)]
    (config/save! sys a-valid-proposal)
    (let [in-force (config/current sys)]
      (doseq [[fault proposal]
              [[:no-repositories (assoc a-valid-proposal :repositories #{})]
               [:no-members (assoc a-valid-proposal :members #{})]
               [:cadence-out-of-bounds (assoc a-valid-proposal :refresh-cadence-ms
                                              (t/minutes 1))]
               [:cadence-out-of-bounds (assoc a-valid-proposal :refresh-cadence-ms
                                              (t/hours 48))]
               [:window-inverted (assoc-in a-valid-proposal [:window :opens-hour] 22)]
               [:no-active-days (assoc-in a-valid-proposal [:window :active-days] #{})]]]
        (let [outcome (config/save! sys proposal)]
          (is (false? (:ok outcome)))
          (is (= fault (:fault outcome)))
          (is (= in-force (config/current sys))))))))

(deftest repositories-are-read-as-owner-slash-name
  (is (= {:owner "acme" :name "widget"} (config/parse-repository "acme/widget")))
  (is (nil? (config/parse-repository "widget")))
  (is (nil? (config/parse-repository "a/b/c")))
  (is (= "acme/widget" (config/repository-key {:owner "acme" :name "widget"})))
  (is (= #{{:owner "acme" :name "widget"} {:owner "acme" :name "gear"}}
         (config/parse-repositories "acme/widget\nacme/gear\n\n")))
  (is (= #{"ada" "grace"} (config/parse-members "ada, grace"))))

(deftest the-reassignment-switch-is-off-until-switched-and-saved-apart
  (let [sys (testing*/system)]
    (is (false? (:reassignment-enabled (config/current sys))))
    (is (:reassignment-enabled (config/switch-reassignment! sys true)))
    (is (empty? (events/recent (:bus sys))) "switching it announces nothing")
    (config/save! sys a-valid-proposal)
    (is (:reassignment-enabled (config/current sys)) "saving the rest leaves it alone")
    (is (false? (:reassignment-enabled (config/switch-reassignment! sys false))))))
