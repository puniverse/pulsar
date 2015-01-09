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

(ns co.paralleluniverse.pulsar.actors-test
  (:use midje.sweet
        co.paralleluniverse.pulsar.core
        co.paralleluniverse.pulsar.actors)
;  (:require [co.paralleluniverse.pulsar.lazyseq :as s :refer [channel->lazy-seq]])
  (:refer-clojure :exclude [promise await bean])
  (:import [java.util.concurrent TimeUnit TimeoutException ExecutionException]
           [co.paralleluniverse.common.util Debug]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.fibers Fiber]
           (co.paralleluniverse.actors LifecycleException PulsarActor Actor)
           (clojure.lang IFn)))

(defmacro spawn-thread-actor
  "Creates and starts a new actor running in its own, newly-spawned thread.

  f - the actor function, or an actor created with actor, gen-server etc..
  args - (optional) arguments to for the function.

  If `f` is an actor rather than an actor function, the optional parameters will be ignored.

  Options:
  * `:name` - The actor's name (that's also given to the thread running the actor). The name can be a string
              or a keyword, in which case it's identical to the keyword's name (i.e. a name of `\"foo\"` is the same as `:foo`).
  * `:mailbox-size` - The number of messages that can wait in the mailbox,
                      or -1 (the default) for an unbounded mailbox.
  * `:overflow-policy` - What to do if a bounded mailbox overflows. Can be one of:
     - `:throw` - an exception will be thrown *into the receiving actor*
     - `:drop`  -  the message will be silently discarded
     - `:block` - the sender will block until there's room in the mailbox.
  * `:trap` - If set to `true`, linked actors' death will send an exit message rather than throw an exception.
  * `:lifecycle-handler` - A function that will be called to handle special messages sent to the actor.
                           If set to `nil` (the default), the default handler is used, which is what you
                           want in all circumstances, except for some actors that are meant to do some
                           special tricks."
  {:arglists '([:name? :mailbox-size? :overflow-policy? :trap? :lifecycle-handler? f & args])}
  [& args]
  (let [[{:keys [^String name
                 ^Integer mailbox-size
                 ^Boolean trap
                 overflow-policy
                 ^IFn lifecycle-handler],
          :or {trap false mailbox-size -1}} body] (kps-args args)
        b   (gensym 'b)    ; Using 'gensym' as autogen syms (e.g. 'sym#') seem not to behave as desired in unquote
        cls (gensym 'cls)]
    `(let [args#  (list ~@(rest body))     ; eval once all args
           ~b     (first ~body)            ; eval once the function
           ~cls   (fn [] (apply ~b args#)) ; => "call-by-value"-like behaviour for spawned function when args are being
                                           ;    passed (so that e.g. arguments containing @self are correctly evaluated)
           nme#   (when ~name (clojure.core/name ~name))
           f#     (when (not (instance? Actor ~b))
                    (suspendable! ~(if (== (count body) 1) b cls)))
           ^Actor actor# (if (instance? Actor ~b)
                           ~b
                           (PulsarActor. nme#
                                         ~b
                                         ~trap
                                         (->MailboxConfig ~mailbox-size ~overflow-policy)
                                         ~lifecycle-handler f#))]
       (.spawnThread actor#)
       (.ref actor#))))

;; ## actors

(fact "The spawn macro will evaluate arguments by value"
      (let [a (spawn-thread-actor
                     #(do
                       (spawn (fn [parent] (! parent :something)) @self)
                       (receive [m] :something m :after 1000 nil)))]
        (join a))
      => :something)

(fact "When actor throws exception then join throws it"
      (let [f #(throw (Exception. "my exception"))
            fiber-actor (spawn f)
            thread-actor (spawn-thread-actor f)
            action #(join %)
            effect (throws Exception "my exception")]
        (fact "Fiber" (action fiber-actor) => effect)
        (fact "Thread" (action thread-actor) => effect)))

(fact "When actor returns a value then join returns it"
      (let [f #(+ 41 1)
            fiber-actor (spawn f)
            thread-actor (spawn-thread-actor f)
            action #(join %)
            effect 42]
        (fact "Fiber" (action fiber-actor) => effect)
        (fact "Thread" (action thread-actor) => effect)))

(fact "actor-receive"
      (fact "Test simple actor send/receive"
            (let [f #(receive)
                  fiber-actor (spawn f)
                  thread-actor (spawn-thread-actor f)]
              (fact "Fiber"
                    (! fiber-actor :abc)
                    (join fiber-actor) => :abc)
              (fact "Thread"
                    (! thread-actor :abc)
                    (join thread-actor) => :abc)))
      (fact "Test receive after sleep"
            (let [f #(let [m1 (receive)
                           m2 (receive)]
                      (+ m1 m2))
                  fiber-actor (spawn f)
                  thread-actor (spawn-thread-actor f)
                  action #(do (! % 13)
                              (Thread/sleep 200)
                              (! % 12)
                              (join %))
                  effect 25]
              (fact "Fiber" (action fiber-actor) => effect)
              (fact "Thread" (action thread-actor) => effect)))
      (fact "When simple receive and timeout then return nil"
            (let [f #(let [m1 (receive-timed 50)
                          m2 (receive-timed 50)
                          m3 (receive-timed 50)]
                     [m1 m2 m3])
                  action #(do (! % 1)
                              (Thread/sleep 20)
                              (! % 2)
                              (Thread/sleep 100)
                              (! % 3)
                              (join %))
                  effect [1 2 nil]]
              (fact "Fiber" (let [fiber-actor (spawn f)] (action fiber-actor) => effect))
              (fact "Thread"(let [thread-actor (spawn-thread-actor f)] (action thread-actor) => effect)))))

(fact "matching-receive"
      (fact "Test actor matching receive 1"
            (let [f #(receive
                      :abc "yes!"
                      :else "oy")
                  fiber-actor (spawn f)
                  thread-actor (spawn-thread-actor f)
                  action #(do (! % :abc)
                              (join %))
                  effect "yes!"]
              (fact "Fiber" (action fiber-actor) => effect)
              (fact "Thread" (action thread-actor) => effect)))
      (fact "Test actor matching receive 2"
            (let [f #(receive
                      :abc "yes!"
                      [:why? answer] answer
                      :else "oy")
                  fiber-actor (spawn f)
                  thread-actor (spawn-thread-actor f)
                  action #(do (! % [:why? "because!"])
                              (join %))
                  effect "because!"]
              (fact "Fiber" (action fiber-actor) => effect)
              (fact "Thread" (action thread-actor) => effect)))
      (fact "Test actor matching receive 3"
            (let [f #(receive
                      [x y] (+ x y))
                  fiber-actor (spawn f)
                  thread-actor (spawn-thread-actor f)
                  action #(do (! % [2 3])
                              (join %))
                  effect 5]
              (fact "Fiber" (action fiber-actor) => effect)
              (fact "Thread" (action thread-actor) => effect)))
      (fact "When matching receive and timeout then run :after clause"
            (let [f #(receive
                    [:foo] nil
                    :else (println "got it!")
                    :after 30 :timeout)
                  fiber-actor (spawn f)
                  thread-actor (spawn-thread-actor f)
                  action #(do (Thread/sleep 150)
                              (! % 1)
                              (join %))
                  effect :timeout]
              (fact "Fiber" (action fiber-actor) => effect)
              (fact "Thread" (action thread-actor) => effect))))

(fact "selective-receive"
      (fact "Test selective receive1"
            (let [res (atom nil)
                  f #(dotimes [_ 2]
                                (receive
                                  [:foo x] (do
                                             (swap! res conj x)
                                             (receive
                                               [:baz z] (swap! res conj z)))
                                  [:bar y] (swap! res conj y)
                                  [:baz z] (swap! res conj z)))
                  fiber-actor (spawn f)
                  thread-actor (spawn-thread-actor f)
                  action #(do (reset! res [])
                              (! % [:foo 1])
                              (! % [:bar 2])
                              (! % [:baz 3])
                              (join %)
                               @res)
                  effect [1 3 2]]
              (fact "Fiber" (action fiber-actor) => effect)
              (fact "Thread" (action thread-actor) => effect)))
      (fact "Test selective ping pong (mixed thread-fiber)"
            (let [f1 #(receive
                            [from m] (! from @self (str m "!!!")))
                  fiber-actor1 (spawn f1) ; same as (! from [@self (str m "!!!")])
                  f2 #(do
                       (! fiber-actor1 @self (receive)) ; same as (! actor1 [@self (receive)])
                       (receive
                         [fiber-actor1 res] res))
                  thread-actor2 (spawn-thread-actor f2)]
              (! thread-actor2 "hi")
              (join thread-actor2)) => "hi!!!"))

(facts "actor-link"
       (fact "When an actor dies, its link gets an exception (mixed thread-fiber)"
             (let [f1 #(Fiber/sleep 100)
                   fiber-actor1 (spawn f1)
                   f2 #(try
                        (loop [] (receive [m] :foo :bar) (recur))
                        (catch LifecycleException e true))
                   thread-actor2 (spawn-thread-actor f2)]
               (link! fiber-actor1 thread-actor2)
               (join fiber-actor1)
               (join thread-actor2)) => true)
       (fact "When an actor dies and lifecycle-handler is defined, then it gets a message (mixed thread-fiber)"
             (let [f1 #(Strand/sleep 100)
                   thread-actor1 (spawn-thread-actor f1)
                   f2 #(try
                        (loop [] (receive [m]
                                          [:foo x] x
                                          :else (recur)))
                        (catch LifecycleException e nil))
                   fiber-actor2 (spawn :lifecycle-handler #(! @self [:foo (first %)]) f2)]
               (link! thread-actor1 fiber-actor2)
               (join thread-actor1)
               (join fiber-actor2)) => :exit)
       (fact "When an actor dies, and its link traps, then its link gets a message (mixed thread-fiber)"
             (let [f1 #(Strand/sleep 100)
                   fiber-actor1 (spawn f1)
                   f2 #(do
                        (link! fiber-actor1)
                        (receive [m]
                                 [:exit _ actor reason] actor))
                   thread-actor2 (spawn-thread-actor :trap true f2)]
               (join fiber-actor1)
               (fact (join thread-actor2) => fiber-actor1))))

(fact "actor-watch"
      (fact "When an actor dies, its watch gets a message (mixed thread-fiber)"
            (let [f1 #(Strand/sleep 200)
                  thread-actor1 (spawn-thread-actor f1)
                  f2 #(let [w (watch! thread-actor1)]
                         (receive
                           [:exit w actor reason] actor))
                  fiber-actor2 (spawn f2)]
              (join thread-actor1)
              (join fiber-actor2) => thread-actor1))
      (fact "When an actor dies, its watch gets a message - 2 (mixed thread-fiber)"
            (let [f1 #(Fiber/sleep 200)
                  fiber-actor1 (spawn f1)
                  f2 #(let [w (watch! fiber-actor1)]
                        (receive
                          [:exit w actor reason] actor))
                  thread-actor2 (spawn-thread-actor f2)]
              (join fiber-actor1)
              (fact (join thread-actor2) => fiber-actor1))))

(facts "actor-state"
       (let [action #(do (! % 13)
                         (! % 12)
                         (join %))
             effect 25]
         (fact "Test recur actor-state"
               (let [f #(loop [i (int 2) state (int 0)]
                         (if (== i 0)
                           state
                           (recur (dec i) (+ state (int (receive))))))
                     fiber-actor (spawn f)
                     thread-actor (spawn-thread-actor f)]
                 (fact "Fiber" (action fiber-actor) => effect)
                 (fact "Thread" (action thread-actor) => effect)))
         (fact "Test simple actor-state"
               (let [f #(do
                         (set-state! 0)
                         (set-state! (+ @state (receive)))
                         (set-state! (+ @state (receive)))
                         @state)
                     fiber-actor (spawn f)
                     thread-actor (spawn-thread-actor f)]
                 (fact "Fiber" (action fiber-actor) => effect)
                 (fact "Thread" (action thread-actor) => effect)))
         (fact "Test primitive actor-state"
               (let [actor (actor [^int sum 0]
                                  (set! sum (int (+ sum (receive))))
                                  (set! sum (int (+ sum (receive))))
                                  sum)
                     fiber-actor (spawn actor)
                     thread-actor (spawn-thread-actor actor)]
                 (fact "Fiber" (action fiber-actor) => effect)
                 (fact "Thread" (action thread-actor) => effect)))))

(defsfn f1 [] (inc (receive)))

(defsfn f2 [x] (+ x (receive)))

(defactor a1f [] (inc (receive)))
(defactor a1t [] (inc (receive)))

(defactor a2f [^double x] (+ x (receive)))
(defactor a2t [^double x] (+ x (receive)))

(facts "spawn-syntax"
       (let [
             action #(do (! % 41)
                         (join %))
             effect 42]
         (fact "Test spawn inline function"
               (let [fiber-actor (spawn #(inc (receive)))
                     thread-actor (spawn-thread-actor #(inc (receive)))]
                 (fact "Fiber" (action fiber-actor) => effect)
                 (fact "Thread" (action thread-actor) => effect)))
         (fact "Test spawn simple function"
               (let [fiber-actor (spawn f1)
                     thread-actor (spawn-thread-actor f1)]
                 (fact "Fiber" (action fiber-actor) => effect)
                 (fact "Thread" (action thread-actor) => effect)))
         (fact "Test spawn simple actor"
               (let [fiber-actor (spawn a1f)
                     thread-actor (spawn-thread-actor a1t)]
                 (fact "Fiber" (action fiber-actor) => effect)
                 (fact "Thread" (action thread-actor) => effect))))
       (fact "Test spawn function with args"
             (let [fiber-actor (spawn f2 5)
                   thread-actor (spawn-thread-actor f2 5)
                   action #(do (! % 37)
                               (join %))
                   effect 42]
               (fact "Fiber" (action fiber-actor)=> effect)
               (fact "Thread" (action thread-actor) => effect)))
       (fact "Test spawn simple actor with constructor args"
             (let [fiber-actor (spawn a2f 3.4)
                   thread-actor (spawn-thread-actor a2t 3.4)
                   action #(do (! % 38.6)
                               (join %))
                   effect 42.0]
               (fact "Fiber" (action fiber-actor)=> effect)
               (fact "Thread" (action thread-actor) => effect))))

;(facts "mailbox-seq"
;       (fact "Send and receive sequence (via @mailbox)"
;             (let [actor (spawn #(doall (take 5 (channel->lazy-seq @mailbox))))]
;               (snd-seq (mailbox-of actor) (take 10 (range)))
;               (join actor)) => '(0 1 2 3 4))
;       (fact "Map received sequence (via @mailbox)"
;             (let [actor (spawn (fn [] (doall (map #(* % %) (take 5 (channel->lazy-seq @mailbox))))))]
;               (snd-seq (mailbox-of actor) (take 10 (range)))
;               (join actor)) => '(0 1 4 9 16))
;       (fact "Filter received sequence (via @mailbox)"
;             (let [actor (spawn #(s/doall (filter even? (take 5 (channel->lazy-seq @mailbox)))))]
;               (snd-seq (mailbox-of actor) (take 10 (range)))
;               (join actor)) => '(0 2 4)))

(fact "strampoline-test"
      (fact "Test trampolining actor"
            (let [state2 (sfn [] (receive :bar :foobar))
                  state1 (sfn [] (receive :foo state2))
                  f (fn [] (strampoline state1))
                  fiber-actor (spawn f)
                  thread-actor (spawn-thread-actor f)
                  action #(do (! % :foo)
                              (Thread/sleep 50)
                              (! % :bar)
                              (join %))
                  effect :foobar]
              (fact "Fiber" (action fiber-actor)=> effect)
              (fact "Thread" (action thread-actor) => effect)))
      (fact "Test trampolining actor with selective receive"
            (let [state2 (sfn [] (receive :bar :foobar))
                  state1 (sfn [] (receive :foo state2))
                  f (fn [] (strampoline state1))
                  fiber-actor (spawn f)
                  thread-actor (spawn-thread-actor f)
                  action #(do (! % :bar)
                              (Thread/sleep 50)
                              (! % :foo)
                              (join %))
                  effect :foobar]
              (fact "Fiber" (action fiber-actor)=> effect)
              (fact "Thread" (action thread-actor) => effect))))

;; ## gen-server

(fact "When gen-server starts then init is called"
      (let [actor #(gen-server (reify Server
                                (init [_]
                                  (reset! % true)
                                  (shutdown!))
                                (terminate [_ cause])))
            fiber-called (atom false)
            thread-called (atom true)
            fiber-gs (spawn (actor fiber-called))
            thread-gs (spawn-thread-actor (actor thread-called))
            action #(do (join %1)
                        @%2)
            effect true]
        (fact "Fiber" (action fiber-gs fiber-called)=> effect)
        (fact "Thread" (action thread-gs thread-called) => effect)))

(fact "When no messages then handle-timeout is called"
      (let [
            actor #(gen-server :timeout 20
                              (reify Server
                                (init [_])
                                (handle-timeout [_]
                                  (if (< @% 5)
                                    (swap! % inc)
                                    (shutdown!)))
                                (terminate [_ cause])))
            fiber-times (atom 0)
            thread-times (atom 0)
            fiber-gs (spawn (actor fiber-times))
            thread-gs (spawn-thread-actor (actor thread-times))
            action1 #(join 50 :ms %)
            effect1 (throws TimeoutException)
            action2 #(do (join 200 :ms %1)
                         @%2)
            effect2 5]
        (fact "Fiber"
              (fact (action1 fiber-gs) => effect1)
              (fact (action2 fiber-gs fiber-times) => effect2))
        ; TODO understand Exception in thread "main" java.lang.IllegalArgumentException: nanosecond timeout value out of range at java.lang.Thread.join(Thread.java:1292) at co.paralleluniverse.strands.Strand$ThreadStrand.join(Strand.java:1013)
        #_(fact "Thread"
              (fact (action1 thread-gs) => effect1)
              (fact (action2 thread-gs thread-times) => effect2))))

(fact "Server dies on shutdown"
      (let [actor #(gen-server (reify Server
                                (init [_])
                                (terminate [_ cause]
                                           (fact cause => nil))))
            fiber-gs (spawn (actor))
            thread-gs (spawn-thread-actor (actor))
            action1 #(join 50 :ms %)
            effect1 (throws TimeoutException)
            action2 #(do (shutdown! %)
                         (join %))
            effect2 nil]
        (fact "Fiber" (fact (action1 fiber-gs) => effect1)
              (action2 fiber-gs) => effect2)
        ; TODO breaks, understand Exception in thread "main" java.lang.IllegalArgumentException: nanosecond timeout value out of range at java.lang.Thread.join(Thread.java:1292) at co.paralleluniverse.strands.Strand$ThreadStrand.join(Strand.java:1013)
        #_(fact "Thread" (fact (action1 thread-gs) => effect1)
              (action2 thread-gs) => effect2)))

(facts "gen-server call!"
       (fact "When gen-server call! then result is returned"
             (let [actor #(gen-server (reify Server
                                        (init [_])
                                        (terminate [_ cause])
                                        (handle-call [_ from id [a b]]
                                          (+ a b))))
                   fiber-gs (spawn (actor))
                   thread-gs (spawn-thread-actor (actor))
                   action #(call! % 3 4)
                   effect 7]
               (fact "Fiber" (action fiber-gs) => effect)
               (fact "Thread" (action thread-gs) => effect)))
       (fact "When gen-server call! then result is returned (with sleep)"
             (let [actor #(gen-server (reify Server
                                        (init [_])
                                        (terminate [_ cause])
                                        (handle-call [_ from id [a b]]
                                          (Strand/sleep 50)
                                          (+ a b))))
                   fiber-gs (spawn (actor))
                   thread-gs (spawn-thread-actor (actor))
                   action #(call! % 3 4)
                   effect 7]
               (fact "Fiber" (action fiber-gs) => effect)
               (fact "Thread" (action thread-gs) => effect)))
       (fact "When gen-server call! from strand then result is returned (mixed thread-fiber)"
             (let [actor #(gen-server (reify Server
                                        (init [_])
                                        (terminate [_ cause])
                                        (handle-call [_ from id [a b]]
                                          (+ a b))))
                   fiber-gs (spawn (actor))
                   thread-gs (spawn-thread-actor (actor))
                   strand-fn #(fn [] (call! % 3 4))
                   thr #(spawn-thread (strand-fn %))
                   fib #(spawn-fiber (strand-fn %))
                   action #(join (%1 %2))
                   effect 7]
               (fact "Fiber joining fiber server actor" (action fib fiber-gs) => effect)
               ; TODO breaks => nil
               #_(fact "Thread joining thread server actor" (action thr thread-gs) => effect)
               (fact "Fiber joining thread server actor" (action fib thread-gs) => effect)
               ; TODO breaks => nil
               #_(fact "Thread joining fiber server actor" (action thr fiber-gs) => effect)))
       (fact "When gen-server call! from strand then result is returned (with sleep, mixed thread-fiber)"
             (let [actor #(gen-server (reify Server
                                        (init [_])
                                        (terminate [_ cause])
                                        (handle-call [_ from id [a b]]
                                          (Strand/sleep 50)
                                          (+ a b))))
                   strand-fn #(fn [] (call! % 3 4))
                   fiber-gs (spawn (actor))
                   thread-gs (spawn-thread-actor (actor))
                   thr #(spawn-thread (strand-fn %))
                   fib #(spawn-fiber (strand-fn %))
                   action #(join (%1 %2))
                   effect 7]
               (fact "Fiber joining fiber server actor" (action fib fiber-gs) => effect)
               ; TODO breaks => nil
               #_(fact "Thread joining thread server actor" (action thr thread-gs) => effect)
               (fact "Fiber joining thread server actor" (action fib thread-gs) => effect)
               ; TODO breaks => nil
               #_(fact "Thread joining fiber server actor" (action thr fiber-gs) => effect)))
       (fact "When gen-server call! from actor then result is returned (mixed thread-fiber)"
             (let [actor #(gen-server
                            (reify Server
                              (init [_])
                              (terminate [_ cause])
                              (handle-call [_ from id [a b]]
                                (+ a b))))
                   strand-f #(call! % 3 4)
                   fiber-gs (spawn (actor))
                   thread-gs (spawn-thread-actor (actor))
                   fiber-actor #(spawn strand-f %)
                   thread-actor #(spawn-thread-actor strand-f %)
                   action #(join (%1 %2))
                   effect 7]
               (fact "Fiber actor joining fiber server actor" (action fiber-actor fiber-gs) => effect)
               (fact "Thread actor joining thread server actor" (action thread-actor thread-gs) => effect)
               (fact "Fiber actor joining thread server actor" (action fiber-actor thread-gs) => effect)
               (fact "Thread actor joining fiber server actor" (action thread-actor fiber-gs) => effect)))
       (fact "When handle-call throws exception then call! throws it"
             (let [actor #(gen-server (reify Server
                                        (init [_])
                                        (terminate [_ cause])
                                        (handle-call [_ from id [a b]]
                                                     (throw (Exception. "oops!")))))
                   fiber-gs (spawn (actor))
                   thread-gs (spawn-thread-actor (actor))
                   action #(call! % 3 4)
                   effect (throws Exception "oops!")]
               (fact "Fiber" (action fiber-gs) => effect)
               (fact "Thread" (action thread-gs) => effect))))

(fact "when gen-server doesn't respond then timeout"
      (let [actor #(gen-server (reify Server
                                 (init [_])
                                 (terminate [_ cause])
                                 (handle-call [_ from id [a b]]
                                   (Strand/sleep 100)
                                   (+ a b))))
            fiber-gs (spawn (actor))
            thread-gs (spawn-thread-actor (actor))
            action #(call-timed! % 10 :ms 3 4)
            effect (throws TimeoutException)]
        (fact "Fiber" (action fiber-gs) => effect)
        (fact "Thread" (action thread-gs) => effect)))

(fact "when reply! is called return value in call!"
      (let [actor #(gen-server :timeout 50
                               (reify Server
                                 (init [_]
                                   (set-state! {}))
                                 (terminate [_ cause])
                                 (handle-call [_ from id [a b]]
                                   (set-state! (assoc @state :a a :b b :from from :id id))
                                   nil)
                                 (handle-timeout [_]
                                   (let [{:keys [a b from id]} @state]
                                     (when id
                                       (reply! from id (+ a b))
                                       (set-state! nil))))))
            fiber-gs (spawn (actor))
            thread-gs (spawn-thread-actor (actor))
            action1 #(call-timed! % 10 :ms 3 4)
            effect1 (throws TimeoutException)
            action2 #(call-timed! % 100 :ms 5 6)
            effect2 11]
        (fact "Fiber"
              (fact (action1 fiber-gs) => effect1)
              (fact (action2 fiber-gs) => effect2))
        (fact "Thread"
              (fact (action1 thread-gs) => effect1)
              (fact (action2 thread-gs) => effect2))))

(fact "When cast! then handle-cast is called"
      (let [actor #(gen-server (reify Server
                                 (init [_])
                                 (terminate [_ cause])
                                 (handle-cast [_ from id [a b]]
                                              (reset! % (+ a b)))))
            fiber-res (atom nil)
            thread-res (atom nil)
            fiber-gs (spawn (actor fiber-res))
            thread-gs (spawn (actor thread-res))
            action #(do (cast! %1 3 4)
                        (shutdown! %1)
                        (join %1)
                        @%2)
            effect 7]
        (fact "Fiber" (action fiber-gs fiber-res) => effect)
        (fact "Thread" (action thread-gs thread-res) => effect)))

(fact "Messages sent to a gen-server are passed to handle-info"
      (let [actor #(gen-server (reify Server
                                 (init [_])
                                 (terminate [_ cause])
                                 (handle-info [_ m]
                                   (reset! % m))))
            fiber-res (atom nil)
            thread-res (atom nil)
            fiber-gs (spawn (actor fiber-res))
            thread-gs (spawn-thread-actor (actor thread-res))
            action #(do (! %1 :hi)
                        (shutdown! %1)
                        (join %1)
                        @%2)
            effect :hi]
        (fact "Fiber" (action fiber-gs fiber-res) => effect)
        (fact "Thread" (action thread-gs thread-res) => effect)))

(fact "When handle-info throws exception, terminate is called with the cause"
      (let [actor (fn [] (gen-server (reify Server
                                       (init [_])
                                       (terminate [_ cause]
                                         (fact cause => (every-checker #(instance? Exception %) #(= (.getMessage ^Exception %) "oops!"))))
                                       (handle-info [_ m]
                                         (throw (Exception. "oops!"))))))
            fiber-gs (spawn (actor))
            thread-gs (spawn-thread-actor (actor))
            action #(do (! % :hi)
                        (join %))
            effect (throws Exception "oops!")]
        (fact "Fiber" (action fiber-gs) => effect)
        (fact "Thread" (action thread-gs) => effect)))

(fact "Test receive in handle-call (mixed thread-fiber)"
      (Debug/dumpAfter 5000 "foo.log")
      (let [f #(receive [from m] (! from @self (str m "!!!")))
            fiber-actor (spawn f)
            thread-actor (spawn-thread-actor f)
            actor #(gen-server (reify Server
                                 (init [_])
                                 (terminate [_ cause])
                                 (handle-call [_ from id [a b]]
                                   (! % @self (+ a b))
                                   (receive
                                     [% m] m))))
            fiber-gs #(spawn (actor %))
            thread-gs #(spawn-thread-actor (actor %))
            action #(call! (%1 %2) 3 4)
            effect "7!!!"]
        (fact "Fiber actor server receiving from fiber actor" (action fiber-gs fiber-actor) => effect)
        (fact "Thread actor server receiving from thread actor" (action thread-gs thread-actor) => effect)
        ; TODO hang
        #_(fact "Fiber actor server receiving from thread actor" (action fiber-gs thread-actor) => effect)
        #_(fact "Thread actor server receiving from fiber actor" (action thread-gs fiber-actor) => effect)))

;; ## gen-event

(unfinished handler1 handler2)

(fact "When notify gen-event then call handlers"
      (let [actor #(gen-event (fn [] (add-handler! @self %1)))
            fiber-ge #(spawn (actor %1))
            thread-ge #(spawn-thread-actor (actor %1))
            action #(do (add-handler! %1 %2)
                        (notify! %1 "hello")
                        (shutdown! %1)
                        (join %1))]
        [(action (fiber-ge handler1) handler2)
         (action (thread-ge handler1) handler2)]) => [nil, nil]
      (provided
        (handler1 "hello") => nil
        (handler2 "hello") => nil))

(fact "When handler is removed then don't call it"
      (let [actor #(gen-event (fn [] (add-handler! @self %1)))
            fiber-ge #(spawn (actor %1))
            thread-ge #(spawn (actor %1))
            action #(do (add-handler! %1 %2)
                        (Strand/sleep 50)
                        (remove-handler! %1 %3)
                        (notify! %1 "hello")
                        (shutdown! %1)
                        (join %1))]
        [(action (fiber-ge handler1) handler2 handler1)
         (action (thread-ge handler1) handler2 handler1)]) => [nil, nil]
      (provided
        (handler1 anything) => irrelevant :times 0
        (handler2 "hello") => nil))

;; ## supervisor

;; This is cumbersome, but we don't normally obtain an actor from a supervisor, but rather
;; manually add a known actor to a supervisor, so this ugly function is only useful for tests anyway.

(defn- sup-child
  [sup id timeout]
  (when (pos? timeout)
    (let [a (get-child sup id)]
      (if (and a (not (done? a)))
        a
        (do
          (Thread/sleep 10)
          (recur sup id (- timeout 10)))))))

(defsfn actor1 []
  (loop [i (int 0)]
    (receive
      [:shutdown a] i
      :else (recur (inc i)))))

(defsfn bad-actor1 []
  (receive)
  (throw (RuntimeException. "Ha!")))


(fact "child-modes"
      (fact "When permanent actor dies of natural causes then restart"
            (let [actor (supervisor :one-for-one
                                    #(list ["actor1" :permanent 5 1 :sec 10 actor1]))
                  fiber-sup (spawn actor)]
              (doseq [res [3 5]]
                (let [a (sup-child fiber-sup "actor1" 200)]
                  (dotimes [i res]
                    (! a :hi!))
                  (! a :shutdown nil)
                  (fact
                    (join a) => res)))
              (shutdown! fiber-sup)
              (join fiber-sup)))
      (fact "When permanent actor dies of un-natural causes then restart"
            (let [actor (supervisor :one-for-one
                                    #(list ["actor1" :permanent 5 1 :sec 10 bad-actor1]))
                  fiber-sup (spawn actor)]
              (dotimes [i 2]
                (let [a (sup-child fiber-sup "actor1" 300)]
                  (! a :hi!)
                  (fact
                    (join a) => throws Exception)))
              (shutdown! fiber-sup)
              (join fiber-sup)))
      (fact "When transient actor dies of natural causes then don't restart"
            (let [actor (supervisor :one-for-one
                                    #(list ["actor1" :transient 5 1 :sec 10 actor1]))
                  fiber-sup (spawn actor)]
              (let [a (sup-child fiber-sup "actor1" 200)]
                (dotimes [i 3]
                  (! a :hi!))
                (! a :shutdown nil)
                (fact
                  (join a) => 3))
              (fact (sup-child fiber-sup "actor1" 200) => nil)
              (shutdown! fiber-sup)
              (join fiber-sup)))
      (fact "When transient actor dies of un-natural causes then restart"
            (let [actor (supervisor :one-for-one
                                    #(list ["actor1" :transient 5 1 :sec 10 bad-actor1]))
                  fiber-sup (spawn actor)]
              (dotimes [i 2]
                (let [a (sup-child fiber-sup "actor1" 200)]
                  (! a :hi!)
                  (fact
                    (join a) => throws Exception)))
              (shutdown! fiber-sup)
              (join fiber-sup)))
      (fact "When temporary actor dies of natural causes then don't restart"
            (let [actor (supervisor :one-for-one
                                    #(list ["actor1" :temporary 5 1 :sec 10 actor1]))
                  fiber-sup (spawn actor)]
              (let [a (sup-child fiber-sup "actor1" 200)]
                (dotimes [i 3]
                  (! a :hi!))
                (! a :shutdown nil)
                (fact
                  (join a) => 3))
              (fact (sup-child fiber-sup "actor1" 200) => nil)
              (shutdown! fiber-sup)
              (join fiber-sup)))
      (fact "When temporary actor dies of un-natural causes then don't restart"
            (let [actor (supervisor :one-for-one
                                    #(list ["actor1" :temporary 5 1 :sec 10 bad-actor1]))
                  fiber-sup (spawn actor)]
              (let [a (sup-child fiber-sup "actor1" 200)]
                (! a :hi!)
                (fact
                  (join a) => throws Exception))
              (fact (sup-child fiber-sup "actor1" 200) => nil)
              (shutdown! fiber-sup)
              (join fiber-sup))))

(fact "When a child dies too many times then give up and die"
      (let [actor (supervisor :one-for-one
                        #(list ["actor1" :permanent 3 1 :sec 10 bad-actor1]))
            fiber-sup (spawn actor)]
        (dotimes [i 4]
          (let [a (sup-child fiber-sup "actor1" 200)]
            (! a :hi!)
            (fact
              (join a) => throws Exception)))
        (join fiber-sup)))

(defsfn actor3
  [sup started terminated]
  (let [actor (gen-server ; or (spawn (gen-server...))
                (reify Server
                  (init        [_]       (swap! started inc))
                  (terminate   [_ cause] (swap! terminated inc))
                  (handle-call [_ from id [a b]]
                    (log :debug "=== a: {} b: {}" a b)
                    (let [res (+ a b)]
                      (if (> res 100)
                        (throw (Exception. "oops!"))
                        res)))))
        adder (add-child! sup nil :temporary 5 1 :sec 10 actor)
        a (receive)
        b (receive)]
    (call! adder a b)))

(fact :selected "Complex example test1"
      (let [prev       (atom nil)
            started    (atom 0)
            terminated (atom 0)
            actor (supervisor :all-for-one
                              #(list ["actor1" :permanent 5 1 :sec 10
                                      :name "koko" ; ... and any other optional parameter accepted by spawn
                                      actor3 @self started terminated]))
            fiber-sup (spawn actor)]
        (let [a (sup-child fiber-sup "actor1" 200)]
          (! a 3)
          (! a 4)
          (fact :selected
            (join a) => 7)
          (reset! prev a))
        (let [a (sup-child fiber-sup "actor1" 200)]
          (fact :selected
            (identical? a @prev) => false)
          (! a 70)
          (! a 80)
          (fact :selected
            (join a) => throws Exception)
          (reset! prev a))
        (let [a (sup-child fiber-sup "actor1" 200)]
          (fact (identical? a @prev) => false)
          (! a 7)
          (! a 8)
          (fact :selected
            (join a) => 15))
        (Strand/sleep 2000) ; give the actor time to start the gen-server
        (shutdown! fiber-sup)
        (join fiber-sup)
        [@started @terminated]) => [4 4])