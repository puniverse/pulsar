; Pulsar: lightweight threads and Erlang-like actors for Clojure.
; Copyright (C) 2013-2015, Parallel Universe Software Co. All rights reserved.
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
  (:require [midje.checking.core :as checking])
  (:refer-clojure :exclude [promise await])
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

(fact "simple test"
      (let [f (fn [a b] (Fiber/sleep 20) (+ a b))
            fib (spawn-fiber f 3 4)]
        (join fib))
      => 7)

(fact "When fiber interrupted while sleeping then InterruptedException thrown"
      (let [fib (spawn-fiber
                  #(try
                     (sleep 100)
                     false
                     (catch InterruptedException e
                       true)))]
        (sleep 20)
        (.interrupt fib)
        (join fib)) => true)

(def ^:dynamic *foo* 40)

(facts "fiber-bindings"
       (fact "Fiber inherits thread bindings"
             (let [fiber
                   (binding [*foo* 20]
                     (spawn-fiber
                       #(let [v1 *foo*]
                          (sleep 200)
                          (let [v2 *foo*]
                            (+ v1 v2)))))]
               (join fiber))
             => 40)
       (fact "Bindings declared in fiber last throughout fiber lifetime"
             (let [fiber
                   (spawn-fiber
                     #(binding [*foo* 15]
                        (let [v1 *foo*]
                          (sleep 200)
                          (let [v2 *foo*]
                            (+ v1 v2)))))]
               (join fiber))
             => 30))

(fact "fiber->future can be used to turn a fiber into a future"
      (let [fiber (spawn-fiber
                    (fn []
                      (sleep 20)
                      42))
            fut (fiber->future fiber)]
        (fact @fut => 42)
        (fact (realized? fut) => true)))

(fact "fiber can be used to execute a whole block in a newly created fiber"
      (let [fiber (fiber
                    (sleep 20)
                    0)]
        (fact (join fiber) => 0)))

(fact "await blocks the fiber and returns the value passed to the callback"
      (let [exec (java.util.concurrent.Executors/newSingleThreadExecutor)
            service (fn [a b clbk]
                      (.execute exec ^Runnable (fn []
                                                 (sleep 50)
                                                 (clbk (+ a b)))))
            fiber (spawn-fiber
                    (fn []
                      (await service 2 5)))]
        (join fiber) => 7))

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
        (sleep 20)
        (snd ch "m1")
        (sleep 20)
        (snd ch "m2")
        (sleep 20)
        (snd ch "m3")
        (close! ch)
        (snd ch "m4")
        (join fiber))  => '("m1" "m2" "m3" nil))

(fact "Test snd-seq and rcv-into"
      (let [ch (channel)
            fiber (spawn-fiber #(rcv-into [] ch 1000))]
        (snd-seq ch (range 5))
        (close! ch)
        (join fiber)) => [0 1 2 3 4])

(facts "promises-promises"
       (let [v (promise)]
         (fact "When try to set promise twice, then return nil and the first value wins"
               [(deliver v "hi!")
                (deliver v "bye!")
                (deref v)] => [v nil "hi!"]))
       (fact "This complex promises test passes"
             (let [v1 (promise)
                   v2 (promise)
                   v3 (promise)
                   v4 (promise)
                   f1 (spawn-fiber  #(deliver v2 (+ @v1 1)))
                   t1 (spawn-thread #(deliver v3 (+ @v1 @v2)))
                   f2 (spawn-fiber  #(deliver v4 (+ @v3 @v2)))]
               (sleep 50)
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
               (sleep 50)
               (deliver v1 1)
               (fact @v3 => 3)
               (fact
                 (mapv realized? [v0 v1 v2 v3 v4]) => [false true true true false])
               (deliver v0 2)
               @v4) => 10))

(facts "topics"
       (fact "When channel subscribes to topic then it receives its messages"
             (let [ch1 (channel)
                   ch2 (channel)
                   ch3 (channel)
                   topic (topic)
                   f1 (spawn-fiber #(list (rcv ch1) (rcv ch1) (rcv ch1)))
                   f2 (spawn-fiber #(list (rcv ch2) (rcv ch2) (rcv ch2)))
                   f3 (spawn-fiber #(list (rcv ch3) (rcv ch3) (rcv ch3)))]
               (subscribe! topic ch1)
               (subscribe! topic ch2)
               (subscribe! topic ch3)
               (sleep 20)
               (snd topic "m1")
               (sleep 20)
               (snd topic "m2")
               (sleep 20)
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
                   f1 (spawn-fiber #(list (rcv ch1 1000 :ms) (rcv ch1 1000 :ms) (rcv ch1 1000 :ms)))
                   f2 (spawn-fiber #(list (rcv ch2 1000 :ms) (rcv ch2 1000 :ms) (rcv ch2 1000 :ms)))
                   f3 (spawn-fiber #(list (rcv ch3 1000 :ms) (rcv ch3 1000 :ms) (rcv ch3 1000 :ms)))]
               (subscribe! topic ch1)
               (subscribe! topic ch2)
               (subscribe! topic ch3)
               ;(sleep 5)
               (snd topic "m1")
               (sleep 5)
               (snd topic "m2")
               (unsubscribe! topic ch2)
               (sleep 5)
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
      (let [ch (channel 10 :displace)
            task (sfn [] 
                        (let [ch (ticker-consumer ch)]
                          (loop [prev -1]
                            (let [m (rcv ch)]
                              (when m
                                (assert (> m prev)) ;(fact m => (gt? prev))
                                (recur (long m)))))))
            f1 (spawn-fiber task)
            t1 (spawn-thread task)
            f2 (spawn-fiber task)
            t2 (spawn-thread task)
            f3 (spawn-fiber task)
            t3 (spawn-thread task)
            f4 (spawn-fiber task)
            t4 (spawn-thread task)]
        (sleep 100)
        (dotimes [i 1000]
          (sleep 1)
          (snd ch i))
        (close! ch)
        (join (list f1 t1 f2 t2 f3 t3 f4 t4))
        :ok) => :ok)


(defn fan-in [ins size]
  (let [c (channel size)]
    (spawn-fiber #(while true
                    (let [[x] (sel ins)]
                      (snd c x))))
    c))

(defn fan-out [in cs-or-n]
  (let [cs (if (number? cs-or-n)
             (doall (repeatedly cs-or-n channel))
             (doall cs-or-n))]
    (spawn-fiber (fn []
                   (while true
                     (let [x (rcv in)
                           outs (map #(vector % x) cs)]
                       (sel outs)))))
    cs))

(facts :selected "select"
       #_(Debug/dumpAfter 5000 "sel.log")
       (fact "basic sel test"
             (let [cout (channel 0) ;;
                   cin (fan-in (fan-out cout (repeatedly 3 channel)) 0)
                   f (spawn-fiber #(loop [n (int 0)
                                          res []]
                                     (if (< n 10)
                                        (do 
                                          (snd cout n)
                                          (recur (inc n) (conj res (rcv cin))))
                                        res)))]
               (join f) => (vec (range 10))))
       (fact :selected "another sel test"
             (let [n 20
                   cout (channel 1) ;;
                   cin (fan-in (fan-out cout (repeatedly n #(channel 1))) 1)]
               (dotimes [i n]
                 (snd cout i))
               (sort (repeatedly n #(rcv cin))) => (range n)))
       (fact "test select with timeout"
             (let [c (channel)]
               (select :timeout 0 
                       c ([v] v) 
                       :else "timeout!")) => "timeout!")
       (fact "test select with timeout2"
             (let [c (channel)]
               (select :timeout 100 
                       c ([v] v) 
                       :else "timeout!")) => "timeout!")
       (fact "test select with timeout3"
             (let [c (channel)
                   f (spawn-fiber #(snd c 10))]
               (select :timeout 100 
                       c ([v] (inc v)) 
                       :else "timeout!")) => 11))
