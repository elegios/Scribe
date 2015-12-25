(ns standalone-client.core
    (:require-macros [cljs.core.async.macros :refer [go]])
    (:require [reagent.core :as r :refer [atom]]
              [cljs.core.async :as async :refer [<!]]
              [cljs-http.client :as http]
              [clojure.set :as set]))

(enable-console-print!)

(defn json-parse
  [str]
  (js->clj (js/JSON.parse str) :keywordize-keys true))

(defn project-url
  [relpath]
  (str (.-pathname js/location) relpath))

;(def update-delay 300000)
;(def update-trigger-delay 150000)
(def update-delay 5000)
(def update-trigger-delay 5000)

(def project-tree (atom {}))

(def document-contents (atom {}))

(def selected-document (atom 1))

(def possibly-need-update (atom false))
(def sending (atom false))
(def recently-triggered-possible-update (atom false))
(def last-sent (atom {:tree {} :documents {}}))

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

(defn possibly-push-updates
  []
  (when-not @sending
    (let [last-tree (:tree @last-sent)
          last-documents (:documents @last-sent)
          now-tree @project-tree
          now-documents @document-contents]
      (reset! sending true)
      (go
        (try
          (when (not= last-tree now-tree)
            (let [to-send (diff last-tree now-tree)]
              (when-not (empty? to-send)
                (let [resp (<! (http/post (project-url "/tree") {:json-params to-send}))]
                  (if (:success resp)
                    (swap! last-sent assoc :tree now-tree)
                    (throw (js/Error. "Failed to push update of tree")))))))
          (when (not= last-documents now-documents)
            (let [to-send (diff last-documents now-documents)]
              (when-not (empty? to-send)
                (let [resp (<! (http/post (project-url "/documents") {:json-params to-send}))]
                  (if (:success resp)
                    (swap! last-sent assoc :documents now-documents)
                    (throw (js/Error. "Failed to push update of content")))))))
          (reset! possibly-need-update false)
          (catch js/Error e
            (println "Failure in pushing updates: " e))
          (finally
            (reset! sending false)))))))

(defn watch-atom
  [atom]
  (let [f (fn [_ _ _ _]
            (when-not @recently-triggered-possible-update
              (reset! recently-triggered-possible-update true)
              (.setTimeout js/window possibly-push-updates update-delay)
              (.setTimeout js/window #(reset! recently-triggered-possible-update false) update-trigger-delay)))]
    (add-watch atom :possibly-update f)))

(watch-atom document-contents)
(watch-atom project-tree)

(add-watch selected-document :possibly-fetch
  (fn [_ _ _ id]
    (when (and (not (@document-contents id)) (not (:children (@project-tree @selected-document))))
      (go (let [response (<! (http/get (project-url "/document") {:query-params {"id" id}}))]
            (if (:success response)
              (do
                (let [d (into {}
                              (map (fn [[k v]] [(keyword k) v]))
                              (json-parse (:body response)))]
                  (swap! document-contents assoc id d)
                  (swap! last-sent assoc-in [:documents id] d)))
              (println "Failed to fetch document with id " id)))))))

(defn create-file
  []
  (go (let [parent (:root @project-tree)
            response (<! (http/post (project-url "/document") {:query-params {"parent" parent}}))]
        (if (:success response)
          (do
            (let [id (:id (json-parse (:body response)))]
              (swap! project-tree assoc id {:name "New file"})
              (swap! project-tree update-in [parent :children] conj id)
              (swap! document-contents assoc id {:text ""
                                                 :notes ""
                                                 :synopsis ""})))
          (println "Failed to create file")))))

(defn edit-field
  [kind]
  [:textarea {:on-change #(swap! document-contents assoc-in [@selected-document kind] (-> % .-target .-value)) 
              :value (kind (@document-contents @selected-document))
              :disabled (not (@document-contents @selected-document))}])

(defn main-document []
  [:div
   [:input {:type "text"
            :value (:name (@project-tree @selected-document))
            :on-change #(swap! project-tree assoc-in [@selected-document :name] (-> % .-target .-value))}]
   [edit-field :text]
   [edit-field :notes]
   [edit-field :synopsis]])
  
(defn project-item
  [id]
  ^{:key id} [(if (= @selected-document id) :li.selected :li)
              {:on-click #(reset! selected-document id)}
              (:name (@project-tree id))])

(defn simplified-project-view []
  [:div
   [:ul
    (for [id (:children (@project-tree (:root @project-tree)))]
      ^{:key id} [project-item id])]
   [:input {:type "button"
            :on-click create-file
            :value "New File"}]])

(defn main-page []
  [:div [:h2 "Welcome to scribe"]
   [simplified-project-view]
   [main-document]])

(defn convert-js-tree
  [orig]
  (into {}
        (map (fn [[k v :as pair]] (if (= k :root)
                                    pair
                                    [(js/parseInt (name k)) v])))
        (js->clj js/StartingTree :keywordize-keys true)))

(reset! project-tree (convert-js-tree js/StartingTree))
(swap! last-sent assoc :tree @project-tree)
(reset! selected-document (:root @project-tree))
(r/render-component [main-page] (js/document.getElementById "app"))

;
