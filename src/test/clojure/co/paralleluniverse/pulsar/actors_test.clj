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

(ns co.paralleluniverse.pulsar.actors-test
  (:use midje.sweet
        co.paralleluniverse.pulsar.core
        co.paralleluniverse.pulsar.actors)
  (:require [co.paralleluniverse.pulsar.lazyseq :as s :refer [channel->lazy-seq snd-seq]])
  (:import [java.util.concurrent TimeUnit TimeoutException ExecutionException]
           [co.paralleluniverse.common.util Debug]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.fibers Fiber]))

;; ## actors

(fact "When actor throws exception then join throws it"
      (let [actor (spawn #(throw (Exception. "my exception")))]
        (join actor))
      => (throws Exception "my exception"))

(fact "When actor returns a value then join returns it"
      (let [actor (spawn #(+ 41 1))]
        (join actor))
      => 42)

(fact "actor-receive"
      (fact "Test simple actor send/receive"
            (let [actor (spawn #(receive))]
              (! actor :abc)
              (join actor)) => :abc)
      (fact "Test receive after sleep"
            (let [actor
                  (spawn #(let [m1 (receive)
                                m2 (receive)]
                            (+ m1 m2)))]
              (! actor 13)
              (Thread/sleep 200)
              (! actor 12)
              (join actor)) => 25)
      (fact "When simple receive and timeout then return nil"
            (let [actor
                  (spawn #(let [m1 (receive-timed 50)
                                m2 (receive-timed 50)
                                m3 (receive-timed 50)]
                            [m1 m2 m3]))]
              (! actor 1)
              (Thread/sleep 20)
              (! actor 2)
              (Thread/sleep 100)
              (! actor 3)
              (join actor) => [1 2 nil])))

(fact "matching-receive"
      (fact "Test actor matching receive 1"
            (let [actor (spawn
                          #(receive
                             :abc "yes!"
                             :else "oy"))]
              (! actor :abc)
              (join actor)) => "yes!")
      (fact "Test actor matching receive 2"
            (let [actor (spawn
                          #(receive
                             :abc "yes!"
                             [:why? answer] answer
                             :else "oy"))]
              (! actor [:why? "because!"])
              (join actor)) => "because!")
      (fact "When matching receive and timeout then run :after clause"
            (let [actor
                  (spawn
                    #(receive
                       [:foo] nil
                       :else (println "got it!")
                       :after 30 :timeout))]
              (Thread/sleep 150)
              (! actor 1)
              (join actor)) => :timeout))

(fact "selective-receive"
      (fact "Test selective receive"
            (let [res (atom [])
                  actor (spawn
                          #(dotimes [i 2]
                             (receive
                               [:foo x] (do
                                          (swap! res conj x)
                                          (receive
                                            [:baz z] (swap! res conj z)))
                               [:bar y] (swap! res conj y)
                               [:baz z] (swap! res conj z))))]
              (! actor [:foo 1])
              (! actor [:bar 2])
              (! actor [:baz 3])
              (join actor)
              @res) => [1 3 2]))

(facts "actor-link"
       (fact "When an actor dies, its link gets an exception"
             (let [actor1 (spawn #(Fiber/sleep 100))
                   actor2 (spawn
                            #(try
                               (loop [] (receive [m] :foo :bar) (recur))
                               (catch co.paralleluniverse.actors.LifecycleException e
                                 true)))]
               (link! actor1 actor2)
               (join actor1)
               (join actor2)) => true)
       (fact "When an actor dies and lifecycle-handler is defined, then it gets a message"
             (let [actor1 (spawn #(Fiber/sleep 100))
                   actor2 (spawn :lifecycle-handler #(! @self [:foo (first %)])
                                 #(try
                                    (loop [] (receive [m]
                                                      [:foo x] x
                                                      :else (recur)))
                                    (catch co.paralleluniverse.actors.LifecycleException e nil)))]
               (link! actor1 actor2)
               (join actor1)
               (join actor2)) => :exit)
       (fact "When an actor dies, and its link traps, then its link gets a message"
             (let [actor1 (spawn #(Fiber/sleep 100))
                   actor2 (spawn :trap true
                                 (fn []
                                   (link! actor1)
                                   (receive [m]
                                            [:exit _ actor reason] actor)))]
               (join actor1)
               (fact (join actor2) => actor1))))

(fact "actor-watch"
      (fact "When an actor dies, its watch gets a message"
            (let [actor1 (spawn #(Fiber/sleep 200))
                  actor2 (spawn
                           (fn []
                             (let [w (watch! actor1)]
                               (receive
                                 [:exit w actor reason] actor))))]
              (join actor1)
              (join actor2) => actor1))
      (fact "When an actor dies, its watch gets a message2"
            (let [actor1 (spawn #(Fiber/sleep 200))
                  actor2 (spawn
                           (fn []
                             (let [w (watch! actor1)]
                               (receive
                                 [:exit w actor reason] actor))))]
              (join actor1)
              (fact (join actor2) => actor1))))

(facts "actor-state"
       (fact "Test recur actor-state"
             (let [actor
                   (spawn #(loop [i (int 2)
                                  state (int 0)]
                             (if (== i 0)
                               state
                               (recur (dec i) (+ state (int (receive)))))))]
               (! actor 13)
               (! actor 12)
               (join actor)) => 25)
       (fact "Test simple actor-state"
             (let [actor
                   (spawn #(do
                             (set-state! 0)
                             (set-state! (+ @state (receive)))
                             (set-state! (+ @state (receive)))
                             @state))]
               (! actor 13)
               (! actor 12)
               (join actor)) => 25)
       (fact "Test primitive actor-state"
             (let [actor (spawn (actor [^int sum 0]
                                       (set! sum (int (+ sum (receive))))
                                       (set! sum (int (+ sum (receive))))
                                       sum))]
               (! actor 13)
               (! actor 12)
               (join actor)) => 25))

(defsusfn f1 []
  (inc (receive)))

(defsusfn f2 [x]
  (+ x (receive)))

(defactor a1 []
  (inc (receive)))

(defactor a2 [^double x]
  (+ x (receive)))

(facts "spawn-syntax"
       (fact "Test spawn inline function"
             (let [actor
                   (spawn #(inc (receive)))]
               (! actor 41)
               (join actor)) => 42)
       (fact "Test spawn simple function"
             (let [actor
                   (spawn f1)]
               (! actor 41)
               (join actor)) => 42)
       (fact "Test spawn function with args"
             (let [actor
                   (spawn f2 5)]
               (! actor 37)
               (join actor)) => 42)
       (fact "Test spawn simple actor"
             (let [actor
                   (spawn a1)]
               (! actor 41)
               (join actor)) => 42)
       (fact "Test spawn simple actor with constructor args"
             (let [actor
                   (spawn a2 3.4)]
               (! actor 38.6)
               (join actor)) => 42.0))

(facts :selected "mailbox-seq"
       (fact :selected "Send and receive sequence (via @mailbox)"
             (let [actor (spawn #(s/doall (s/take 5 (channel->lazy-seq @mailbox))))]
               (snd-seq (mailbox-of actor) (take 10 (range)))
               (join actor)) => '(0 1 2 3 4))
       (fact "Map received sequence (via @mailbox)"
             (let [actor (spawn (fn [] (s/doall (s/map #(* % %) (s/take 5 (channel->lazy-seq @mailbox))))))]
               (snd-seq (mailbox-of actor) (take 10 (range)))
               (join actor)) => '(0 1 4 9 16))
       (fact "Filter received sequence (via @mailbox)"
             (let [actor (spawn #(s/doall (s/filter even? (s/take 5 (channel->lazy-seq @mailbox)))))]
               (snd-seq (mailbox-of actor) (take 10 (range)))
               (join actor)) => '(0 2 4)))

(fact "strampoline-test"
      (fact "Test trampolining actor"
            (let [state2 (susfn []
                                (receive
                                  :bar :foobar))
                  state1 (susfn []
                                (receive
                                  :foo state2))
                  actor (spawn (fn []
                                 (strampoline state1)))]
              (! actor :foo)
              (Thread/sleep 50)
              (! actor :bar)
              (join actor)) => :foobar)
      (fact "Test trampolining actor with selctive receive"
            (let [state2 (susfn []
                                (receive
                                  :bar :foobar))
                  state1 (susfn []
                                (receive
                                  :foo state2))
                  actor (spawn (fn []
                                 (strampoline state1)))]
              (! actor :bar)
              (Thread/sleep 50)
              (! actor :foo)
              (join actor)) => :foobar))

;; ## gen-server

(fact "When gen-server starts then init is called"
      (let [called (atom false)
            gs (spawn
                 (gen-server (reify Server
                               (init [_]
                                     (reset! called true)
                                     (shutdown!))
                               (terminate [_ cause]))))]
        (join gs)
        @called) => true)

(fact "When no messages then handle-timeout is called"
      (let [times (atom 0)
            gs (spawn
                 (gen-server :timeout 20
                             (reify Server
                               (init [_])
                               (handle-timeout [_]
                                               (if (< @times 5)
                                                 (swap! times inc)
                                                 (shutdown!)))
                               (terminate [_ cause]))))]
        (fact
          (join 50 :ms gs) => (throws TimeoutException))
        (join 200 :ms gs)
        @times) => 5)

(fact "Server dies on shutdown"
      (let [times (atom 0)
            gs (spawn
                 (gen-server (reify Server
                               (init [_])
                               (terminate [_ cause]
                                          (fact cause => nil)))))]
        (fact
          (join 50 :ms gs) => (throws TimeoutException))
        (shutdown! gs)
        (join gs)) => nil)


(facts "gen-server call"
       (fact "When gen-server call then result is returned"
             (let [gs (spawn
                        (gen-server (reify Server
                                      (init [_])
                                      (terminate [_ cause])
                                      (handle-call [_ from id [a b]]
                                                   (+ a b)))))]
               (call gs 3 4) => 7))
       (fact "When gen-server call then result is returned (with sleep)"
             (let [gs (spawn
                        (gen-server (reify Server
                                      (init [_])
                                      (terminate [_ cause])
                                      (handle-call [_ from id [a b]]
                                                   (Strand/sleep 50)
                                                   (+ a b)))))]
               (call gs 3 4) => 7))
       (fact "When gen-server call from fiber then result is returned"
             (let [gs (spawn
                        (gen-server (reify Server
                                      (init [_])
                                      (terminate [_ cause])
                                      (handle-call [_ from id [a b]]
                                                   (+ a b)))))
                   fib (spawn-fiber #(call gs 3 4))]
               (join fib) => 7))
       (fact "When gen-server call from fiber then result is returned (with sleep)"
             (let [gs (spawn
                        (gen-server (reify Server
                                      (init [_])
                                      (terminate [_ cause])
                                      (handle-call [_ from id [a b]]
                                                   (Strand/sleep 50)
                                                   (+ a b)))))
                   fib (spawn-fiber #(call gs 3 4))]
               (join fib) => 7))
       (fact "When gen-server call from actor then result is returned"
             (let [gs (spawn (gen-server
                               (reify Server
                                 (init [_])
                                 (terminate [_ cause])
                                 (handle-call [_ from id [a b]]
                                              (+ a b)))))
                   actor (spawn #(call gs 3 4))]
               (join actor) => 7))
       (fact "When handle-call throws exception then call throws it"
             (let [gs (spawn
                        (gen-server (reify Server
                                      (init [_])
                                      (terminate [_ cause])
                                      (handle-call [_ from id [a b]]
                                                   (throw (Exception. "oops!"))))))]
               (call gs 3 4) => (throws Exception "oops!"))))

(fact "when gen-server doesn't respond then timeout"
      (let [gs (spawn
                 (gen-server (reify Server
                               (init [_])
                               (terminate [_ cause])
                               (handle-call [_ from id [a b]]
                                            (Strand/sleep 100)
                                            (+ a b)))))]
        (call-timed gs 10 :ms 3 4) => (throws TimeoutException)))

(fact "when reply is called return value in call"
      (let [info (atom {})
            gs (spawn
                 (gen-server :timeout 50
                             (reify Server
                               (init [_])
                               (terminate [_ cause])
                               (handle-call [_ from id [a b]]
                                            (swap! info assoc :a a :b b :from from :id id)
                                            nil)
                               (handle-timeout [_]
                                               (let [{:keys [a b from id]} @info]
                                                 (when id
                                                   (reply from id (+ a b))))))))]
        (fact
          (call-timed gs 10 :ms 3 4) => (throws TimeoutException))
        (call-timed gs 100 :ms 5 6)) => 11)


(fact "When cast then handle-cast is called"
      (let [res (atom nil)
            gs (spawn
                 (gen-server (reify Server
                               (init [_])
                               (terminate [_ cause])
                               (handle-cast [_ from id [a b]]
                                            (reset! res (+ a b))))))]
        (cast gs 3 4)
        (shutdown! gs)
        (join gs)
        @res => 7))

(fact "Messages sent to a gen-server are passed to handle-info"
      (let [res (atom nil)
            gs (spawn
                 (gen-server (reify Server
                               (init [_])
                               (terminate [_ cause])
                               (handle-info [_ m]
                                            (reset! res m)))))]
        (! gs :hi)
        (shutdown! gs)
        (join gs)
        @res => :hi))

(fact "When handle-info throws exception, terminate is called with the cause"
      (let [gs (spawn
                 (gen-server (reify Server
                               (init [_])
                               (terminate [_ cause]
                                          (fact cause => (every-checker #(instance? Exception %) #(= (.getMessage %) "oops!"))))
                               (handle-info [_ m]
                                            (throw (Exception. "oops!"))))))]
        (! gs :hi)
        (join gs)) => (throws Exception "oops!"))

;; ## gen-event

(unfinished handler1 handler2)

(fact "When notify gen-event then call handlers"
      (let [ge (spawn (gen-event
                        #(add-handler @self handler1)))]
        (add-handler ge handler2)
        (notify ge "hello")
        (shutdown! ge)
        (join ge)) => nil
      (provided 
        (handler1 "hello") => nil
        (handler2 "hello") => nil))

(fact "When handler is removed then don't call it"
      (let [ge (spawn (gen-event
                        #(add-handler @self handler1)))]
        (add-handler ge handler2)
        (Strand/sleep 50)
        (remove-handler ge handler1)
        (notify ge "hello")
        (shutdown! ge)
        (join ge)) => nil
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

(defsusfn actor1 []
  (loop [i (int 0)]
    (receive
      [:shutdown a] i
      :else (recur (inc i)))))

(defsusfn bad-actor1 []
  (receive)
  (throw (RuntimeException. "Ha!")))


(fact "child-modes"
      (fact "When permanent actor dies of natural causes then restart"
            (let [sup (spawn
                        (supervisor :one-for-one
                                    #(list ["actor1" :permanent 5 1 :sec 10 actor1])))]
              (doseq [res [3 5]]
                (let [a (sup-child sup "actor1" 200)]
                  (dotimes [i res]
                    (! a :hi!))
                  (! a :shutdown nil)
                  (fact
                    (join a) => res)))
              (shutdown! sup)
              (join sup)))
      (fact "When permanent actor dies of un-natural causes then restart"
            (let [sup (spawn
                        (supervisor :one-for-one
                                    #(list ["actor1" :permanent 5 1 :sec 10 bad-actor1])))]
              (dotimes [i 2]
                (let [a (sup-child sup "actor1" 300)]
                  (! a :hi!)
                  (fact
                    (join a) => throws Exception)))
              (shutdown! sup)
              (join sup)))
      (fact "When transient actor dies of natural causes then don't restart"
            (let [sup (spawn
                        (supervisor :one-for-one
                                    #(list ["actor1" :transient 5 1 :sec 10 actor1])))]
              (let [a (sup-child sup "actor1" 200)]
                (dotimes [i 3]
                  (! a :hi!))
                (! a :shutdown nil)
                (fact
                  (join a) => 3))
              (fact (sup-child sup "actor1" 200) => nil)
              (shutdown! sup)
              (join sup)))
      (fact "When transient actor dies of un-natural causes then restart"
            (let [sup (spawn
                        (supervisor :one-for-one
                                    #(list ["actor1" :transient 5 1 :sec 10 bad-actor1])))]
              (dotimes [i 2]
                (let [a (sup-child sup "actor1" 200)]
                  (! a :hi!)
                  (fact
                    (join a) => throws Exception)))
              (shutdown! sup)
              (join sup)))
      (fact "When temporary actor dies of natural causes then don't restart"
            (let [sup (spawn
                        (supervisor :one-for-one
                                    #(list ["actor1" :temporary 5 1 :sec 10 actor1])))]
              (let [a (sup-child sup "actor1" 200)]
                (dotimes [i 3]
                  (! a :hi!))
                (! a :shutdown nil)
                (fact
                  (join a) => 3))
              (fact (sup-child sup "actor1" 200) => nil)
              (shutdown! sup)
              (join sup)))
      (fact "When temporary actor dies of un-natural causes then don't restart"
            (let [sup (spawn
                        (supervisor :one-for-one
                                    #(list ["actor1" :temporary 5 1 :sec 10 bad-actor1])))]
              (let [a (sup-child sup "actor1" 200)]
                (! a :hi!)
                (fact
                  (join a) => throws Exception))
              (fact (sup-child sup "actor1" 200) => nil)
              (shutdown! sup)
              (join sup))))

(fact "When a child dies too many times then give up and die"
      (let [sup (spawn
                  (supervisor :one-for-one
                              #(list ["actor1" :permanent 3 1 :sec 10 bad-actor1])))]
        (dotimes [i 4]
          (let [a (sup-child sup "actor1" 200)]
            (! a :hi!)
            (fact
              (join a) => throws Exception)))
        (join sup)))


(defsusfn actor3
  [sup started terminated]
  (let [adder
        (add-child sup nil :temporary 5 1 :sec 10
                   (gen-server ; or (spawn (gen-server...))
                               (reify Server
                                 (init        [_]       (swap! started inc))
                                 (terminate   [_ cause] (swap! terminated inc))
                                 (handle-call [_ from id [a b]]
                                              (log :debug "=== a: {} b: {}" a b)
                                              (let [res (+ a b)]
                                                (if (> res 100)
                                                  (throw (Exception. "oops!"))
                                                  res))))))
        a (receive)
        b (receive)]
    (call adder a b)))

(fact "Complex example test1"
      (let [prev       (atom nil)
            started    (atom 0)
            terminated (atom 0)
            sup (spawn
                  (supervisor :all-for-one
                              #(list ["actor1" :permanent 5 1 :sec 10
                                      :name "koko" ; ... and any other optional parameter accepted by spawn
                                      actor3 @self started terminated])))]
        (let [a (sup-child sup "actor1" 200)]
          (! a 3)
          (! a 4)
          (fact 
            (join a) => 7)
          (reset! prev a))
        (let [a (sup-child sup "actor1" 200)]
          (fact
            (identical? a @prev) => false)
          (! a 70)
          (! a 80)
          (fact
            (join a) => throws Exception)
          (reset! prev a))
        (let [a (sup-child sup "actor1" 200)]
          (fact (identical? a @prev) => false)
          (! a 7)
          (! a 8)
          (fact
            (join a) => 15))
        (Strand/sleep 2000) ; give the actor time to start the gen-server
        (shutdown! sup)
        (join sup)
        [@started @terminated]) => [4 4])
