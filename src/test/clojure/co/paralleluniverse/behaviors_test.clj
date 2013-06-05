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

(ns co.paralleluniverse.behaviors-test
  (:use midje.sweet
        co.paralleluniverse.pulsar.core
        co.paralleluniverse.pulsar.behaviors)
  (:import [java.util.concurrent TimeUnit TimeoutException ExecutionException]
           [co.paralleluniverse.common.util Debug]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.fibers Fiber]))

;; ## gen-server

(fact "When gen-server starts then init is called"
      (let [called (atom false)
            gs (spawn
                (gen-server (reify Server
                              (init [_]
                                    (reset! called true)
                                    (stop))
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
                                                (stop)))
                              (terminate [_ cause]))))]
        (fact
         (join 50 :ms gs) => (throws TimeoutException))
        (join 200 :ms gs)
        @times) => 5)

(fact "When no messages then handle-timeout is called"
      (let [times (atom 0)
            gs (spawn
                (gen-server (reify Server
                              (init [_])
                              (terminate [_ cause]
                                         (fact cause => nil)))))]
        (fact
         (join 50 :ms gs) => (throws TimeoutException))
        (shutdown gs)
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
       (fact "When gen-server call from fiber then result is returned"
             (let [gs (spawn
                       (gen-server (reify Server
                                     (init [_])
                                     (terminate [_ cause])
                                     (handle-call [_ from id [a b]]
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
        (shutdown gs)
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
        (shutdown gs)
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
              (shutdown sup)
              (join sup)))
      (fact "When permanent actor dies of un-natural causes then restart"
            (let [sup (spawn
                       (supervisor :one-for-one
                                   #(list ["actor1" :permanent 5 1 :sec 10 bad-actor1])))]
              (dotimes [i 2]
                (let [a (sup-child sup "actor1" 200)]
                  (! a :hi!)
                  (fact
                   (join a) => throws Exception)))
              (shutdown sup)
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
              (shutdown sup)
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
              (shutdown sup)
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
              (shutdown sup)
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
              (shutdown sup)
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
                                   (let [res (+ a b)]
                                     (if (> res 100)
                                       (throw (Exception. "oops!"))
                                       res))))))
        a (receive)
        b (receive)]
    (call adder a b)))

(fact "Complex example test1"
      (let [started    (atom 0)
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
           (join a) => 7))
        (let [a (sup-child sup "actor1" 200)]
          (! a 70)
          (! a 80)
          (fact
           (join a) => throws Exception))
        (let [a (sup-child sup "actor1" 200)]
          (! a 7)
          (! a 8)
          (fact
           (join a) => 15))
        (Strand/sleep 100) ; give the actor time to start the gen-server
        (shutdown sup)
        (join sup)
        [@started @terminated]) => [4 4])
