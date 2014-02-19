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

(ns co.paralleluniverse.pulsar.rx
  "Functions transform channels"
  (:require
    [co.paralleluniverse.pulsar.core :refer :all]
    [clojure.core.match :refer [match]]
    [clojure.core.typed :refer [ann Option AnyInteger]])
  (:refer-clojure :exclude [promise await
                             foreach map mapcat filter zip])
  (:import
    [co.paralleluniverse.strands.channels Channels Channel ReceivePort SendPort]
    [co.paralleluniverse.strands SuspendableAction2]
    [com.google.common.base Function Predicate]
    ; for types:
    [clojure.lang Seqable LazySeq ISeq]))

(defn- ^Function fn->guava-fn
  [f]
  (reify Function
    (apply [_ arg]
      (f arg))))

(defn- ^Predicate fn->guava-pred
  [p?]
  (reify Predicate
    (^boolean apply [_ arg]
      (p? arg))))

(defn- ^SuspendableAction2 fn->SuspendableAction2
  [f]
  (let [sf (suspendable! f)]
    (reify SuspendableAction2
      (^void call [_ x y]
        (sf x y)))))

(defn ^ReceivePort map
  "Creates a receive-port (a read-only channel) that receives messages that are transformed by the
  given mapping function f from a given channel ch."
  [f ^ReceivePort ch]
  (Channels/map ^ReceivePort ch (fn->guava-fn f)))

(defn ^ReceivePort mapcat
  "Creates a receive-port (a read-only channel) that receives messages that are transformed by the
  given mapping function f from a given channel ch.

  Unlike map, the mapping function may return a single value, a sequence of values or a channel, and
  in all cases the values contained in the sequence/channel will be received one at a time by the returned
  receive-port."
  [f ^ReceivePort ch]
  (Channels/flatmap ^ReceivePort ch (fn->guava-fn
                                      (fn [x]
                                        (let [v (f x)]
                                          (cond
                                            (nil? v) v
                                            (instance? ReceivePort v) v
                                            (sequential? v) (seq->channel v)
                                            :else (singleton-channel v)))))))

(defn ^ReceivePort filter
  "Creates a receive-port (a read-only channel) that filters messages that satisfy the predicate pred
  from the given channel ch.
   All messages (even those not satisfying the predicate) will be consumed from the original channel;
   those that don't satisfy the predicate will be silently discarded."
  [pred ^ReceivePort ch]
  (Channels/filter ^ReceivePort ch (fn->guava-pred pred)))

(defn ^ReceivePort zip
  "Creates a receive-port (a read-only channel) that combines messages from the given channels
  into a single combined vector message."
  [^ReceivePort c & cs]
  (Channels/zip ^java.util.List (cons c cs) (fn->guava-fn vec)))

(defn ^ReceivePort group
  "Creates a receive-port (a read-only channel) from a group of channels.
  Receiving a message from the group will return the next message available from
  any of the channels in the group.
  A message received from the group is consumed (removed) from the original member
  channel to which it has been sent."
  ([ports]
   (Channels/group ^java.util.Collection ports))
  ([port & ports]
   (Channels/group ^java.util.Collection (cons port ports))))

(defn fiber-transform
  "Spawns a fiber that runs the supplied function `f`, which is passed the
  supplied receive-port `in` and send-port `out`"
  [in out f]
  (Channels/fiberTransform ^ReceivePort in ^SendPort out (fn->SuspendableAction2 f)))

(defn ^SendPort snd-map
  "Returns a channel that transforms messages by applying th given mapping function f
  before sending them to the given channel ch."
  [f ^SendPort ch]
  (Channels/mapSend ^SendPort ch (fn->guava-fn f)))

(defn ^SendPort snd-filter
  "Returns a channel that filters messages that satisfy the predicate pred before sending to the given channel ch.
   Messages that don't satisfy the predicate will be silently discarded when sent."
  [pred ^SendPort ch]
  (Channels/filterSend ^SendPort ch (fn->guava-pred pred)))