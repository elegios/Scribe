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

(defn copy
  "Attempts to put the given text in the users clipboard. Somewhat hacky."
  [text]
  (let [area (js/document.createElement "textarea")]
    (doto (.-style area)
      (aset "position" "fixed")
      (aset "top" 0)
      (aset "left" 0)
      (aset "width" "2em")
      (aset "height" "2em")
      (aset "padding" 0)
      (aset "border" "none")
      (aset "outline" "none")
      (aset "boxShadow" "none")
      (aset "background" "transparent"))
    (set! (.-value area) text)
    (-> js/document .-body (.appendChild area))
    (.select area)
    (try
      (when-not (js/document.execCommand "copy")
        (println "Failed copy, returned false."))
      (catch :default e
        (println "Failed copy, threw:" e))
      (finally
        (-> js/document .-body (.removeChild area))))))

;
