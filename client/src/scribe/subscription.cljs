(ns scribe.subscription
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [re-frame.core :refer [register-sub]]))

(register-sub :content
  (fn [db _]
    (reaction (get-in @db [:current :content]))))

(register-sub :word-count
  (fn [db _]
    (reaction (get-in @db [:word-count :count]))))

(register-sub :tree
  (fn [db [_ & [id]]]
    (if id
      (reaction (get-in @db [:current :tree id]))
      (reaction (get-in @db [:current :tree])))))

(register-sub :selected-id
  (fn [db _]
    (reaction (:selected-document @db))))

(register-sub :selected-content
  (fn [db [_ & [type]]]
    (if type
      (reaction (get-in @db [:current :content (:selected-document @db) type]))
      (reaction (get-in @db [:current :content (:selected-document @db)])))))

(register-sub :selected-node
  (fn [db [_ & [type]]]
    (if type
      (reaction (get-in @db [:current :tree (:selected-document @db) type]))
      (reaction (get-in @db [:current :tree (:selected-document @db)])))))

(register-sub :dragging
  (fn [db [_ & [type]]]
    (if type
      (reaction (get-in @db [:dragging type]))
      (reaction (:dragging @db)))))

(register-sub :network
  (fn [db [_ & [type]]]
    (if type
      (reaction (get-in @db [:network type]))
      (reaction (:dragging @db)))))

;
