(ns odato.events.core)

(def ^:private history-size 200)

(defn bus []
  {:listeners (atom {})
   :history (atom [])})

(defn subscribe! [bus k f]
  (swap! (:listeners bus) assoc k f)
  (fn [] (swap! (:listeners bus) dissoc k)))

(defn unsubscribe! [bus k]
  (swap! (:listeners bus) dissoc k)
  nil)

(defn publish! [bus event]
  (swap! (:history bus) (fn [h] (vec (take-last history-size (conj h event)))))
  (doseq [[_ f] @(:listeners bus)]
    (try (f event)
         (catch Throwable _ nil)))
  event)

(defn recent [bus] @(:history bus))
