(ns scribe.util
  (:require [clojure.set :as set]))

(defn find-parent
  [tree node-id]
  (->> tree
       (sequence (comp (filter (fn [[k]] (not= k :root)))
                       (filter (fn [[_ v]]
                                 (some #(= % node-id) (:children v))))
                       (map first)))
       first))

(defn insert-after
  [coll marker item]
  (loop [acc (transient [])
         [curr & tail] coll]
    (if (= curr marker)
      (-> acc
          (conj! curr)
          (conj! item)
          persistent!
          (into tail))
      (recur (conj! acc curr) tail))))

(defn item-before
  [coll item]
  (loop [prev (first coll)
         [curr & tail] (rest coll)]
    (if (= item curr)
      prev
      (recur curr tail))))

(defn modify-when
  [a cond f & args]
  (if cond
    (apply f a args)
    a))

(defn diff
  [prev curr]
  (let [prev-keys (apply hash-set (keys prev))
        curr-keys (apply hash-set (keys curr))
        deleted-keys (set/difference prev-keys curr-keys)
        do-diff (comp (map (fn [[k v :as pair]]
                             (if (map? v)
                               (let [prev-map (or (prev k) {})
                                     keep-nonequal (filter (fn [[k v]] (not= v (prev-map k))))]
                                 [k (into {} keep-nonequal v)])
                               pair)))
                      (filter (fn [[_ v]] (not (and (map? v) (empty? v))))))]
    (into (zipmap deleted-keys (repeat nil))
          do-diff
          curr)))

(defn- to-sub
  [binding sub]
  `[~binding (re-frame.core/subscribe ~sub)])

(defn- to-deref
  [destructure sym]
  `[~destructure (deref ~sym)])

(defmacro with-subs
  [bindings & body]
  (let [destructures (take-nth 2 bindings)
        subs (take-nth 2 (rest bindings))
        syms (map (fn [& _] (gensym)) destructures)]
    `(let [~@(apply concat (map to-sub syms subs))]
       (fn []
         (let [~@(apply concat (map to-deref destructures syms))]
           ~@body)))))
;
