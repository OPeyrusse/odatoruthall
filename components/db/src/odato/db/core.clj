(ns odato.db.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datascript.core :as d]))

;; ---------------------------------------------------------------- schemas

(def durable-schema
  "What outlives the process."
  {;; Configuration — one for the whole installation.
   :config/scope              {:db/unique :db.unique/identity}
   :config/repositories       {:db/cardinality :db.cardinality/many}
   :config/members            {:db/cardinality :db.cardinality/many}
   :config/window-active-days {:db/cardinality :db.cardinality/many}

   ;; Work items — the Jira issues time is logged against.
   :work-item/key             {:db/unique :db.unique/identity}

   ;; Work days — one per UTC date, keyed by the midnight it opens on.
   :work-day/starts-at        {:db/unique :db.unique/identity}

   ;; Worklog entries — one task, one day, one duration.
   :wl-entry/id               {:db/unique :db.unique/identity}
   :wl-entry/day              {:db/valueType :db.type/ref}
   :wl-entry/work-item        {:db/valueType :db.type/ref}

   ;; A key the operator typed, waiting on Jira to say what it is.
   :held-name/id              {:db/unique :db.unique/identity}
   :held-name/day             {:db/valueType :db.type/ref}

   ;; A key asked of Jira, and whether the answer came.
   :lookup/key                {:db/unique :db.unique/identity}

   ;; Discovered activities — they belong to the day, not to the scan.
   :activity/ident            {:db/unique :db.unique/identity}
   :activity/day              {:db/valueType :db.type/ref}
   :activity/named-keys       {:db/cardinality :db.cardinality/many}
   :activity/pending-keys     {:db/cardinality :db.cardinality/many}
   :activity/attributed-keys  {:db/cardinality :db.cardinality/many}})

(def volatile-schema
  "What dies with the process."
  {;; Credentials and what the services said about them.
   :connection/service        {:db/unique :db.unique/identity}
   :budget/id                 {:db/unique :db.unique/identity}

   ;; The snapshot.
   :pr/ident                  {:db/unique :db.unique/identity}
   :source/ident              {:db/unique :db.unique/identity}
   :src-entry/ident           {:db/unique :db.unique/identity}
   :src-entry/source          {:db/valueType :db.type/ref}
   :src-entry/pull-request    {:db/valueType :db.type/ref}
   :src-entry/cycle           {:db/valueType :db.type/ref}

   ;; The cycles that fill it.
   :cycle/id                  {:db/unique :db.unique/identity}
   :task/id                   {:db/unique :db.unique/identity}
   :task/cycle                {:db/valueType :db.type/ref}
   :task/source               {:db/valueType :db.type/ref}
   :schedule/id               {:db/unique :db.unique/identity}

   ;; The day scans.
   :scan/id                   {:db/unique :db.unique/identity}
   :scan-task/id              {:db/unique :db.unique/identity}
   :scan-task/scan            {:db/valueType :db.type/ref}})

;; ------------------------------------------------------------- persistence

(defn- read-datoms [file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (edn/read {:eof nil} r)))

(defn- write-datoms! [file db]
  (let [tmp (io/file (str file ".tmp"))]
    (io/make-parents tmp)
    (with-open [w (io/writer tmp)]
      (binding [*out* w *print-length* nil *print-level* nil]
        (pr (mapv (fn [dt] [(:e dt) (:a dt) (:v dt)]) (d/datoms db :eavt)))))
    (.renameTo tmp (io/file file))))

;; ------------------------------------------------------------------- store

(defn open [{:keys [file]}]
  (let [loaded (when (and file (.exists (io/file file)))
                 (try (read-datoms file)
                      (catch Exception e
                        (throw (ex-info "the stored worklog could not be read"
                                        {:file (str file)} e)))))
        durable (if (seq loaded)
                  (d/conn-from-db
                   (d/init-db (map (fn [[e a v]] (d/datom e a v)) loaded) durable-schema))
                  (d/create-conn durable-schema))
        store {:durable durable
               :volatile (d/create-conn volatile-schema)
               :file (when file (str file))
               :writer (atom nil)}]
    (when file
      (d/listen! durable ::persist
                 (fn [{:keys [db-after]}] (write-datoms! file db-after))))
    store))

(defn close! [store]
  (when (:file store) (d/unlisten! (:durable store) ::persist))
  store)

(defn flush! [store]
  (when-let [file (:file store)] (write-datoms! file @(:durable store)))
  store)

;; ----------------------------------------------------------------- queries

(defn transact! [conn tx-data] (d/transact! conn tx-data))
(defn db [conn] @conn)
(defn q [query db & args] (apply d/q query db args))
(defn pull [db pattern eid] (d/pull db pattern eid))
(defn pull-many [db pattern eids] (d/pull-many db pattern eids))
(defn entity [db eid] (d/entity db eid))

(defn lookup
  "Asking one half of the store about the other half's attribute is a fair
  question with a plain answer: it holds nothing under it."
  [db attr value]
  (when (and (some? value) (contains? (:schema db) attr))
    (:e (first (d/datoms db :avet attr value)))))

(defn entity-map [db attr value]
  (when-let [eid (lookup db attr value)]
    (d/pull db '[*] eid)))

(defn put!
  "Upserts one entity, keyed by a unique attribute.

  DataScript stores no nil, so an attribute given as nil is retracted rather
  than written — which is what `last_refusal = null` and `account_login = null`
  mean in the specification. Cardinality-many attributes are replaced whole:
  the tracked repositories after a save are the ones saved, not those plus the
  ones before."
  [conn ident-attr ident-value m]
  (let [d (db conn)
        eid (lookup d ident-attr ident-value)
        many? (fn [a] (= :db.cardinality/many (get-in (:schema d) [a :db/cardinality])))
        present (into {ident-attr ident-value} (remove (comp nil? val)) m)
        stale (when eid
                (for [[a v] m
                      :when (or (nil? v) (many? a))
                      dt (d/datoms d :eavt eid a)]
                  [:db/retract eid a (:v dt)]))]
    (transact! conn (into (vec stale) [present]))))

(defn all [db attr]
  (->> (d/datoms db :aevt attr)
       (map :e)
       distinct
       (d/pull-many db '[*])))
