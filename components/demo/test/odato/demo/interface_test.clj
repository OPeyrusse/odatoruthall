(ns odato.demo.interface-test
  (:require [clojure.test :refer [deftest is]]
            [odato.config.interface :as config]
            [odato.demo.interface :as demo]
            [odato.reassignment.interface :as reassignment]
            [odato.testing :as testing*]
            [odato.time.interface :as t]))

(deftest the-demo-plays-every-case-of-the-reassignment-pass
  (let [now (t/parse-instant testing*/default-instant)
        sys (testing*/configured! {:github (demo/github-script now)
                                   :configuration demo/configuration})
        sys (assoc sys :assigner (:github sys))]
    (config/switch-reassignment! sys true)
    (is (= [["acme/gear" 77 :remove "ada"]
            ["acme/widget" 351 :add "linus"]
            ["acme/widget" 351 :remove "ada"]
            ["acme/widget" 433 :remove "ada"]]
           (mapv (juxt (comp config/repository-key :repository) :number :op :login)
                 (:writes (reassignment/pass! sys))))
        "an approval, a change request, a bot's change request; acme/gear#81, re-assigned after the review, is left alone")))
