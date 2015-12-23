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

(defn project-url
  [relpath]
  (str (.-pathname js/location) relpath))

(def update-delay 300000)
(def update-trigger-delay 150000)

(def project-tree (atom {:root 0
                         0 {:name "Test project"
                            :children [1 2 3]}
                         1 {:name "First document"}
                         2 {:name "Second document"}
                         3 {:name "Unfetched document"}}))

(def document-contents (atom {1 {:text "Initial content"
                                 :notes "Initial notes"
                                 :synopsis "Initial synopsis"}
                              2 {:text "Content 2"
                                 :notes "Notes 2"
                                 :synopsis "Syn 2"}}))

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
            (let [resp (<! (http/post (project-url "/tree") {:json-params (diff last-tree now-tree)}))]
              (if (:success resp)
                (swap! last-sent assoc :tree now-tree)
                (throw "Failed to push update of tree"))))
          (when (not= last-documents now-documents)
            (let [resp (<! (http/post (project-url "/documents") {:json-params (diff last-documents now-documents)}))]
              (if (:success resp)
                (swap! last-sent assoc :documents now-documents)
                (throw "Failed to push update of content"))))
          (reset! possibly-need-update false)
          (catch :default e
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
    (when-not (@document-contents id)
      (go (let [response (<! (http/get (project-url "/document") {:query-params {"id" id}}))]
            (if (:success response)
              (do
                (println (:body response)))
                ; TODO: store retrieved result
                ; TODO: store also in last sent, since this is already up to date with server
              (println "Failed to fetch document with id " id)))))))

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
