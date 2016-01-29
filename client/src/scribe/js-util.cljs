(ns scribe.js-util)

(defn json-parse
  [str]
  (js->clj (js/JSON.parse str) :keywordize-keys true))

(defn project-url
  [relpath]
  (str (.-pathname js/location) relpath))

(defn convert-js-tree
  [orig]
  (into {}
        (map (fn [[k v :as pair]] (if (= k :root)
                                    pair
                                    [(js/parseInt (name k)) v])))
        (js->clj orig :keywordize-keys true)))

;
