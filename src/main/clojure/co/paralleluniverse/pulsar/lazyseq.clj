 ; Pulsar: lightweight threads and Erlang-like actors for Clojure.
 ; Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 ;
 ; This program and the accompanying materials are dual-licensed under
 ; either the terms of the Eclipse Public License v1.0 as published by
 ; the Eclipse Foundation
 ;
 ;   or (per the licensee's choosing)
 ;
 ; under the terms of the GNU Lesser General Public License version 3.0
 ; as published by the Free Software Foundation.
 
 (ns co.paralleluniverse.pulsar.lazyseq
   "Functions to transform a channel into a laze seq"
   (:require
     [co.paralleluniverse.pulsar.core :refer :all]
     [clojure.core.match :refer [match]]
     [clojure.core.typed :refer [ann Option AnyInteger]])
   (:refer-clojure :exclude [promise await
                             lazy-seq seq dorun doall nthnext nthrest take take-while
                             drop repeatedly map filter])
   (:import
     [co.paralleluniverse.strands.channels Channel]
     [co.paralleluniverse.pulsar ClojureHelper SuspendableLazySeq]
     ; for types:
     [clojure.lang Seqable LazySeq ISeq]))
 
 ;; We don't need to make most seq functions suspendable because of the way lazy-seqs work
 ;; but we do need to call the lazy-seq body more than once (on each resume), and the default
 ;; implementation adds the ^:once metadata which clears closure during the first call.
 ;; Here, lazy-seq is defined without the :once tag.
(defmacro lazy-seq
  "Takes a body of expressions that returns an ISeq or nil, and yields
  a Seqable object that will invoke the body only the first time seq
  is called, and will cache the result and return it on all subsequent
  seq calls. See also - realized?"
  [& body]
  `(new co.paralleluniverse.pulsar.SuspendableLazySeq (fn [] ~@body)))
;  `(new clojure.lang.LazySeq (fn [] ~@body)))
;  (list 'new 'clojure.lang.LazySeq (list* '^{:once true} fn* [] body)))

(defn channel->lazy-seq
  "Turns a channel into a lazy-seq."
  ([^Channel channel]
   (lazy-seq
    (when-let [m (.receive channel)]
      (cons m (channel->lazy-seq channel)))))
  ([^Channel channel timeout unit]
   (lazy-seq
    (when-let [m (.receive channel (long timeout) unit)]
      (cons m (channel->lazy-seq channel timeout unit))))))

(defn snd-seq
  "Sends a sequence of messages to a channel"
  [^Channel channel ms]
  (doseq [m ms]
    (.send channel m)))

;; Suspendable versions of core seq functions

(ann seq (All [x]
              (Fn
               [(I (Seqable x) (CountRange 1)) -> (I (ISeq x) (CountRange 1))]
               [(Option (Seqable x)) -> (Option (I (ISeq x) (CountRange 1)))
                :filters {:then (& (is (CountRange 1) 0) (! nil 0))
                          :else (| (is nil 0) (is (ExactCount 0) 0))}])))
(defn ^clojure.lang.ISeq seq [x]
  (co.paralleluniverse.pulsar.SuspendableLazySeq/seq x))

(defsfn dorun
  "When lazy sequences are produced via functions that have side
  effects, any effects other than those needed to produce the first
  element in the seq do not occur until the seq is consumed. dorun can
  be used to force any effects. Walks through the successive nexts of
  the seq, does not retain the head and returns nil."
  {:added "1.0"
   :static true}
  ([coll]
   (when (seq coll)
     (recur (next coll))))
     ;(println "zzzz" (first coll))
     ;(let [n (next coll)]
     ;  ;(println "yyy" (first n))
     ;  (recur n))))
  ([n coll]
   (when (and (seq coll) (pos? n))
     (recur (dec n) (next coll)))))

(defsfn doall
  "When lazy sequences are produced via functions that have side
  effects, any effects other than those needed to produce the first
  element in the seq do not occur until the seq is consumed. doall can
  be used to force any effects. Walks through the successive nexts of
  the seq, retains the head and returns it, thus causing the entire
  seq to reside in memory at one time."
  {:added "1.0"
   :static true}
  ([coll]
   (dorun coll)
   coll)
  ([n coll]
   (dorun n coll)
   coll))

(ann nthnext (All [x]
                  [(Option (Seqable x)) AnyInteger -> (Option (I (Seqable x) (CountRange 1)))]))
(defsfn nthnext
  "Returns the nth next of coll, (seq coll) when n is 0."
  {:added "1.0"
   :static true}
  [coll n]
  (loop [n n xs (seq coll)]
    (if (and xs (pos? n))
      (recur (dec n) (next xs))
      xs)))

(ann nthrest (All [x]
                  [(Option (Seqable x)) AnyInteger -> (Option (I (Seqable x)))]))
(defsfn nthrest
  "Returns the nth rest of coll, coll when n is 0."
  {:added "1.3"
   :static true}
  [coll n]
  (loop [n n xs coll]
    (if (and (pos? n) (seq xs))
      (recur (dec n) (rest xs))
      xs)))

;; Some sequence functions must be redefined to use the modified lazy-seq

(ann take (All [x] (Fn [AnyInteger (Seqable x) -> (LazySeq x)])))
(defn take
  "Returns a lazy sequence of the first n items in coll, or all items if
  there are fewer than n."
  {:added "1.0"
   :static true}
  [n coll]
  (lazy-seq
   (when (pos? n)
     (when-let [s (seq coll)]
       (cons (first s) (take (dec n) (rest s)))))))

(ann take-while (All [x y]
                     (Fn
                      [[x -> Any :filters {:then (is y 0) :else tt}] (Option (Seqable x)) -> (Seqable y)]
                      [[x -> Any] (Option (Seqable x)) -> (Seqable x)])))
(defn take-while
  "Returns a lazy sequence of successive items from coll while
  (pred item) returns true. pred must be free of side-effects."
  {:added "1.0"
   :static true}
  [pred coll]
  (lazy-seq
   (when-let [s (seq coll)]
     (when (pred (first s))
       (cons (first s) (take-while pred (rest s)))))))

(defn drop
  "Returns a lazy sequence of all but the first n items in coll."
  {:added "1.0"
   :static true}
  [n coll]
  (let [step (sfn [n coll]
                    (let [s (seq coll)]
                      (if (and (pos? n) s)
                        (recur (dec n) (rest s))
                        s)))]
    (lazy-seq (step n coll))))

(defn repeatedly
  "Takes a function of no args, presumably with side effects, and
  returns an infinite (or length n if supplied) lazy sequence of calls
  to it"
  ([f] (lazy-seq (cons (f) (repeatedly f))))
  ([n f] (take n (repeatedly f))))

(ann map (All [v0 v1 v2 ...]
              [[v1 v2 ... v2 -> v0] (U nil (Seqable v1)) (U nil (Seqable v2)) ... v2 -> (LazySeq v0)]))
(defn map
  "Returns a lazy sequence consisting of the result of applying f to the
  set of first items of each coll, followed by applying f to the set
  of second items in each coll, until any one of the colls is
  exhausted.  Any remaining items in other colls are ignored. Function
  f should accept number-of-colls arguments."
  {:added "1.0"
   :static true}
  ([f coll]
   (lazy-seq
    (when-let [s (seq coll)]
      (cons (f (first s)) (map f (rest s)))))))

(ann filter (All [x y]
                 (Fn
                  [[x -> Any :filters {:then (is y 0) :else tt}] (Option (Seqable x)) -> (Seqable y)]
                  [[x -> Any] (Option (Seqable x)) -> (Seqable x)])))
(defn filter
  "Returns a lazy sequence of the items in coll for which
  (pred item) returns true. pred must be free of side-effects."
  {:added "1.0"
   :static true}
  ([pred coll]
   (lazy-seq
    (when-let [s (seq coll)]
      (let [f (first s)
            r (rest s)]
        (if (pred f)
          (cons f (filter pred r))
          (filter pred r)))))))