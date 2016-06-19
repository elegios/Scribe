(ns scribe.view
  (:require-macros [scribe.util :refer [with-subs]])
  (:require [re-frame.core :refer [dispatch]]
            [goog.events :as events]
            [clojure.string :as str])
  (:import [goog.events EventType]))

(def indent-width 20)

(defn cx
  [& args]
  (->> args
       (filter identity)
       (str/join " ")))

(defn drag-fn
  [id]
  (fn [event]
    (let [x (.-offsetLeft (.-currentTarget event))
          y (.-offsetTop (.-currentTarget event))
          off-x (- x (.-clientX event))
          off-y (- y (.-clientY event))
          item-height (.-offsetHeight (aget (.getElementsByClassName js/document "inner") 0))

          move-fn
          (fn [event]
            (let [x (+ (.-clientX event) off-x)
                  y (+ (.-clientY event) off-y)
                  top (.-offsetTop (aget (.getElementsByClassName js/document "tree") 0))
                  left (.-offsetLeft (aget (.getElementsByClassName js/document "tree") 0))
                  row (max 0
                           (dec (quot (+ (- y top)
                                         (/ item-height 2))
                                      item-height)))
                  column (max 0
                              (quot (- x left)
                                    indent-width))]
              (dispatch [:move-drag x y row column])))]
      (dispatch [:initialize-drag id x y])
      (events/listen js/window EventType.MOUSEMOVE move-fn)
      (events/listenOnce js/window EventType.MOUSEUP
        (fn [_]
          (events/unlisten js/window EventType.MOUSEMOVE move-fn)
          (dispatch [:end-drag]))))))

(defn tree-node
  [id & ignore-placeholder]
  (with-subs [root-id [:tree :root]
              dragging-id [:dragging :id]
              selected-id [:selected-id]
              {:keys [name children collapsed]} [:tree id]]
    (let [placeholder? (and (not ignore-placeholder)
                            (= id dragging-id))
          root? (= id root-id)
          selected? (= id selected-id)]
      [:div
       {:class (cx "tree-node" (when placeholder? "placeholder"))
        :style {:margin-left (if root? 0 indent-width)}}
       [:div.inner
        (when-not root? {:on-mouse-down (drag-fn id)})
        (when children
          [:span
           {:class (cx "collapse" (if collapsed "caret-right" "caret-down"))
            :on-mouse-down #(.stopPropagation %)
            :on-click #(dispatch [:toggle-collapsed id])}])
        [:span
         {:class (cx (when selected? "selected"))
          :on-click #(dispatch [:select-document id])}
         name]]
       (when-not collapsed
         (for [child-id children]
           ^{:key child-id} [tree-node child-id]))])))

(defn dragged []
  (with-subs [{{:keys [x y]} :pos id :id} [:dragging]]
    [:div.dragged
     {:style {:left x :top y}}
     (when id
       ^{:key id} [tree-node id true])]))

(defn left []
  (with-subs [root-id [:tree :root]
              selected-id [:selected-id]
              selected-children [:selected-node :children]]
    [:div.project
     [:div.tree
      [tree-node root-id]
      [dragged]]
     [:div.project-buttons
      [:input {:type "button"
               :value "File"
               :on-click #(dispatch [:create-document selected-id :file])}]
      [:input {:type "button"
               :value "Folder"
               :on-click #(dispatch [:create-document selected-id :folder])}]
      [:input {:type "button"
               :value "Delete"
               :disabled (not (empty? selected-children))
               :on-click #(dispatch [:delete-document selected-id])}]]]))

(defn edit-field
  [type]
  (with-subs [content [:selected-content type]
              selected-id [:selected-id]]
    [:textarea
     {:on-change #(dispatch [:update-content selected-id type (-> % .-target .-value)])
      :class type
      :value content
      :disabled (not content)}]))

(defn middle []
  (with-subs [selected-id [:selected-id]
              name [:selected-node :name]]
    [:div.main-document
     [:input.title {:type "text"
                    :value name
                    :on-change #(dispatch [:update-name selected-id
                                                        (-> % .-target .-value)])}]
     [edit-field :text]]))

(defn right []
  (with-subs [possibly-need-update [:network :possibly-need-update]
              sending [:network :sending]]
    [:div.right-side
     "Synopsis"
     [edit-field :synopsis]
     "Notes"
     [edit-field :notes]
     [:input {:type "button"
              :value (if sending "Saving Changes..." "Save Changes")
              :disabled (or sending (not possibly-need-update))
              :on-click #(dispatch [:trigger-send])}]]))

(defn main-view []
  [:div.top-level
   [left]
   [middle]
   [right]])
;
