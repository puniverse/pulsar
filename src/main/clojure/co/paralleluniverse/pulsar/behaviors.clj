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
  "Pulsar high-level behaviors.
  Defines gen-servers and suprevisors"
  (:import
   [java.util.concurrent TimeUnit]
   [co.paralleluniverse.actors ActorRegistry Actor LocalActor ActorImpl ActorBuilder PulsarActor LifecycleListener ShutdownMessage]
   [co.paralleluniverse.actors.behaviors GenBehavior GenServer LocalGenServer Supervisor Supervisor$ChildSpec Supervisor$ChildMode Supervisor$RestartStrategy]
   ; for types:
   [clojure.lang Seqable LazySeq ISeq])
  (:require
   [co.paralleluniverse.pulsar.core :refer :all]
   [co.paralleluniverse.pulsar.interop :refer :all]
   [clojure.core.typed :refer [ann Option AnyInteger]]))


(defn shutdown
  "Asks a gen-server or a supervisor to shut down"
  [^GenBehavior gs]
  (.shutdown gs))

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

#_(defn- create-actor
  [f]
  (co.paralleluniverse.actors.PulsarActor. nil false (int -1) nil (suspendable! f)))

(defn- ^ActorBuilder actor-builder
  [f]
  (reify ActorBuilder
    (build [this]
           (f))))

(defn ^LocalActor create-actor
  [& args]
  (let [[{:keys [^String name ^Boolean trap ^Integer mailbox-size ^IFn lifecycle-handler ^Integer stack-size ^ForkJoinPool pool], :or {trap false mailbox-size -1 stack-size -1}} body] (kps-args args)
        f  (when (not (instance? LocalActor body))
             (suspendable! (if (== (count body) 1)
                             (first body)
                             (fn [] (apply (first body) (rest body))))))]
    (if (instance? LocalActor body)
      body
      (co.paralleluniverse.actors.PulsarActor. name trap (int mailbox-size) lifecycle-handler f))))

(defn- child-spec
  [id mode max-restarts duration unit shutdown-deadline-millis & args]
  (Supervisor$ChildSpec. id
                         (keyword->enum Supervisor$ChildMode mode)
                         (int max-restarts)
                         (long duration) (->timeunit unit)
                         (long shutdown-deadline-millis)
                         (if (instance? ActorBuilder (first args))
                           (first args)
                           (actor-builder #(apply create-actor args)))))

(defn supervisor
  "Creates (but doesn't start) a new supervisor"
  ([^String name restart-strategy init]
   (Supervisor. nil name (int -1)
                ^Supervisor$RestartStrategy (keyword->enum Supervisor$RestartStrategy restart-strategy)
                (->suspendable-callable
                 (susfn []
                        (map #(apply child-spec %) ((suspendable! init)))))))
  ([restart-strategy init]
   (supervisor nil restart-strategy init)))

(defn add-child
  "Adds an actor to a supervisor"
  [^Supervisor supervisor id mode max-restarts duration unit shutdown-deadline-millis f]
  (.addChild supervisor (child-spec id mode max-restarts duration unit shutdown-deadline-millis f)))

(defn remove-child
  "Removes an actor from a supervisor"
  [^Supervisor supervisor name]
  (.removeChild supervisor name false))

(defn remove-and-terminate-child
  "Removes an actor from a supervisor and terminates the actor"
  [^Supervisor supervisor name]
  (.removeChild supervisor name true))

(defn ^LocalActor get-child
  "Returns a supervisor's child by id"
  [^Supervisor sup id]
  (.getChild sup id))

