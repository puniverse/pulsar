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

(ns co.paralleluniverse.pulsar-test
  (:use midje.sweet
        co.paralleluniverse.pulsar)
  (:require [co.paralleluniverse.pulsar.lazyseq :as s :refer [channel->lazy-seq snd-seq]])
  (:import [java.util.concurrent TimeUnit]
           [co.paralleluniverse.common.util Debug]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.fibers Fiber FiberInterruptedException TimeoutException]))


;; ## fibers

(fact "fiber-timeout"
      (fact "When join and timeout then throw exception"
            (let [fib (spawn-fiber #(Fiber/park 100 TimeUnit/MILLISECONDS))]
              (join fib 2 TimeUnit/MILLISECONDS))
            => (throws java.util.concurrent.TimeoutException))
      (fact "When join and no timeout then join"
            (let [fib (spawn-fiber
                       #(do
                          (Fiber/park 100 TimeUnit/MILLISECONDS)
                          15))]
              (join fib 200 TimeUnit/MILLISECONDS))
            => 15))

(fact "When fiber throws exception then join throws that exception"
      (let [fib (spawn-fiber #(throw (Exception. "my exception")))]
        (join fib))
      => (throws java.util.concurrent.ExecutionException #(= "my exception" (-> % .getCause .getCause .getMessage))))

(fact "When fiber interrupted while sleeping then InterruptedException thrown"
      (let [fib (spawn-fiber
                 #(try
                    (Fiber/sleep 100)
                    false
                    (catch FiberInterruptedException e
                      true)))]
        (Thread/sleep 20)
        (.interrupt fib)
        (join fib)) => true)

;; ## channels

(facts "channel-group"
       (fact "Receive from channel group"
             (let [ch1 (channel)
                   ch2 (channel)
                   ch3 (channel)
                   grp (channel-group ch1 ch2 ch3)
                   fiber (spawn-fiber
                          (fn []
                            (let [m1 (rcv grp)
                                  m2 (rcv ch2)
                                  m3 (rcv grp)]
                              (list m1 m2 m3))))]
               (Thread/sleep 20)
               (snd ch1 "hello")
               (Thread/sleep 20)
               (snd ch3 "foo")
               (Thread/sleep 20)
               (snd ch2 "world!")
               (join fiber))  => '("hello" "world!" "foo"))
       (fact "Receive from channel group with timeout"
             (let [ch1 (channel)
                   ch2 (channel)
                   ch3 (channel)
                   grp (channel-group ch1 ch2 ch3)
                   fiber (spawn-fiber
                          (fn []
                            (let [m1 (rcv grp)
                                  m2 (rcv grp 10 :ms)
                                  m3 (rcv grp 100 :ms)]
                              (list m1 m2 m3))))]
               (Thread/sleep 20)
               (snd ch1 "hello")
               (Thread/sleep 100)
               (snd ch3 "world!")
               (join fiber))  => '("hello" nil "world!")))

(facts "channels-seq"
       (fact "Receive sequence with sleep"
             (let [ch (channel)
                   fiber (spawn-fiber
                          (fn []
                            (let [s (s/take 5 (channel->lazy-seq ch))]
                              (s/doall s))))]
               (dotimes [m 10]
                 (Thread/sleep 20)
                 (snd ch m))
               (join fiber))  => '(0 1 2 3 4))
       (fact "Map received sequence with sleep"
             (let [ch (channel)
                   fiber (spawn-fiber (fn [] (s/doall (s/map #(* % %) (s/take 5 (channel->lazy-seq ch))))))]
               (dotimes [m 10]
                 (Thread/sleep 20)
                 (snd ch m))
               (join fiber)) => '(0 1 4 9 16))
       (fact "Filter received sequence with sleep (odd)"
             (let [ch (channel)
                   fiber (spawn-fiber
                          #(s/doall (s/filter odd? (s/take 5 (channel->lazy-seq ch)))))]
               (dotimes [m 10]
                 (Thread/sleep 20)
                 (snd ch m))
               (join fiber)) => '(1 3))
       (fact "Filter received sequence with sleep (even)"
             (let [ch (channel)
                   fiber (spawn-fiber
                          #(s/doall (s/filter even? (s/take 5 (channel->lazy-seq ch)))))]
               (dotimes [m 10]
                 (Thread/sleep 20)
                 (snd ch m))
               (join fiber)) => '(0 2 4))
       (fact "Filter and map received sequence with sleep (even)"
             (let [ch (channel)
                   fiber (spawn-fiber
                          (fn [] (s/doall
                                  (s/filter #(> % 10)
                                            (s/map #(* % %)
                                                   (s/filter even?
                                                             (s/take 5 (channel->lazy-seq ch))))))))]
               (dotimes [m 10]
                 (Thread/sleep 20)
                 (snd ch m))
               (join fiber)) => '(16))
       (fact "Send and receive sequence"
             (let [ch (channel)
                   fiber (spawn-fiber #(s/doall (s/take 5 (channel->lazy-seq ch))))]
               (snd-seq ch (take 10 (range)))
               (join fiber)) => '(0 1 2 3 4))
       (fact "Map received sequence"
             (let [ch (channel)
                   fiber (spawn-fiber (fn [] (s/doall (s/map #(* % %) (s/take 5 (channel->lazy-seq ch))))))]
               (snd-seq ch (take 10 (range)))
               (join fiber)) => '(0 1 4 9 16))
       (fact "Filter received sequence"
             (let [ch (channel)
                   fiber (spawn-fiber #(s/doall (s/filter even? (s/take 5 (channel->lazy-seq ch)))))]
               (snd-seq ch (take 10 (range)))
               (join fiber)) => '(0 2 4)))

;; ## actors


(fact "When fiber throws exception then join throws it"
      (let [actor (spawn #(throw (Exception. "my exception")))]
        (join actor))
      => (throws Exception #(= "my exception" (-> % .getCause .getCause .getMessage))))

(fact "When actor returns a value then join returns it"
      (let [actor (spawn #(+ 41 1))]
        (join actor))
      => 42)


(def ^:dynamic *foo* 40)

(facts "actor-bindings"
      (fact "Fiber inherits thread bindings"
            (let [actor
                  (binding [*foo* 20]
                    (spawn
                     #(let [v1 *foo*]
                        (Fiber/sleep 200)
                        (let [v2 *foo*]
                          (+ v1 v2)))))]
              (join actor))
            => 40)
      (fact "Bindings declared in fiber last throughout fiber lifetime"
            (let [actor
                  (spawn
                   #(binding [*foo* 15]
                      (let [v1 *foo*]
                        (Fiber/sleep 200)
                        (let [v2 *foo*]
                          (+ v1 v2)))))]
              (join actor))
            => 30))

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
                        #(receive [m]
                                  [:exit _ actor reason] actor))]
      (link! actor1 actor2)
      (join actor1)
      (fact (join actor2) => actor1))))

(fact "actor-monitor"
  (fact "When an actor dies, its monitor gets a message"
    (let [actor1 (spawn #(Fiber/sleep 200))
          actor2 (spawn
                  #(receive
                    [:exit monitor actor reason] monitor))
          mon (monitor! actor2 actor1)]
      (join actor1)
      (fact (join actor2) => mon))))

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

(facts "mailbox-seq"
       (fact "Send and receive sequence (via @mailbox)"
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
