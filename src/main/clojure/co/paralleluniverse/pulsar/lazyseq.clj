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
 
 (ns co.paralleluniverse.pulsar.lazyseq
   "Functions to transform a channel into a laze seq"
   (:require
     [co.paralleluniverse.pulsar.core :refer :all]
     [clojure.core.match :refer [match]]
     [clojure.core.typed :refer [ann Option AnyInteger]])
   (:refer-clojure :exclude [promise await
                             ;filter
                             ])
   (:import
     [co.paralleluniverse.strands.channels Channel SendPort ReceivePort]
     [co.paralleluniverse.pulsar ClojureHelper SuspendableLazySeq]
     ; for types:
     [clojure.lang Seqable LazySeq ISeq]))
 
 ;; We don't need to make most seq functions suspendable because of the way lazy-seqs work
 ;; but we do need to call the lazy-seq body more than once (on each resume), and the default
 ;; implementation adds the ^:once metadata which clears closure during the first call.
 ;; Here, lazy-seq is defined without the :once tag.
;(defmacro lazy-seq
;  "Takes a body of expressions that returns an ISeq or nil, and yields
;  a Seqable object that will invoke the body only the first time seq
;  is called, and will cache the result and return it on all subsequent
;  seq calls. See also - realized?"
;  [& body]
;  `(new co.paralleluniverse.pulsar.SuspendableLazySeq (fn [] ~@body)))
;;  `(new clojure.lang.LazySeq (fn [] ~@body)))
;;  (list 'new 'clojure.lang.LazySeq (list* '^{:once true} fn* [] body)))

(defn channel->lazy-seq
  "Turns a channel into a lazy-seq."
  ([^ReceivePort channel]
   (clojure.lang.LazySeq.
     (sfn [] (when-let [m (.receive channel)]
      (cons m (channel->lazy-seq channel))))))
  ([^ReceivePort channel timeout unit]
   (clojure.lang.LazySeq.
     (sfn [] (when-let [m (.receive channel (long timeout) unit)]
      (cons m (channel->lazy-seq channel timeout unit)))))))

(suspendable! filter)
(suspendable! doall)
(suspendable! dorun)
(suspendable! take)
(suspendable! take-while)
(suspendable! drop)
(suspendable! map)
(suspendable! nthnext)
(suspendable! nthrest)

;(defn filter
;  "Returns a lazy sequence of the items in coll for which
;  (pred item) returns true. pred must be free of side-effects."
;  {:added "1.0"
;   :static true}
;  ([pred coll]
;   (clojure.lang.LazySeq.
;     (sfn [] (when-let [s (seq coll)]
;      (let [f (first s)]
;        (if (pred f)
;          (cons f (filter pred (rest s)))
;          (filter pred (rest s)))))))))