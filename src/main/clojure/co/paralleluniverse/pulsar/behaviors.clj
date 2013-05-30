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

(ns co.paralleluniverse.pulsar.behaviors
  "Pulsar "
  (:import
   [java.util.concurrent TimeUnit]
   [co.paralleluniverse.actors ActorRegistry Actor LocalActor ActorImpl ActorBuilder PulsarActor LifecycleListener ShutdownMessage]
   [co.paralleluniverse.actors.behaviors GenServer LocalGenServer Supervisor Supervisor$ActorInfo Supervisor$ActorMode Supervisor$RestartStrategy]
   ; for types:
   [clojure.lang Seqable LazySeq ISeq])
  (:require
   [co.paralleluniverse.pulsar :refer :all]
   [co.paralleluniverse.pulsar.interop :refer :all]
   [clojure.core.typed :refer [ann Option AnyInteger]]))

;; ## gen-server

(defprotocol Server
  (init [this])
  (handle-call [this ^Actor from id message])
  (handle-cast [this ^Actor from id message])
  (handle-info [this message])
  (handle-timeout [this])
  (terminate [this ^Throwable cause]))

(defn ^co.paralleluniverse.actors.behaviors.Server Server->java
  {:no-doc true}
  [server]
  (reify
    co.paralleluniverse.actors.behaviors.Server
    (^void init       [this]
                (init server))
    (handleCall [this ^Actor from id message]
                (handle-call server from id message))
    (^void handleCast [this ^Actor from id message]
                (handle-cast server from id message))
    (^void handleInfo [this message]
                (handle-info server message))
    (^void handleTimeout [this]
                   (handle-timeout server))
    (^void terminate  [this ^Throwable cause]
                (terminate server cause))))

(defmacro gen-server
  "Creates (but doesn't start) a new gen-server"
  {:arglists '([:name? :timeout? :mailbox-size? server & args])}
  [& args]
  (let [[{:keys [^String name ^Integer timeout ^Integer mailbox-size], :or {timeout -1 mailbox-size -1}} body] (kps-args args)]
    `(co.paralleluniverse.actors.behaviors.LocalGenServer. ~name
                                                          ^co.paralleluniverse.actors.behaviors.Server (Server->java ~(first body))
                                                          (long ~timeout) java.util.concurrent.TimeUnit/MILLISECONDS
                                                          nil (int ~mailbox-size))))

(defn call
  "Makes a synchronous call to a gen-server and returns the response"
  ([^GenServer gs m]
   (unwrap-exception
     (.call gs m)))
  ([^GenServer gs m & args]
   (unwrap-exception
     (.call gs (vec (cons m args))))))

(defn call-timed
  "Makes a synchronous call to a gen-server and returns the response"
  ([^GenServer gs timeout unit m]
   (unwrap-exception
     (.call gs m (long timeout) (->timeunit unit))))
  ([^GenServer gs timeout unit m & args]
   (unwrap-exception
     (.call gs (vec (cons m args)) (long timeout) (->timeunit unit)))))

(defn cast
  "Makes an asynchronous call to a gen-server"
  ([^GenServer gs m]
   (.cast gs m))
  ([^GenServer gs m & args]
   (.cast gs (vec (cons m args)))))

(defn shutdown
  "Asks a gen-server to shut down"
  [^GenServer gs]
  (.shutdown gs))

(defn stop
  "Stops the current gen-server"
  []
  (.stop (LocalGenServer/currentGenServer)))

(defn set-timeout
  "Sets the timeout for the current gen-server"
  [timeout unit]
  (.setTimeout (LocalGenServer/currentGenServer) timeout (->timeunit unit)))

(defn reply
  "Replies to a message sent to the current gen-server"
  [^Actor to id res]
  (.reply (LocalGenServer/currentGenServer) to id res))

(defn reply-error
  "Replies with an error to a message sent to the current gen-server"
  [^Actor to id ^Throwable error]
  (.replyError (LocalGenServer/currentGenServer) to id error))

;; ## supervisor

(defmacro create-actor
  "Creates (but doesn't start) a new actor"
  {:arglists '([:name? :mailbox-size? :lifecycle-handler? f & args])}
  [& args]
  (let [[{:keys [^String name ^Boolean trap ^Integer mailbox-size ^IFn lifecycle-handler], :or {trap false mailbox-size -1}} body] (kps-args args)]
    `(let [f# (suspendable! ~(if (== (count body) 1) (first body) `(fn [] (apply ~(first body) (list ~@(rest body))))))]
       (co.paralleluniverse.actors.PulsarActor. ~name ~trap (int ~mailbox-size) ~lifecycle-handler f#))))

(defn ^ActorBuilder actor-builder
  [f]
  (reify ActorBuilder
    (build [this]
           (f))))

(defn- actor-info
  [name f mode max-restarts duration unit shutdown-deadline-millis]
  (Supervisor$ActorInfo. name
                         (if (instance? ActorBuilder f) f (actor-builder #(create-actor f)))
                         (keyword->enum Supervisor$ActorMode mode)
                         (int max-restarts)
                         (long duration) (->timeunit unit)
                         (long shutdown-deadline-millis)))

(defn supervisor
  "Creates (but doesn't start) a new supervisor"
  ([^String name restart-strategy children-specs]
   (Supervisor. nil name (int -1)
                ^Supervisor$RestartStrategy (keyword->enum Supervisor$RestartStrategy restart-strategy)
                ^java.util.List (map #(apply actor-info %) children-specs)))
  ([restart-strategy children-specs]
   (supervisor nil restart-strategy children-specs)))

(defn add-child
  "Adds an actor to a supervisor"
  [^Supervisor supervisor name f mode max-restarts duration unit shutdown-deadline-millis]
  (.addChild supervisor (actor-info name f mode max-restarts duration unit shutdown-deadline-millis) nil))

(defn remove-child
  "Removes an actor from a supervisor"
  [^Supervisor supervisor name]
  (.removeChild supervisor name false))

(defn remove-and-terminate-child
  "Removes an actor from a supervisor and terminates the actor"
  [^Supervisor supervisor name]
  (.removeChild supervisor name true))

