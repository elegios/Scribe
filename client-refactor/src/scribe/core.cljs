(ns scribe.core
  (:require [scribe.view :as view]
            [scribe.handler :as handler]
            [scribe.subscription :as subscription]
            [scribe.util :refer [convert-js-tree]]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch dispatch-sync]]))

(defn ^:export run
  (if js/StartingContent
    (dispatch-sync [:initialize (convert-js-tree js/StartingTree)
                                (convert-js-tree js/StartingContent)])
    (dispatch-sync [:initialize (convert-js-tree js/StartingTree)]))
  (add-watch (subscribe [:content]) :poke #(dispatch [:poke-network]))
  (add-watch (subscribe [:tree]) :poke #(dispatch [:poke-network]))
  (add-watch (subscribe [:selected-content]) :fetch #(when-not % (dispatch [:fetch-selected])))
  (r/render-component [view/main-view]))
;
