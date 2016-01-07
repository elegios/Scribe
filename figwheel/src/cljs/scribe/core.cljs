(ns scribe.core
    (:require-macros [cljs.core.async.macros :refer [go]])
    (:require [reagent.core :as r :refer [atom]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [cljs.core.async :as async :refer [<!]]
              [cljs-http.client :as http]
              [clojure.set :as set]
              [clojure.string :as str]
              [goog.events :as events])
    (:import [goog.events EventType]))

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

(def project-tree (atom {:root 0
                         0 {:name "0 Folder"
                            :children [1 2 4 3]}
                         1 {:name "1 Document"}
                         2 {:name "2 Document"}
                         3 {:name "3 Document"}
                         4 {:name "4 Folder"
                            :children [5 6]}
                         5 {:name "5 Document"}
                         6 {:name "6 Document"}}))

(def document-contents (atom {1 {:text "1" :notes "1n" :synopsis "1s"}
                              2 {:text "2" :notes "2n" :synopsis "2s"}
                              3 {:text "3" :notes "3n" :synopsis "3s"}
                              5 {:text "5" :notes "5n" :synopsis "5s"}
                              6 {:text "6" :notes "6n" :synopsis "6s"}}))

(def selected-document (atom 0))


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
              (reset! possibly-need-update true)
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

(defn find-parent
  [node-id]
  (->> @project-tree
       (sequence (comp (filter (fn [[k]] (not= k :root)))
                       (filter (fn [[_ v]]
                                 (some #(= % node-id) (:children v))))
                       (map (fn [[k]] k))))
       first))

(defn create-document
  [kind]
  (go (let [parent (:root @project-tree)
            response (<! (http/post (project-url "/document") {:query-params {"parent" parent "folder" (= kind :folder)}}))]
        (if (:success response)
          (do
            (let [id (:id (json-parse (:body response)))
                  tree-entity (case kind
                                :folder {:name "New folder" :children []}
                                :file {:name "New file"})]
              (swap! project-tree assoc id tree-entity)
              (swap! project-tree update-in [parent :children] conj id)
              (when (= kind :file)
                (swap! document-contents assoc id {:text "" :notes "" :synopsis ""}))))
          (println "Failed to create" (name kind))))))

(defn delete-document
  [document-id]
  (let [parent (find-parent document-id)]
    (swap! document-contents dissoc document-id)
    (swap! project-tree dissoc document-id)
    (swap! project-tree
      update-in [parent :children]
                (partial into [] (remove (partial = document-id))))
    (when (= @selected-document document-id)
      (reset! selected-document parent))))

(defn edit-field
  [kind]
  [:textarea {:on-change #(swap! document-contents assoc-in [@selected-document kind] (-> % .-target .-value)) 
              :class kind
              :value (kind (@document-contents @selected-document))
              :disabled (not (@document-contents @selected-document))}])

(defn main-document []
  [:div.main-document
   [:input.title {:type "text"
                  :value (:name (@project-tree @selected-document))
                  :on-change #(swap! project-tree assoc-in [@selected-document :name] (-> % .-target .-value))}]
   [edit-field :text]])

(defn right-side []
  [:div.right-side
   "Notes"
   [edit-field :notes]
   "Synopsis"
   [edit-field :synopsis]
   [:input {:type "button"
            :value (if @sending "Pushing Changes..." "Push Changes")
            :disabled (or (not @possibly-need-update) @sending)
            :on-click possibly-push-updates}]])
  
#_(defn project-item
   [id]
   ^{:key id} [(if (= @selected-document id) :li.selected :li)
               {:on-click #(reset! selected-document id)}
               (:name (@project-tree id))])

(defn item-before
  [coll item]
  (loop [prev (first coll)
         [curr & tail] (rest coll)]
    (if (= item curr)
      prev
      (recur curr tail))))

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

(def indent-width 20)

(def dragging-id (atom nil))
(def dragging-start (atom false))
(def dragging-pos (atom {}))

(defn cx
  [& args]
  (->> args
       (filter identity)
       (str/join " ")))

(defn drag-fn
  [id]
  (fn [event]
    (reset! dragging-start id)
    (let [off-x (- (.-offsetLeft (.-currentTarget event)) (.-clientX event))
          off-y (- (.-offsetTop (.-currentTarget event)) (.-clientY event))
          item-height (.-offsetHeight (aget (.getElementsByClassName js/document "inner") 0))
          
          move-fn
          (fn [event]
            (when @dragging-start
              (reset! dragging-id @dragging-start)
              (reset! dragging-start false))
            (reset! dragging-pos {:x (+ (.-clientX event) off-x)
                                  :y (+ (.-clientY event) off-y)})
            (letfn [(trans [curr-id]
                      (let [curr (if (:children (@project-tree curr-id)) (list nil (list curr-id)) (list curr-id))
                            children (sequence (comp (remove #(= % id))
                                                     (mapcat trans))
                                               (:children (@project-tree curr-id)))
                            last-index (dec (count children))]
                        (cons curr
                              (map-indexed #(conj %2 (when (and (= %1 last-index) (not= curr-id (:root @project-tree))) curr-id))
                                           children))))]
              (let [top (.-offsetTop (aget (.getElementsByClassName js/document "inner") 0))
                    left (.-offsetLeft (aget (.getElementsByClassName js/document "inner") 0))
                    positions (trans (:root @project-tree))
                    index (max 0 (dec (quot (+ (- (:y @dragging-pos) top) (/ item-height 2)) item-height)))
                    position (or (nth positions index nil) (last positions))
                    horiz-index (max 0 (quot (- (:x @dragging-pos) left) indent-width))
                    insert-point (or (some identity (nthrest position horiz-index)) (last position))
                    prev-parent (find-parent id)
                    prev-insert-point (if (= (first (:children (@project-tree prev-parent))) id)
                                        (list prev-parent)
                                        (item-before (:children (@project-tree prev-parent)) id))]
                (when-not (= prev-insert-point insert-point)
                  (swap! project-tree
                    update-in [prev-parent :children]
                              (partial into [] (remove (partial = id))))
                  (if (list? insert-point)
                    (swap! project-tree
                      update-in [(first insert-point) :children] #(into [id] %))
                    (swap! project-tree
                      update-in [(find-parent insert-point) :children] insert-after insert-point id))))))]

      (reset! dragging-pos {:x (+ (.-clientX event) off-x)
                            :y (+ (.-clientY event) off-y)})
      (events/listen js/window EventType.MOUSEMOVE move-fn)
      (events/listenOnce js/window EventType.MOUSEUP
        (fn [_]
          (reset! dragging-id nil)
          (events/unlisten js/window EventType.MOUSEMOVE move-fn))))))

(defn tree-node
  [id & ignore-placeholder]
  [:div
   {:class (cx "tree-node" (when (and (not ignore-placeholder) (= @dragging-id id)) "placeholder"))
    :style {:margin-left indent-width}}
   [:div.inner
    {:on-mouse-down (drag-fn id)}
    (when (:children (@project-tree id))
      [:span
       {:class (cx "collapse" (if (:collapsed (@project-tree id)) "caret-right" "caret-down"))
        :on-mouse-down #(.stopPropagation %)
        :on-click #(swap! project-tree update-in [id :collapsed] not)}])
    [:span
     {:class (cx (when (= @selected-document id) "selected"))
      :on-click #(reset! selected-document id)}
     (:name (@project-tree id))]]
   (when-not (:collapsed (@project-tree id))
     (for [child-id (:children (@project-tree id))]
       ^{:key child-id} [tree-node child-id]))])

(defn dragged
  []
  [:div.dragged
   {:style {:left (:x @dragging-pos)
            :top (:y @dragging-pos)}}
   (when @dragging-id
     [tree-node @dragging-id true])])

(defn simplified-project-view []
  [:div.project
   [:div.tree
    [tree-node (:root @project-tree)]
    [dragged]]
   [:div.project-buttons
    [:input {:type "button"
             :value "File"
             :on-click #(create-document :file)}]
    [:input {:type "button"
             :value "Folder"
             :on-click #(create-document :folder)}]
    [:input {:type "button"
             :value "Delete"
             :disabled (not (empty? (:children (@project-tree @selected-document))))
             :on-click (fn [_]
                         (delete-document @selected-document)
                         (reset! selected-document (:root @project-tree)))}]]])

(defn main-page []
  [:div.top-level
   [simplified-project-view]
   [main-document]
   [right-side]])

(defn convert-js-tree
  [orig]
  (into {}
        (map (fn [[k v :as pair]] (if (= k :root)
                                    pair
                                    [(js/parseInt (name k)) v])))
        (js->clj js/StartingTree :keywordize-keys true)))

(swap! last-sent assoc :tree @project-tree)
(reset! selected-document (:root @project-tree))
;(r/render-component [main-page] (js/document.getElementById "app"))


(defn current-page []
  [:div [(session/get :current-page)]])

;; -------------------------
;; Routes

(secretary/defroute "/" []
  (session/put! :current-page #'main-page))

;; -------------------------
;; Initialize app

(defn mount-root []
  (r/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (accountant/configure-navigation!)
  (accountant/dispatch-current!)
  (mount-root))
