(ns scribe.core
    (:require [reagent.core :as r :refer [atom]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]))

;; -------------------------
;; Views

(def project-tree (atom {:root 0
                         0 {:name "Test project"
                            :children [1]}
                         1 {:name "First document"}}))

(def document-contents (atom {1 {:text "Initial content"
                                 :notes "Initial notes"
                                 :synopsis "Initial synopsis"}}))

(def selected-document (atom 1))

(def possibly-need-update (atom false))
                                
(defn edit-field
  [kind]
  [:div {:on-change #(swap! document-contents assoc-in [@selected-document kind] (-> % .-target .-value))
         :contentEditable "true"}
   (kind (@document-contents @selected-document))])

(defn main-document
  []
  ; document title
  [:div
   [:input {:type "text"
            :value (:name (@project-tree @selected-document))
            :on-change #(swap! project-tree assoc-in [@selected-document :name] (-> % .-target .-value))}]
   [edit-field :text]
   [edit-field :notes]
   [edit-field :synopsis]])
  
(def Tree (r/adapt-react-class js/Tree))

(defn to-js-tree
  ([tree] (to-js-tree tree (:root tree)))
  ([tree id]
   (let [{:keys [children name collapsed]} (tree id)]
     (if children
       #js{:module name
           :children (map (partial to-js-tree tree) children)
           :collapsed collapsed
           :id id}
       #js{:module name
           :leaf true
           :id id}))))

(defn from-js-tree
  [{:keys [id leaf children collapsed module]}]
  (if leaf
    {id {:name module}}
    (conj (apply merge (map from-js-tree children))
          {id {:name module
               :children (map :id children)
               :collapsed collapsed}
           :root id})))
          
(defn render-node
  [node]
  (let [id (.-id node)]
    (r/as-element [(if (= id @selected-document) :span.node.is-active :span.node)
                   {:on-click #(reset! selected-document id)}
                   (.-module node)])))

(defn rendered-tree
  []
  [Tree {:padding-left 20
         :tree (to-js-tree @project-tree)
         :on-change #(reset! @project-tree (from-js-tree (js->clj %)))
         :render-node render-node}])
   
(defn home-page []
  [:div [:h2 "Welcome to scribe"]
   [:div [:a {:href "/about"} "go to about page"]]
   [rendered-tree]
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
