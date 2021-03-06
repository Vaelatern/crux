(ns ^:no-doc crux.mem-kv
  "In-memory KV backend for Crux."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [crux.kv :as kv]
            [crux.memory :as mem]
            [crux.system :as sys]
            [taoensso.nippy :as nippy]
            [crux.checkpoint :as cp]
            [crux.codec :as c]
            [crux.io :as cio]
            [crux.kv.index-store :as kvi])
  (:import [clojure.lang Box MapEntry]
           java.io.Closeable
           java.nio.file.Path))

(defn- persist-db [dir db]
  (let [file (io/file dir)]
    (.mkdirs file)
    (->> (for [[k v] db]
           [(mem/->on-heap k)
            (mem/->on-heap v)])
         (into {})
         (nippy/freeze-to-file (io/file file "memkv")))))

(defn- restore-db [dir]
  (->> (for [[k v] (nippy/thaw-from-file (io/file dir "memkv"))]
         [(mem/->off-heap k)
          (mem/->off-heap v)])
       (into (sorted-map-by mem/buffer-comparator))))

;; NOTE: Using Box here to hide the db from equals/hashCode, otherwise
;; unusable in practice.
(defrecord MemKvIterator [^Box db cursor]
  kv/KvIterator
  (seek [this k]
    (let [[x & xs] (subseq (.val db) >= (mem/as-buffer k))]
      (some->> (reset! cursor {:first x :rest xs})
               :first
               (key))))

  (next [this]
    (some->> (swap! cursor (fn [{[x & xs] :rest}]
                             {:first x :rest xs}))
             :first
             (key)))

  (prev [this]
    (when-let [prev (first (rsubseq (.val db) < (key (:first @cursor))))]
      (kv/seek this (key prev))))

  (value [this]
    (some->> @cursor
             :first
             (val)))

  Closeable
  (close [_]))

(defrecord MemKvSnapshot [db]
  kv/KvSnapshot
  (new-iterator [_]
    (->MemKvIterator (Box. db) (atom {:rest (seq db)})))

  (get-value [_ k]
    (get db (mem/as-buffer k)))

  Closeable
  (close [_]))

(defrecord MemKv [!db cp-job]
  kv/KvStore
  (new-snapshot [_]
    (->MemKvSnapshot @!db))

  (store [_ kvs]
    (swap! !db into (for [[k v] kvs]
                     (MapEntry/create (mem/copy-to-unpooled-buffer (mem/as-buffer k))
                                      (mem/copy-to-unpooled-buffer (mem/as-buffer v)))))
    nil)

  (delete [_ ks]
    (swap! !db #(apply dissoc % (map mem/->off-heap ks)))
    nil)

  (compact [_])

  (fsync [_]
    (log/debug "Using fsync on MemKv has no effect."))

  (count-keys [_]
    (count @!db))

  (db-dir [_] nil)

  (kv-name [this]
    (.getName (class this)))

  cp/CheckpointSource
  (save-checkpoint [this dir]
    (persist-db dir @!db)
    {:tx (kvi/latest-completed-tx this)})

  Closeable
  (close [_]
    (cio/try-close cp-job)))

(def ^:private cp-format
  {:index-version c/index-version
   ::version "1"})

(defn- try-restore-from-checkpoint [checkpointer]
  (let [db-dir (cio/create-tmpdir "memkv-cp")]
    (try
      (when (cp/try-restore checkpointer db-dir cp-format)
        (restore-db db-dir))
      (finally
        (cio/delete-dir db-dir)))))

(defn ->kv-store {::sys/deps {:checkpointer (fn [_])}}
  ([] (->kv-store {}))

  ([{:keys [checkpointer db-dir]}]
   (let [db (or (when db-dir
                  ;; for crux.kv-test/test-checkpoint-and-restore-db
                  (restore-db db-dir))
                (when checkpointer
                  (try-restore-from-checkpoint checkpointer))
                (sorted-map-by mem/buffer-comparator))
         kv-store (map->MemKv {:!db (atom db)})]
     (cond-> kv-store
       checkpointer (assoc :cp-job (cp/start checkpointer kv-store {::cp/cp-format cp-format}))))))
