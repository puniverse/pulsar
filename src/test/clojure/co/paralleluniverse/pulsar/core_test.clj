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

(ns co.paralleluniverse.pulsar.core-test
  (:use midje.sweet
        co.paralleluniverse.pulsar.core)
  (:require [co.paralleluniverse.pulsar.lazyseq :as s :refer [channel->lazy-seq snd-seq]]
            [midje.checking.core :as checking])
  (:import [java.util.concurrent TimeUnit TimeoutException ExecutionException]
           [co.paralleluniverse.common.util Debug]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.fibers Fiber]))


;; ## fibers

(fact "fiber-timeout"
      (fact "When join and timeout then throw exception"
            (let [fib (spawn-fiber #(Fiber/park 100 TimeUnit/MILLISECONDS))]
              (join 2 :ms fib))
            => (throws TimeoutException))
      (fact "When join and no timeout then join"
            (let [fib (spawn-fiber
                        #(do
                           (Fiber/park 100 TimeUnit/MILLISECONDS)
                           15))]
              (join 200 :ms fib))
            => 15))

(fact "When fiber throws exception then join throws that exception"
      (let [fib (spawn-fiber #(throw (Exception. "my exception")))]
        (join fib))
      => (throws Exception "my exception"))

(fact "When fiber interrupted while sleeping then InterruptedException thrown"
      (let [fib (spawn-fiber
                  #(try
                     (Fiber/sleep 100)
                     false
                     (catch InterruptedException e
                       true)))]
        (Thread/sleep 20)
        (.interrupt fib)
        (join fib)) => true)

(def ^:dynamic *foo* 40)

(facts "fiber-bindings"
       (fact "Fiber inherits thread bindings"
             (let [fiber
                   (binding [*foo* 20]
                     (spawn-fiber
                       #(let [v1 *foo*]
                          (Fiber/sleep 200)
                          (let [v2 *foo*]
                            (+ v1 v2)))))]
               (join fiber))
             => 40)
       (fact "Bindings declared in fiber last throughout fiber lifetime"
             (let [fiber
                   (spawn-fiber
                     #(binding [*foo* 15]
                        (let [v1 *foo*]
                          (Fiber/sleep 200)
                          (let [v2 *foo*]
                            (+ v1 v2)))))]
               (join fiber))
             => 30))

(fact "Fiber can be used turned into a future"
      (let [fiber (spawn-fiber
                    (fn []
                      (Strand/sleep 20)
                      42))
            fut (fiber->future fiber)]
        (fact @fut => 42)
        (fact (realized? fut) => true)))

;; ## channels

(fact "Test channel close"
      (let [ch (channel)
            fiber (spawn-fiber
                    (fn []
                      (let [m1 (rcv ch)
                            m2 (rcv ch)
                            m3 (rcv ch)
                            m4 (rcv ch)]
                        (fact
                          (closed? ch) => true)
                        (list m1 m2 m3 m4))))]
        (Thread/sleep 20)
        (snd ch "m1")
        (Thread/sleep 20)
        (snd ch "m2")
        (Thread/sleep 20)
        (snd ch "m3")
        (close! ch)
        (snd ch "m4")
        (join fiber))  => '("m1" "m2" "m3" nil))


(facts "promises-promises"
       (fact "When try to set promise twice, then throw exception"
             (let [v (promise)]
               (deliver v "hi!")
               (deliver v "bye!")) => throws IllegalStateException)
       (fact "This complex promises test passes"
             (let [v1 (promise)
                   v2 (promise)
                   v3 (promise)
                   v4 (promise)
                   f1 (spawn-fiber  #(deliver v2 (+ @v1 1)))
                   t1 (spawn-thread #(deliver v3 (+ @v1 @v2)))
                   f2 (spawn-fiber  #(deliver v4 (+ @v3 @v2)))]
               (Strand/sleep 50)
               (deliver v1 1)
               (join [f1 f2 t1])
               (fact
                 (mapv realized? [v1 v2 v3 v4]) => [true true true true])
               @v4) => 5)
       (fact "Test promise functions"
             (let [v0 (promise)
                   v1 (promise)
                   v2 (promise #(+ @v1 1))
                   v3 (promise #(+ @v1 @v2))
                   v4 (promise #(* (+ @v3 @v2) @v0))]
               (Strand/sleep 50)
               (deliver v1 1)
               (fact @v3 => 3)
               (fact
                 (mapv realized? [v0 v1 v2 v3 v4]) => [false true true true false])
               (deliver v0 2)
               @v4) => 10))

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

(facts "topics"
       (fact "When channel subscribes to topic then it receives its messages"
             (let [ch1 (channel)
                   ch2 (channel)
                   ch3 (channel)
                   topic (topic)
                   f1 (spawn-fiber #(list (rcv ch1) (rcv ch1) (rcv ch1)))
                   f2 (spawn-fiber #(list (rcv ch2) (rcv ch2) (rcv ch2)))
                   f3 (spawn-fiber #(list (rcv ch3) (rcv ch3) (rcv ch3)))]
               (subscribe topic ch1)
               (subscribe topic ch2)
               (subscribe topic ch3)
               (Thread/sleep 20)
               (snd topic "m1")
               (Thread/sleep 20)
               (snd topic "m2")
               (Thread/sleep 20)
               (snd topic "m3")
               (fact 
                 (join f1) => '("m1" "m2" "m3"))
               (fact 
                 (join f2) => '("m1" "m2" "m3"))
               (fact 
                 (join f3) => '("m1" "m2" "m3"))))
       (fact "When channel unsubscribes from topic then it stops receiving messages"
             (let [ch1 (channel)
                   ch2 (channel)
                   ch3 (channel)
                   topic (topic)
                   f1 (spawn-fiber #(list (rcv ch1 500 :ms) (rcv ch1 500 :ms) (rcv ch1 500 :ms)))
                   f2 (spawn-fiber #(list (rcv ch2 500 :ms) (rcv ch2 500 :ms) (rcv ch2 500 :ms)))
                   f3 (spawn-fiber #(list (rcv ch3 500 :ms) (rcv ch3 500 :ms) (rcv ch3 500 :ms)))]
               (subscribe topic ch1)
               (subscribe topic ch2)
               (subscribe topic ch3)
               ;(Thread/sleep 5)
               (snd topic "m1")
               (Thread/sleep 5)
               (snd topic "m2")
               (unsubscribe topic ch2)
               (Thread/sleep 5)
               (snd topic "m3")
               (fact 
                 (join f1) => '("m1" "m2" "m3"))
               (fact 
                 (join f2) => '("m1" "m2" nil))
               (fact 
                 (join f3) => '("m1" "m2" "m3")))))

(defn gt? [y]
  #(or (> % y)  (checking/as-data-laden-falsehood {:notes [(str "actual " % " required > " y)]})))

(fact "When multiple consumers receive from ticker-channel then each consumer's messages are monotonic"
      (let [ch (ticker-channel 10)
            task (susfn [] 
                        (let [ch (ticker-consumer ch)]
                          (loop [prev -1]
                            (let [m (rcv ch)]
                              (when m
                                (assert (> m prev)) ;(fact m => (gt? prev))
                                (recur m))))))
            f1 (spawn-fiber task)
            t1 (spawn-thread task)
            f2 (spawn-fiber task)
            t2 (spawn-thread task)
            f3 (spawn-fiber task)
            t3 (spawn-thread task)
            f4 (spawn-fiber task)
            t4 (spawn-thread task)]
        (Thread/sleep 100)
        (dotimes [i 1000]
          (Thread/sleep 1)
          (snd ch i))
        (close! ch)
        (join (list f1 t1 f2 t2 f3 t3 f4 t4))
        :ok) => :ok)

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
