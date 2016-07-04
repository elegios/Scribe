(ns scribe.handler
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [re-frame.core :refer [register-handler dispatch path]]
            [cljs.core.async :refer [<!]]
            [cljs-http.client :as http]
            [scribe.js-util :refer [project-url json-parse]]
            [scribe.util :refer [find-parent item-before insert-after modify-when diff]]))

(def update-delay 10000) ; time in ms between last edit and network save
(def word-count-delay 2000) ; time in ms between last edit and word count update

(register-handler :initialize
  (fn [db [_ tree & [content]]]
    (let [content (or content {})]
      {:current {:tree tree
                 :content content}
       :last-sent {:tree tree
                   :content content}
       :selected-document (:root tree)
       :network {:possibly-need-update false
                 :sending false
                 :timer nil}
       :word-count {:count 0
                    :timer nil}
       :dragging {:id nil
                  :start nil
                  :pos nil}})))

; local events (public)
; =====================

(register-handler :update-content
  (path [:current :content])
  (fn [content [_ id type value]]
    (dispatch [:poke-network])
    (dispatch [:poke-word-count])
    (assoc-in content [id type] value)))

(register-handler :update-name
  (path [:current :tree])
  (fn [tree [_ id value]]
    (dispatch [:poke-network])
    (assoc-in tree [id :name] value)))

(register-handler :toggle-collapsed
  (path [:current :tree])
  (fn [tree [_ id]]
    (update-in tree [id :collapsed] not)))

(register-handler :select-document
  (path [:selected-document])
  (fn [_ [_ id]]
    (dispatch [:word-count])
    id))

(register-handler :delete-document
  (fn [{{tree :tree} :current selected :selected-document :as db} [_ id]]
    (if (empty? (:children (tree id)))
      (let [parent (find-parent tree id)]
        (dispatch [:poke-network])
        (when (= selected id)
          (dispatch [:word-count]))
        (-> db
            (update-in [:current :content] dissoc id)
            (update-in [:current :tree] dissoc id)
            (update-in [:current :tree parent :children]
                       (partial into [] (remove (partial = id))))
            (assoc :selected-document (if (= selected id)
                                        parent
                                        selected))))
      db)))

(register-handler :initialize-drag
  (path [:dragging])
  (fn [dragging [_ id x y]]
    (assoc dragging :start id
                    :pos {:x x :y y})))

(register-handler :move-drag
  (fn [{{:keys [start id pos]} :dragging
        {tree :tree} :current
        :as db}
       [_ x y row column]]
    (if (and (= x (:x pos)) (= y (:y pos)))
      db
      (let [id (or id start)]
        (try
          ;; This function takes a curr-id and creates a list with one entry for each
          ;; element in the tree with curr-id at the root. Each element is a list of
          ;; valid locations for drag'n'drop dropping. If the element is truthy that
          ;; column is valid, and the element will describe where the drop-point will
          ;; put the dropped thing. Such a list will never have trailing nils, i.e.
          ;; if no other column matches, take the last.
          ;; id -> same parent as id, after id
          ;; '(id) -> as first child of id
          (letfn [(trans [curr-id]
                    (let [{:keys [collapsed children]} (tree curr-id)
                          children (if collapsed nil children)
                          filtered-children (remove #(= % id) children)
                          curr (cond
                                 (not children) (list curr-id)
                                 (empty? filtered-children) (list curr-id (list curr-id))
                                 :otherwise (list nil (list curr-id)))
                          children (sequence (mapcat trans) filtered-children)
                          last-index (dec (count children))]
                      (cons curr
                            (map-indexed #(conj %2 (when (and (= %1 last-index)
                                                              (not= curr-id (:root tree)))
                                                      curr-id))
                                         children))))]
            (let [positions    (trans (:root tree))
                  position     (or (nth positions row nil)
                                   (last positions))
                  insert-point (or (some identity (nthrest position column))
                                   (last position))
                  prev-parent (find-parent tree id)
                  prev-point (if (= id (first (:children (tree prev-parent))))
                               (list prev-parent)
                               (item-before (:children (tree prev-parent)) id))]
              ; sanity check
              (when (or (nil? insert-point)
                        (and (not (list? insert-point))
                             (not (find-parent tree insert-point))))
                 (throw (js/Error. (str "Failure in dragging, invalid insert-point: " insert-point))))
              (dispatch [:poke-network])
              (-> db
                  (assoc-in [:dragging :pos] {:x x :y y})
                  (modify-when start
                    update :dragging assoc :id start :start nil)
                  (modify-when (not= prev-point insert-point)
                    update-in [:current :tree]
                      #(let [removed (update-in % [prev-parent :children]
                                                  (partial into [] (remove (partial = id))))]
                         (if (list? insert-point)
                           (update-in removed
                                      [(first insert-point) :children]
                                      (partial into [id]))
                           (update-in removed
                                      [(find-parent tree insert-point) :children]
                                      insert-after insert-point id)))))))
         (catch js/Error e
           (println e)
           db))))))

(register-handler :end-drag
  (path [:dragging])
  (fn [dragging _]
    (assoc dragging :start nil
                    :id nil)))

; network events (public)
; =======================

; triggers a fetch for the given document. Note that it will be replaced
; when/if the response comes in, regardless of previous content.
(register-handler :fetch
  (fn [db [_ id]]
    (when id
      (go (let [response (<! (http/get (project-url "/document") {:query-params {"id" id}}))]
            (if (:success response)
              (dispatch [:document-fetched id (json-parse (:body response))])
              (println "Failed to fetch document with id" id)))))
    db))

; create a document (:file or :folder) as the last child of parent. If parent
; is not a folder, the new document is created as the last child of parents parent
; (Note that the server is queried for the id, i.e. cannot create without connection)
(register-handler :create-document
  (fn [{{tree :tree} :current :as db} [_ parent type]]
    (go
      (let [parent (if (:children (tree parent))
                       parent
                       (find-parent tree parent))
            response (<! (http/post (project-url "/document")
                                    {:query-params {"parent" parent
                                                    "folder" (= type :folder)}}))]
        (if (:success response)
          (dispatch [:document-created
                     parent
                     type
                     (:id (json-parse (:body response)))])
          (println "Failed to create" (name type)))))
    db))

; send updates if there are any and we're not currently sending
(register-handler :trigger-send
  (fn [{{:keys [sending]} :network
        {curr-tree :tree curr-content :content} :current
        {last-tree :tree last-content :content} :last-sent
        :as db} _]
    (if sending
      ; do nothing if sending
      (assoc-in db [:network :timer] nil)

      ; otherwise, start async requests and set sending
      (do
        (go
          (try
            (when (not= last-tree curr-tree)
              (let [to-send (diff last-tree curr-tree)]
                (when-not (empty? to-send)
                  (let [resp (<! (http/post (project-url "/tree") {:json-params to-send}))]
                    (if (:success resp)
                      (dispatch [:part-sent :tree curr-tree])
                      (throw (js/Error. "Failed to push update of tree")))))))
            (when (not= last-content curr-content)
              (let [to-send (diff last-content curr-content)]
                (when-not (empty? to-send)
                  (let [resp (<! (http/post (project-url "/documents") {:json-params to-send}))]
                    (if (:success resp)
                      (dispatch [:part-sent :content curr-content])
                      (throw (js/Error. "Failed to push update of content")))))))
            (dispatch [:send-done true])
            (catch js/Error e
              (println "Failure in pushing updates:" e)
              (dispatch [:send-done false]))))
        (update db :network assoc :timer nil
                                  :sending true)))))

; network events (private)
; ========================

(register-handler :poke-network
  (path [:network])
  (fn [{:keys [timer] :as network} _]
    (when timer
      (js/clearTimeout timer))
    (assoc network :timer (js/setTimeout #(dispatch [:trigger-send]) update-delay)
                   :possibly-need-update true)))

(register-handler :part-sent
  (path [:last-sent])
  (fn [last-sent [_ type value]]
    (assoc last-sent type value)))

(register-handler :send-done
  (path [:network])
  (fn [network [_ success]]
    (if success
      (assoc network :sending false
                     :possibly-need-update false)
      (assoc network :sending false))))

(register-handler :document-created
  (fn [db [_ parent type id]]
    (let [tree-entity (case type
                        :folder {:name "" :children []}
                        :file {:name ""})
          content-entity (case type
                           :folder {:notes "" :synopsis ""}
                           :file {:notes "" :synopsis "" :text ""})]
      (-> db
          (assoc-in [:current :content id] content-entity)
          (assoc-in [:current :tree id] tree-entity)
          (update-in [:current :tree parent :children] conj id)
          (assoc-in [:last-sent :content id] content-entity)
          (assoc-in [:last-sent :tree id] tree-entity)
          (update-in [:last-sent :tree parent :children] conj id)))))

(register-handler :document-fetched
  (fn [db [_ id content]]
    (when (= (:selected-document db) id)
      (dispatch [:word-count]))
    (-> db
        (assoc-in [:current :content id] content)
        (assoc-in [:last-sent :content id] content))))

; word count events (private)
; ===========================

(register-handler :poke-word-count
  (path [:word-count])
  (fn [{:keys [timer] :as word-count} _]
    (when timer
      (js/clearTimeout timer))
    (assoc word-count :timer (js/setTimeout #(dispatch [:word-count]) word-count-delay))))

(register-handler :word-count
  (fn [db _]
    (let [id (:selected-document db)
          text (get-in db [:current :content id :text])
          count (if text (js/countWords text) 0)]
      (assoc db :word-count {:timer nil :count count}))))

;
