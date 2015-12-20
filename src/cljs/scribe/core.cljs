(ns scribe.core
    (:require [reagent.core :as r :refer [atom]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]))

;; -------------------------
;; Views

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
     [project-item id])])

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
