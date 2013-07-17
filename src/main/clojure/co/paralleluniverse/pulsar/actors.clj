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

(ns co.paralleluniverse.pulsar.actors
  "Defines actors and behaviors like gen-server and supervisor"
  (:import [java.util.concurrent TimeUnit ExecutionException TimeoutException]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.strands.channels Channel]
           [co.paralleluniverse.actors ActorRegistry Actor LocalActor ActorImpl MailboxConfig PulsarActor ActorBuilder 
            LifecycleListener ShutdownMessage]
           [co.paralleluniverse.pulsar ClojureHelper]
           [co.paralleluniverse.actors.behaviors GenBehavior Initializer BasicGenBehavior
            GenServer LocalGenServer 
            GenEvent LocalGenEvent EventHandler
            Supervisor Supervisor$ChildSpec Supervisor$ChildMode LocalSupervisor LocalSupervisor$RestartStrategy]
           ; for types:
           [clojure.lang Keyword IObj IFn IMeta IDeref ISeq IPersistentCollection IPersistentVector IPersistentMap])
  (:require [co.paralleluniverse.pulsar.core :refer :all]
            [co.paralleluniverse.pulsar.interop :refer :all]
            [clojure.string :as str]
            [clojure.core.match :refer [match]]
            [clojure.core.typed :refer [ann def-alias Option AnyInteger]]))

;; ## Private util functions
;; These are internal functions aided to assist other functions in handling variadic arguments and the like.

;; from core.clj:
(defmacro ^{:private true} assert-args
  [& pairs]
  `(do (when-not ~(first pairs)
         (throw (IllegalArgumentException.
                  (str (first ~'&form) " requires " ~(second pairs) " in " ~'*ns* ":" (:line (meta ~'&form))))))
     ~(let [more (nnext pairs)]
        (when more
          (list* `assert-args more)))))

(ann nth-from-last (All [x y]
                        (Fn [(IPersistentCollection x) Long -> x]
                            [(IPersistentCollection x) Long y -> (U x y)])))
(defn- nth-from-last
  ([coll index]
   (nth coll (- (dec (count coll)) index)))
  ([coll index not-found]
   (nth coll (- (dec (count coll)) index) not-found)))

(ann split-at-from-last (All [x]
                             [Long (IPersistentCollection x) -> (Vector* (IPersistentCollection x) (IPersistentCollection x))]))
(defn- split-at-from-last
  [index coll]
  (split-at (- (dec (count coll)) index) coll))

;; ## Actors

(defmacro actor
  "Creates a new actor."
  {:arglists '([bindings & body])}
  [bs & body]
  (assert-args
    (vector? bs) "a vector for its binding"
    (even? (count bs)) "an even number of forms in binding vector")
  `(suspendable!
     ~(if (seq bs)
        ; actor with state fields
        (let [type (gensym "actor")
              fs (vec (take-nth 2 bs)) ; field names
              ivs (take-nth 2 (next bs))] ; initial values
          (eval ; this runs at compile time!
            (let [fs (mapv #(merge-meta % {:unsynchronized-mutable true}) fs)]
              `(deftype ~type ~fs
                 clojure.lang.IFn
                 (invoke [this#] ~@body)
                 (applyTo [this# args#] (clojure.lang.AFn/applyToHelper this# args#)))))
          `(new ~type ~@ivs))
        ; regular actor
        `(fn [] ~@body))))

(defmacro defactor
  "Defines a new actor template."
  {:arglists '([name doc-string? attr-map? [params*] body])}
  [n & decl]
  (let [decl1 decl
        decl1 (if (string? (first decl1)) (next decl1) decl1) ; strip docstring
        decl1 (if (map? (first decl1)) (next decl1) decl1)    ; strip meta
        fs (first decl1)]
    (assert-args
      (vector? fs) "a vector for its binding")
    (if (seq fs)
      ; actor with state fields
      (let [fs1 (mapv #(merge-meta % {:unsynchronized-mutable true}) fs)
            body (next decl1)
            type (symbol (str (name n) "_type"))
            arity (count fs)
            args  (repeatedly arity gensym)]
        `(do
           (deftype ~type ~fs1
             clojure.lang.IFn
             (invoke [this#] ~@body)
             (applyTo [this# args#] (clojure.lang.AFn/applyToHelper this# args#)))
           (suspendable! ~type)
           (defn ~n [~@args]
             ((new ~type ~@args)))
           (let [sn# (suspendable! ~n)]
             (def ~n sn#)
             sn#)))
      ; regular actor
      `(do
         (defn ~n ~@decl)
         (let [sn# (suspendable! ~n)]
             (def ~n sn#)
             sn#)))))

(defmacro ->MailboxConfig 
  [size overflow-policy]
  `(co.paralleluniverse.actors.MailboxConfig. (int ~size) (keyword->enum co.paralleluniverse.strands.channels.Channels$OverflowPolicy ~overflow-policy)))

(defmacro spawn
  "Creates and starts a new actor"
  {:arglists '([:name? :mailbox-size? :overflow-policy? :lifecycle-handler? :stack-size? :pool? f & args])}
  [& args]
  (let [[{:keys [^String name ^Boolean trap ^Integer mailbox-size overflow-policy ^IFn lifecycle-handler ^Integer stack-size ^ForkJoinPool pool], :or {trap false mailbox-size -1 stack-size -1}} body] (kps-args args)]
    `(let [b#     (first ~body) ; eval body
           f#     (when (not (instance? LocalActor b#))
                    (suspendable! ~(if (== (count body) 1) (first body) `(fn [] (apply ~(first body) (list ~@(rest body)))))))
           ^LocalActor actor# (if (instance? LocalActor b#)
                                b#
                                (co.paralleluniverse.actors.PulsarActor. ~name ~trap (->MailboxConfig ~mailbox-size ~overflow-policy) ~lifecycle-handler f#))
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) actor#)]
       (.start fiber#)
       actor#)))

(defmacro spawn-link
  "Creates and starts a new actor, and links it to @self"
  {:arglists '([:name? :mailbox-size? :overflow-policy? :lifecycle-handler? :stack-size? :pool? f & args])}
  [& args]
  `(let [actor# ~(list `spawn ~@args)]
     (link! actor#)
     actor#))

(defmacro spawn-watch
  "Creates and starts a new actor, and makes @self monitor it"
  {:arglists '([:name? :mailbox-size? :overflow-policy? :lifecycle-handler? :stack-size? :pool? f & args])}
  [& args]
  `(let [actor# ~(list `spawn ~@args)]
     (watch! actor#)
     actor#))

(ann done? [LocalActor -> Boolean])
(defn done?
  "Tests whether or not an actor has terminated."
  [^LocalActor a]
  (.isDone a))

;(ann-protocol IUnifyWithLVar
;              unify-with-lvar [Term LVar ISubstitutions -> (U ISubstitutions Fail)])

(ann self (IDeref LocalActor))
(def self
  "@self is the currently running actor"
  (reify
    clojure.lang.IDeref
    (deref [_] (LocalActor/self))))

(ann state (IDeref Any))
(def state
  "@state is the state of the currently running actor"
  (reify
    clojure.lang.IDeref
    (deref [_] (PulsarActor/selfGetState))))

(ann mailbox (IDeref Channel))
(def mailbox
  "@mailbox is the mailbox channel of the currently running actor"
  (reify
    clojure.lang.IDeref
    (deref [_] (PulsarActor/selfMailbox))))

(ann set-state! (All [x] [x -> x]))
(defn set-state!
  "Sets the state of the currently running actor"
  [x]
  (PulsarActor/selfSetState x))

(ann trap! [-> nil])
(defn trap!
  "Sets the current actor to trap lifecycle events (like a dead linked actor) and turn them into messages"
  []
  (.setTrap ^PulsarActor @self true))

(ann get-actor [Any -> Actor])
(defn ^Actor get-actor
  "If the argument is an actor -- returns it. If not, looks up a registered actor with the argument as its name"
  [a]
  (when a
    (if (instance? Actor a)
      a
      (LocalActor/getActor a))))

(ann link! (Fn [Actor -> Actor]
               [Actor Actor -> Actor]))
(defn link!
  "links two actors"
  ([actor2]
   (.link ^LocalActor @self actor2))
  ([actor1 actor2]
   (.link ^LocalActor (cast LocalActor (get-actor actor1)) (get-actor actor2))))

(ann unlink! (Fn [Actor -> Actor]
                 [Actor Actor -> Actor]))
(defn unlink!
  "Unlinks two actors"
  ([actor2]
   (.unlink ^LocalActor @self actor2))
  ([actor1 actor2]
   (.unlink ^LocalActor (cast LocalActor (get-actor actor1)) (get-actor actor2))))

(ann monitor (Fn [Actor Actor -> LifecycleListener]
                 [Actor -> LifecycleListener]))
(defn watch!
  "Makes the current actor watch another actor. Returns a watch object which should be used when calling demonitor."
  [actor]
   (.watch ^LocalActor @self actor))

(ann monitor (Fn [Actor Actor LifecycleListener -> nil]
                 [Actor LifecycleListener -> nil]))
(defn unwatch!
  "Makes an actor stop watch another actor"
  ([actor2 monitor]
   (.unwatch ^LocalActor @self actor2 monitor))
  ([actor1 actor2 monitor]
   (.unwatch ^LocalActor (cast LocalActor (get-actor actor1)) (get-actor actor2) monitor)))

(ann register (Fn [String LocalActor -> LocalActor]
                  [LocalActor -> LocalActor]))
(defn register!
  "Registers an actor"
  ([name ^LocalActor actor]
   (.register actor name))
  ([^LocalActor actor]
   (.register actor)))

(ann unregister [LocalActor -> LocalActor])
(defn unregister!
  "Un-registers an actor"
  [x]
  (let [^LocalActor actor x]
    (.unregister actor)))

(ann mailbox-of [PulsarActor -> Channel])
(defn ^Channel mailbox-of
  [^PulsarActor actor]
  (.mailbox actor))

(ann whereis [Any -> Actor])
(defn ^Actor whereis
  "Returns a registered actor by name"
  [name]
  (ActorImpl/getActor name))

(ann maketag [-> Number])
(defn maketag
  "Returns a random, probably unique, identifier.
  (this is similar to Erlang's makeref)."
  []
  (ActorImpl/randtag))

(ann tagged-tuple? [Any -> Boolean])
(defn tagged-tuple?
  "Tests whether argument x is a vector whose first element is a keyword."
  [x]
  (and (vector? x) (keyword? (first x))))

(defn clojure->java-msg
  [x]
  (if (not (tagged-tuple? x))
    x
    (case (first x)
      :shutdown (ShutdownMessage. (second x))
      x)))

(defmacro !
  "Sends a message to an actor.
  This function returns nil."
  ([actor message]
   `(co.paralleluniverse.actors.PulsarActor/send (get-actor ~actor) (clojure->java-msg ~message)))
  ([actor arg & args]
   `(co.paralleluniverse.actors.PulsarActor/send (get-actor ~actor) (clojure->java-msg [~arg ~@args]))))

(defmacro !!
  "Sends a message to an actor synchronously.
  This has the exact same semantics as ! (in particular, this function always returns nil),
  but it hints the scheduler that the current actor is about to wait for a response from the message's addressee."
  ([actor message]
   `(co.paralleluniverse.actors.PulsarActor/sendSync (get-actor ~actor) (clojure->java-msg ~message)))
  ([actor arg & args]
   `(co.paralleluniverse.actors.PulsarActor/sendSync (get-actor ~actor) (clojure->java-msg [~arg ~@args]))))

(ann receive-timed [AnyInteger -> (Option Any)])
(defsusfn receive-timed
  "Waits (and returns) for a message for up to timeout ms. If time elapses -- returns nil."
  [^Integer timeout]
  (co.paralleluniverse.actors.PulsarActor/selfReceive timeout))

;; For examples of this macro's expansions, try:
;; (pprint (macroexpand-1 '(receive)))
;; (pprint (macroexpand-1 '(receive [:a] :hi :else :bye)))
;; (pprint (macroexpand-1 '(receive [msg] [:a] :hi :else :bye)))
;; (pprint (macroexpand-1 '(receive [msg #(* % %)] [:a] :hi :else :bye)))
;;
;; (pprint (macroexpand-1 '(receive :else m :after 30 :foo)))
;; (pprint (macroexpand-1 '(receive [m] :else m :after 30 :foo)))
;; (pprint (macroexpand-1 '(receive [:a] :hi :else :bye :after 30 :foo)))
;; (pprint (macroexpand-1 '(receive [msg] [:a] :hi :else :bye :after 30 :foo)))
;; (pprint (macroexpand-1 '(receive [msg #(* % %)] [:a] :hi :else :bye :after 30 :foo)))
;;
;; (pprint (macroexpand-1 '(receive [:a x] [:hi x] [:b x] [:bye x])))
;; (pprint (macroexpand-1 '(receive [:a x] [:hi x] [:b x] [:bye x] :after 30 :foo)))

(defmacro receive
  "Receives a message in the current actor and processes it"
  {:arglists '([]
               [patterns* <:after ms action>?]
               [[binding transformation?] patterns* <:after ms action>?])}
  ([]
   `(co.paralleluniverse.actors.PulsarActor/selfReceive))
  ([& body]
   (assert-args
     (or (even? (count body)) (vector? (first body))) "a vector for its binding")
   (let [[body after-clause] (if (= :after (nth-from-last body 2 nil)) (split-at-from-last 2 body) [body nil])
         odd-forms   (odd? (count body))
         bind-clause (if odd-forms (first body) nil)
         transform   (second bind-clause)
         body        (if odd-forms (next body) body)
         m           (if bind-clause (first bind-clause) (gensym "m"))
         timeout     (gensym "timeout")
         iter        (gensym "iter")]
     (if (seq (filter #(= % :else) (take-nth 2 body)))
       ; if we have an :else then every message is processed and our job is easy
       `(let ~(into [] (concat
                         (if after-clause `[~timeout ~(second after-clause)] [])
                         `[~m ~(concat `(co.paralleluniverse.actors.PulsarActor/selfReceive) (if after-clause `(~timeout) ()))]
                         (if transform `[~m (~transform ~m)] [])))
          ~@(surround-with (when after-clause `(if (nil? ~m) ~(nth after-clause 2)))
                           `(match ~m ~@body)))
       ; if we don't, well, we have our work cut out for us
       (let [pbody   (partition 2 body)
             mailbox (gensym "mailbox") n (gensym "n") m2 (gensym "m2") mtc (gensym "mtc") exp (gensym "exp")] ; symbols
         `(let [[~mtc ~m]
                (let ~(into [] (concat `[^co.paralleluniverse.actors.PulsarActor ~mailbox (co.paralleluniverse.actors.PulsarActor/self)]
                                       (if after-clause `[~timeout ~(second after-clause)
                                                          ~exp (if (pos? ~timeout) (long (+ (long (System/nanoTime)) (long (* 1000000 ~timeout)))) 0)] [])))
                  (.maybeSetCurrentStrandAsOwner ~mailbox)
                  (loop [prev# nil ~iter 0]
                    (.lock ~mailbox)
                    (let [~n (.succ ~mailbox prev#)]
                      ~(let [quick-match (concat ; ((pat1 act1) (pat2 act2)...) => (pat1 (do (.processed mailbox# n#) 0) pat2 (do (del mailbox# n#) 1)... :else -1)
                                           (mapcat #(list (first %1) `(do (.processed ~mailbox ~n) ~%2)) pbody (range)); for each match pattern, call processed and return an ordinal
                                           `(:else (do (.skipped ~mailbox ~n) -1)))]
                         `(if ~n
                            (do
                              (.unlock ~mailbox)
                              (let [m1# (.value ~mailbox ~n)]
                                (when (and (not (.isTrap ~mailbox)) (instance? co.paralleluniverse.actors.LifecycleMessage m1#))
                                  (.handleLifecycleMessage ~mailbox m1#))
                                (let [~m2 (co.paralleluniverse.actors.PulsarActor/convert m1#)
                                      ~m ~(if transform `(~transform ~m2) `~m2)
                                      act# (int (match ~m ~@quick-match))]
                                  (if (>= act# 0)
                                    [act# ~m]     ; we've got a match!
                                    (recur ~n (inc ~iter)))))) ; no match. try the next
                            ; ~n == nil
                            ~(if after-clause
                               `(when-not (== ~timeout 0)
                                  (do ; timeout != 0 and ~n == nil
                                    (try
                                      (.await ~mailbox ~iter (- ~exp (long (System/nanoTime))) java.util.concurrent.TimeUnit/NANOSECONDS)
                                      (finally
                                        (.unlock ~mailbox)))
                                    (when-not (> (long (System/nanoTime)) ~exp)
                                      (recur ~n (inc ~iter)))))
                               `(do
                                  (try
                                    (.await ~mailbox ~iter)
                                    (finally
                                      (.unlock ~mailbox)))
                                  (recur ~n (inc ~iter)))))))))]
            ~@(surround-with (when after-clause `(if (nil? ~mtc) ~(nth after-clause 2)))
                             ; now, mtc# is the number of the matching clause and m# is the message.
                             ; but the patterns might have wildcards so we need to match again (for the bindings)
                             `(case (int ~mtc) ~@(mapcat #(list %2 `(match [~m] [~(first %1)] ~(second %1))) pbody (range))))))))))
;`(match [~mtc ~m] ~@(mapcat #(list [%2 (first %1)] (second %1)) pbody (range))))))))))

(defn shutdown!
  "Asks a gen-server or a supervisor to shut down"
  ([^GenBehavior gs]
   (.shutdown gs))
  ([]
   (.shutdown ^GenBehavior @self)))

(defn ^Initializer ->Initializer 
  ([init terminate]
   (let [init      (when init (suspendable! init))
         terminate (when terminate (suspendable! terminate))]
     (reify
       Initializer
       (^void init [this]
              (when init (init)))
       (^void terminate  [this ^Throwable cause]
              (when terminate (terminate cause))))))
  ([init]
   (->Initializer init nil)))

(defmacro request!
  [actor & message]
  `(join (spawn (fn [] 
                  (! actor ~@message)
                  (receive)))))

(defmacro request-timed!
  [timeout actor & message]
  `(join (spawn (fn [] 
                  (! actor ~@message)
                  (receive-timed timeout)))))

(defn capitalize [s]
  {:no-doc true}
  (str/capitalize s))

(defmacro log
  [level message & args]
  `(let [^org.slf4j.Logger log# (.log ^BasicGenBehavior @self)]
     (if (. log# ~(symbol (str "is" (capitalize (name level)) "Enabled")))
       (. log# ~(symbol (name level)) ~message (to-array (vector ~@args))))))

;; ## gen-server

(defprotocol Server
  (init [this])
  (handle-call [this ^Actor from id message])
  (handle-cast [this ^Actor from id message])
  (handle-info [this message])
  (handle-timeout [this])
  (terminate [this ^Throwable cause]))

(suspendable! co.paralleluniverse.pulsar.actors.Server)

(defn ^co.paralleluniverse.actors.behaviors.Server Server->java
  {:no-doc true}
  [server]
  (suspendable! server [co.paralleluniverse.pulsar.actors.Server])
  (reify
    co.paralleluniverse.actors.behaviors.Server
    (^void init [this]
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
  {:arglists '([:name? :timeout? :mailbox-size? :overflow-policy? server & args])}
  [& args]
  (let [[{:keys [^String name ^Integer timeout ^Integer mailbox-size overflow-policy], :or {timeout -1 mailbox-size -1}} body] (kps-args args)]
    `(co.paralleluniverse.actors.behaviors.LocalGenServer. ~name
                                                           ^co.paralleluniverse.actors.behaviors.Server (Server->java ~(first body))
                                                           (long ~timeout) java.util.concurrent.TimeUnit/MILLISECONDS
                                                           nil (->MailboxConfig ~mailbox-size ~overflow-policy))))

(defn call!
  "Makes a synchronous call to a gen-server and returns the response"
  ([^GenServer gs m]
   (unwrap-exception
     (.call gs m)))
  ([^GenServer gs m & args]
   (unwrap-exception
     (.call gs (vec (cons m args))))))

(defn call-timed!
  "Makes a synchronous call to a gen-server and returns the response"
  ([^GenServer gs timeout unit m]
   (unwrap-exception
     (.call gs m (long timeout) (->timeunit unit))))
  ([^GenServer gs timeout unit m & args]
   (unwrap-exception
     (.call gs (vec (cons m args)) (long timeout) (->timeunit unit)))))

(defn cast!
  "Makes an asynchronous call to a gen-server"
  ([^GenServer gs m]
   (.cast gs m))
  ([^GenServer gs m & args]
   (.cast gs (vec (cons m args)))))

(defn set-timeout!
  "Sets the timeout for the current gen-server"
  [timeout unit]
  (.setTimeout (LocalGenServer/currentGenServer) timeout (->timeunit unit)))

(defn reply!
  "Replies to a message sent to the current gen-server"
  [^Actor to id res]
  (.reply (LocalGenServer/currentGenServer) to id res))

(defn reply-error!
  "Replies with an error to a message sent to the current gen-server"
  [^Actor to id ^Throwable error]
  (.replyError (LocalGenServer/currentGenServer) to id error))

;; ## gen-event

(defn gen-event
  "Creates (but doesn't start) a new gen-event"
  {:arglists '([:name? :timeout? :mailbox-size? :overflow-policy? server & args])}
  [& args]
  (let [[{:keys [^String name ^Integer mailbox-size overflow-policy], :or {mailbox-size -1}} body] (kps-args args)]
    (LocalGenEvent. name
                    (->Initializer (first body) (second body))
                    nil (->MailboxConfig mailbox-size overflow-policy))))

(deftype PulsarEventHandler
  [handler]
  EventHandler
  (handleEvent [this event]
               (handler event))
  Object
  (equals [this other]
          (and (instance? PulsarEventHandler other) (= handler (.handler ^PulsarEventHandler other)))))

(defn notify!
  [^GenEvent ge event]
  (.notify ge event))

(defn add-handler!
  [^GenEvent ge handler]
  (.addHandler ge (->PulsarEventHandler (suspendable! handler))))

(defn remove-handler!
  [^GenEvent ge handler]
  (.removeHandler ge (->PulsarEventHandler (suspendable! handler))))

;; ## supervisor

(defn- ^ActorBuilder actor-builder
  [f]
  (reify ActorBuilder
    (build [this]
           (f))))

(defn- ^LocalActor create-actor
  [& args]
  (let [[{:keys [^String name ^Boolean trap ^Integer mailbox-size overflow-policy ^IFn lifecycle-handler ^Integer stack-size ^ForkJoinPool pool], :or {trap false mailbox-size -1 stack-size -1}} body] (kps-args args)
        f  (when (not (instance? LocalActor body))
             (suspendable! (if (== (count body) 1)
                             (first body)
                             (fn [] (apply (first body) (rest body))))))]
    (if (instance? LocalActor body)
      body
      (PulsarActor. name trap (->MailboxConfig mailbox-size overflow-policy) lifecycle-handler f))))

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

(defsusfn add-child!
  "Adds an actor to a supervisor"
  [^Supervisor supervisor id mode max-restarts duration unit shutdown-deadline-millis & args]
  (.addChild supervisor (apply-variadic child-spec id mode max-restarts duration unit shutdown-deadline-millis args)))

(defsusfn remove-child!
  "Removes an actor from a supervisor"
  [^Supervisor supervisor id]
  (.removeChild supervisor id false))

(defn remove-and-terminate-child!
  "Removes an actor from a supervisor and terminates the actor"
  [^Supervisor supervisor id]
  (.removeChild supervisor id true))

(defn ^LocalActor get-child
  "Returns a supervisor's child by id"
  [^Supervisor sup id]
  (.getChild sup id))

(defn supervisor
  "Creates (but doesn't start) a new supervisor"
  ([^String name restart-strategy init]
   (LocalSupervisor. nil name nil
                     ^LocalSupervisor$RestartStrategy (keyword->enum LocalSupervisor$RestartStrategy restart-strategy)
                     (->Initializer
                       (fn [] (doseq [child (seq ((suspendable! init)))]
                                (apply add-child! (cons @self child)))))))
  ([restart-strategy init]
   (supervisor nil restart-strategy init)))


