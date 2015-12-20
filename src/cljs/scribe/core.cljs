(ns scribe.core
    (:require-macros [cljs.core.async.macros :refer [go]])
    (:require [reagent.core :as r :refer [atom]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [cljs.core.async :as async :refer [<!]]
              [cljs-http.client :as http]
              [clojure.set :as set]))

(enable-console-print!)

;; -------------------------
;; Application

;(def update-delay 300000)
(def update-delay 1000)

(def project-tree (atom {:root 0
                         0 {:name "Test project"
                            :children [1 2]}
                         1 {:name "First document"}
                         2 {:name "Second document"}}))

(def document-contents (atom {1 {:text "Initial content"
                                 :notes "Initial notes"
                                 :synopsis "Initial synopsis"}
                              2 {:text "Content 2"
                                 :notes "Notes 2"
                                 :synopsis "Syn 2"}}))

(def selected-document (atom 1))

(def possibly-need-update (atom false))
(def sending (atom false))
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
        (when (not= last-tree now-tree)
          (println "Treediff: " (diff last-tree now-tree))
          (<! (http/post "/update-tree" {:json-params (diff last-tree now-tree)}))
          (swap! last-sent assoc :tree now-tree))
        (when (not= last-documents now-documents)
          (println "Documentsdiff: " (diff last-documents now-documents))
          (<! (http/post "/update-documents" {:json-params (diff last-documents now-documents)}))
          (swap! last-sent assoc :documents now-documents))
        (reset! sending false)
        (reset! possibly-need-update false)))))

(defn watch-atom
  [atom]
  (let [f (fn [_ _ _ _]
            (when-not @possibly-need-update
              (reset! possibly-need-update true)
              (.setTimeout js/window possibly-push-updates update-delay)))]
    (add-watch atom :possibly-update f)))

(watch-atom document-contents)
(watch-atom project-tree)

(defn edit-field
  [kind]
  [:textarea {:on-change #(swap! document-contents assoc-in [@selected-document kind] (-> % .-target .-value)) 
              :value (kind (@document-contents @selected-document))}])

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
  [:ul
   (for [id (:children (@project-tree (:root @project-tree)))]
     ^{:key id} [project-item id])])

(defn home-page []
  [:div [:h2 "Welcome to scribe"]
   [:div [:a {:href "/about"} "go to about page"]]
   [simplified-project-view]
   [main-document]])

(defn current-page []
  [:div [(session/get :current-page)]])

;; -------------------------
;; Routes

(secretary/defroute "/" []
  (session/put! :current-page #'home-page))

;; -------------------------
;; Initialize app

(defn mount-root []
  (r/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (accountant/configure-navigation!)
  (accountant/dispatch-current!)
  (mount-root))
