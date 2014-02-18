; Pulsar: lightweight threads and Erlang-like actors for Clojure.
; Copyright (C) 2013-2014, Parallel Universe Software Co. All rights reserved.
;
; This program and the accompanying materials are dual-licensed under
; either the terms of the Eclipse Public License v1.0 as published by
; the Eclipse Foundation
;
;   or (per the licensee's choosing)
;
; under the terms of the GNU Lesser General Public License version 3.0
; as published by the Free Software Foundation.

(ns co.paralleluniverse.pulsar.interop
  "Quasar-Pulsar interop helpers"
  (:refer-clojure :exclude [bean])
  (:import
    [com.google.common.collect Multiset Multiset$Entry Multimap ListMultimap SetMultimap]
    [java.util Collection Map Map$Entry Set List]
    ; for types:
    [clojure.lang Seqable LazySeq ISeq])
  (:require
    [clojure.string :as str]
    [clojure.core.typed :refer [ann Option AnyInteger]]))


(ann camel-to-dash [String -> String])
(defn camel-to-dash ; there are many ways of doing this, but it doesn't have to be fast
  [^String s]
  (.toLowerCase (str/replace s #"[a-z0-9][A-Z]" #(str (first %) \- (second %)))))
;  (apply str (cons (Character/toLowerCase (first s))
;                   (map #(if (Character/isUpperCase %) (str "-" (Character/toLowerCase %)) %) (next s)))))

(ann underscore-to-dash [String -> String])
(defn underscore-to-dash
  [^String s]
  (.toLowerCase (.replace s \_ \-)))

(ann java-name->clojure [String -> String])
(defn java-name->clojure
  [^String s]
  (-> s camel-to-dash underscore-to-dash))

;; See http://cemerick.com/2010/08/03/enhancing-clojures-case-to-evaluate-dispatch-values/
(defmacro case+
  "Same as case, but evaluates dispatch values, needed for referring to
  class and def'ed constants as well as java.util.Enum instances."
  [value & clauses]
  (let [clauses (partition 2 2 nil clauses)
        default (when (-> clauses last count (== 1))
                  (last clauses))
        clauses (if default (drop-last clauses) clauses)
        eval-dispatch (fn [d]
                        (if (list? d)
                          (map eval d)
                          (eval d)))]
    `(case ~value
       ~@(concat (->> clauses
                      (map #(-> % first eval-dispatch (list (second %))))
                      (mapcat identity))
                 default))))

(defmacro enum->keyword
  [enum-type x]
  `(when ~x
     (case (.ordinal ^Enum ~x) ; see http://stackoverflow.com/questions/16777814/is-it-possible-to-use-clojures-case-form-with-a-java-enum/16778119
       ~@(mapcat #(list (.ordinal ^Enum %) (keyword (java-name->clojure (.name ^Enum %))))
                 (eval (list '. enum-type 'values))))))

#_(defmacro enum->keyword
    [enum-type x]
    `(when ~x
       (case+ ~x
              ~@(mapcat #(list (symbol (str enum-type "/" (.name ^Enum %))) (keyword (java-name->clojure (.name ^Enum %))))
                        (eval (list '. enum-type 'values))))))

(defmacro keyword->enum
  [enum-type x]
  `(when ~x
     (case ~x
       ~@(mapcat #(list (keyword (java-name->clojure (.name ^Enum %))) (symbol (str enum-type "/" (.name ^Enum %))))
                 (eval (list '. enum-type 'values))))))

(def ^:private  ^java.lang.ClassValue bean-class-value
      (proxy [java.lang.ClassValue]
             []
        (computeValue [c]
          (reduce (fn [m ^java.beans.PropertyDescriptor pd]
                     (let [name (. pd (getName))
                           method (. pd (getReadMethod))
                           type (.getPropertyType pd)]
                       (if (and method (zero? (alength (. method (getParameterTypes)))))
                         (assoc m (keyword name) (fn [x] (clojure.lang.Reflector/prepRet type (. method (invoke x nil)))))
                         m)))
                   {}
                   (seq (.. java.beans.Introspector
                            (getBeanInfo c)
                            (getPropertyDescriptors)))))))

(defn bean
  "Takes a Java object and returns a read-only implementation of the
  map abstraction based upon its JavaBean properties."
  {:added "1.0"}
  [^Object x]
  (let [c (. x (getClass))
        pmap (.get bean-class-value c)
        v (fn [k] ((pmap k) x))
        snapshot (fn []
                   (reduce (fn [m e]
                              (assoc m (key e) ((val e) x)))
                            {} (seq pmap)))]
    (proxy [clojure.lang.APersistentMap]
           []
      (containsKey [k] (contains? pmap k))
      (entryAt [k] (when (contains? pmap k) (new clojure.lang.MapEntry k (v k))))
      (valAt ([k] (when (contains? pmap k) (v k)))
        ([k default] (if (contains? pmap k) (v k) default)))
      (cons [m] (conj (snapshot) m))
      (count [] (count pmap))
      (assoc [k v] (assoc (snapshot) k v))
      (without [k] (dissoc (snapshot) k))
      (seq [] ((fn thisfn [plseq]
                 (lazy-seq
                   (when-let [pseq (seq plseq)]
                     (cons (new clojure.lang.MapEntry (first pseq) (v (first pseq)))
                           (thisfn (rest pseq)))))) (keys pmap))))))

;; ## Guava Interop

(defn- collection?
  "Tests wheter an object is a java.util.Collection

  Returns true for lists, sets and vectors,
  false for maps an lazy sequences."
  [x]
  (instance? Collection x))

(defn- ^Map$Entry ->MapEntry
  [k v]
  (reify
    Map$Entry
    (getKey [_] k)
    (getValue [_] v)
    (setValue [_ v] (throw (throw (UnsupportedOperationException.))))
    (hashCode [_] (int (bit-xor (if (nil? k) 0 (.hashCode ^Object k)) (if (nil? k) 0 (.hashCode ^Object v)))))
    (equals [_ o]
      (boolean (when (instance? Map$Entry o)
                 (let [^Map$Entry e o]
                   (and (= k (.getKey e)) (= v (.getValue e)))))))
    (toString [_] (str k "=" v))))

(defn- ^Multiset$Entry ->MultisetEntry
  [v count]
  (reify Multiset$Entry
    (getElement [_] v)
    (getCount [_] (int count))
    (hashCode [_] (bit-xor (if (nil? v) 0 (.hashCode ^Object v)) count))
    (equals [_ o]
      (boolean (when (instance? Multiset$Entry o)
                 (let [^Multiset$Entry e o]
                   (and (= v (.getElement e)) (== (int count) (.getCount e)))))))
    (toString [_]
      (if (== count 1) (str v) (str v " x " count)))))


(defn ->Multiset
  "Returns a Guava Multiset view of a Clojure collection.
  The Clojure representation of a multiset is a map from the values to the count of their
  occurrence in the multiset."
  [m]
  (reify Multiset
    (elementSet [_] (keys m))
    (entrySet [_] (set (map (fn [[v c]] (->MultisetEntry v c)) m)))
    (size [_] (reduce + 0 (vals m)))
    (isEmpty [^Multiset this] (== (.size this) 0))
    (count [_ v] (if-let [c (get m v)] c 0))
    (contains [_ v] (boolean (when-let [c (get m v)] (pos? c))))
    (containsAll [^Multiset this vs] (boolean (some #(.contains this %) vs)))
    (iterator [_] (.iterator ^Iterable (mapcat (fn [[v c]] (repeat c v)) m)))
    (hashCode [_] (.hashCode ^Object m))
    (equals [^Multiset this o] (boolean (when (instance? Multiset o) (= (.entrySet this) (.entrySet ^Multiset o)))))
    (toString [^Multiset this] (.toString (.entrySet this)))
    (toArray [_] (to-array Object (mapcat (fn [[v c]] (repeat c v)) m)))
    (toArray [_ a]
      (let [vs (mapcat (fn [[v c]] (repeat c v)) m)]
        (if (>= (alength a) (count vs))
          (loop [i (int 0)
                 vs vs]
            (if (seq vs)
              (do
                (aset a i (first vs))
                (recur (inc i) (rest vs)))
              a))
          (into-array (.getComponentType ^Class (.getClass a)) (mapcat (fn [[v c]] (repeat c v)) m)))))
    (clear [_] (throw (UnsupportedOperationException.)))
    (add [_ v] (throw (UnsupportedOperationException.)))
    (add [_ v count] (throw (UnsupportedOperationException.)))
    (addAll [_ c] (throw (UnsupportedOperationException.)))
    (remove [_ v] (throw (UnsupportedOperationException.)))
    (remove [_ v count] (throw (UnsupportedOperationException.)))
    (removeAll [_ c] (throw (UnsupportedOperationException.)))
    (retainAll [_ c] (throw (UnsupportedOperationException.)))
    (setCount [_ v count] (throw (UnsupportedOperationException.)))
    (setCount [_ v count oldcount] (throw (UnsupportedOperationException.)))))

(defn ->Multimap
  "Returns a Guave Multimap view of a Clojure collection.
  The Clojure representation of a multimap is a map from keys to a set/vetor/list of values."
  [m]
  (reify Multimap
    (get [_ k] (let [v (get m k '())]
                 (if (collection? v) v [v])))
    (entries [_]
      (mapcat (fn [[k v]] (if (collection? v) (map #(->MapEntry k %) v) (->MapEntry k v))) m))
    (asMap [_] m)
    (containsKey [_ k]
      (let [vs (get m k)]
        (boolean (and vs (if (collection? vs) (not-empty vs) true)))))
    (containsValue [_ v] (boolean (some #(= % v) (flatten (vals m)))))
    (values [_] (flatten (vals m)))
    (keySet [_] (keys m))
    (keys [_] (->Multiset (reduce-kv (fn [m k v] (assoc m k (if (collection? v) (count v) 1) {} m)))))
    (size [_] (reduce + 0 (map #(if (collection? %) (count %) 1) (vals m))))
    (isEmpty [^Multimap this] (== (.size this) 0))
    (hashCode [_] (.hashCode ^Object m))
    (equals [_ o] (boolean (when (instance? Multimap o) (= m (.asMap ^Multimap o)))))
    (clear [_] (throw (UnsupportedOperationException.)))
    (put [_ k v] (throw (UnsupportedOperationException.)))
    (putAll [_ k vs] (throw (UnsupportedOperationException.)))
    (putAll [_ mm] (throw (UnsupportedOperationException.)))
    (remove [_ k v] (throw (UnsupportedOperationException.)))
    (removeAll [_ k] (throw (UnsupportedOperationException.)))
    (replaceValues [_ k vs] (throw (UnsupportedOperationException.)))))

(defn ->SetMultimap
  "Returns a Guave SetMultimap view of a Clojure collection.
  The Clojure representation of a set-multimap is a map from keys to a set of values."
  [m]
  (reify SetMultimap
    (get [_ k] (cast Set
                     (let [v (get m k #{})]
                       (if (collection? v) v #{v}))))
    (entries [_]
      (set (mapcat (fn [[k v]] (if (collection? v) (map #(->MapEntry k %) v) (->MapEntry k v))) m)))
    (asMap [_] m)
    (containsKey [_ k]
      (let [vs (get m k)]
        (boolean (and vs (if (collection? vs) (not-empty vs) true)))))
    (containsValue [_ v] (boolean (some #(= % v) (flatten (vals m)))))
    (values [_] (flatten (vals m)))
    (keySet [_] (keys m))
    (keys [_] (->Multiset (reduce-kv (fn [m k v] (assoc m k (if (collection? v) (count v) 1) {} m)))))
    (size [_] (reduce + 0 (map #(if (collection? %) (count %) 1) (vals m))))
    (isEmpty [^Multimap this] (== (.size this) 0))
    (hashCode [_] (.hashCode ^Object m))
    (equals [_ o] (boolean (when (instance? Multimap o) (= m (.asMap ^Multimap o)))))
    (clear [_] (throw (UnsupportedOperationException.)))
    (put [_ k v] (throw (UnsupportedOperationException.)))
    (putAll [_ k vs] (throw (UnsupportedOperationException.)))
    (putAll [_ mm] (throw (UnsupportedOperationException.)))
    (remove [_ k v] (throw (UnsupportedOperationException.)))
    (removeAll [_ k] (throw (UnsupportedOperationException.)))
    (replaceValues [_ k vs] (throw (UnsupportedOperationException.)))))

(defn ->ListMultimap
  "Returns a Guave ListMultimap view of a Clojure collection.
  The Clojure representation of a list-multimap is a map from keys to a vetor/list of values."
  [m]
  (reify ListMultimap
    (get [_ k] (cast List
                     (let [v (get m k '())]
                       (if (collection? v) v [v]))))
    (entries [_]
      (mapcat (fn [[k v]] (if (collection? v) (map #(->MapEntry k %) v) (->MapEntry k v))) m))
    (asMap [_] m)
    (containsKey [_ k]
      (let [vs (get m k)]
        (boolean (and vs (if (collection? vs) (not-empty vs) true)))))
    (containsValue [_ v] (boolean (some #(= % v) (flatten (vals m)))))
    (values [_] (flatten (vals m)))
    (keySet [_] (keys m))
    (keys [_] (->Multiset (reduce-kv (fn [m k v] (assoc m k (if (collection? v) (count v) 1) {} m)))))
    (size [_] (reduce + 0 (map #(if (collection? %) (count %) 1) (vals m))))
    (isEmpty [^Multimap this] (== (.size this) 0))
    (hashCode [_] (.hashCode ^Object m))
    (equals [_ o] (boolean (when (instance? Multimap o) (= m (.asMap ^Multimap o)))))
    (clear [_] (throw (UnsupportedOperationException.)))
    (put [_ k v] (throw (UnsupportedOperationException.)))
    (putAll [_ k vs] (throw (UnsupportedOperationException.)))
    (putAll [_ mm] (throw (UnsupportedOperationException.)))
    (remove [_ k v] (throw (UnsupportedOperationException.)))
    (removeAll [_ k] (throw (UnsupportedOperationException.)))
    (replaceValues [_ k vs] (throw (UnsupportedOperationException.)))))