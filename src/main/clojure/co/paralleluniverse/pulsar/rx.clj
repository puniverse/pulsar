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

(ns co.paralleluniverse.pulsar.rx
  "Functions transform channels"
  (:require
    [co.paralleluniverse.pulsar.core :refer :all]
    [clojure.core.match :refer [match]]
    [clojure.core.typed :refer [ann Option AnyInteger]])
  (:refer-clojure :exclude [promise await
                             map filter zip])
  (:import
    [co.paralleluniverse.strands.channels Channels Channel ReceivePort SendPort]
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

(defn ^ReceivePort map
  "Creates a receive-port (a read-only channel) that receives messages that are transformed by the
  given mapping function f from a given channel ch."
  [f ^ReceivePort ch]
  (Channels/map ^ReceivePort ch (fn->guava-fn f)))

(defn ^SendPort snd-map
  "Returns a channel that transforms messages by applying th given mapping function f
  before sending them to the given channel ch."
  [f ^SendPort ch]
  (Channels/map ^SendPort ch (fn->guava-fn f)))

(defn ^ReceivePort filter
  "Creates a receive-port (a read-only channel) that filters messages that satisfy the predicate pred
  from the given channel ch.
   All messages (even those not satisfying the predicate) will be consumed from the original channel;
   those that don't satisfy the predicate will be silently discarded."
  [pred ^ReceivePort ch]
  (Channels/filter ^ReceivePort ch (fn->guava-pred pred)))

(defn ^SendPort snd-filter
  "Returns a channel that filters messages that satisfy the predicate pred before sending to the given channel ch.
   Messages that don't satisfy the predicate will be silently discarded when sent."
  [pred ^SendPort ch]
  (Channels/filter ^SendPort ch (fn->guava-pred pred)))

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