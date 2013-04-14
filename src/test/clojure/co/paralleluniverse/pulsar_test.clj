(ns co.paralleluniverse.pulsar-test
  (:use clojure.test
        co.paralleluniverse.pulsar)
  (:import [java.util.concurrent TimeUnit]
           [jsr166e ForkJoinPool ForkJoinTask]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.fibers Fiber Joinable FiberInterruptedException]
           [co.paralleluniverse.strands.channels Channel]
           [co.paralleluniverse.actors Actor PulsarActor]))

(deftest fiber-timeout
  (testing "When join and timeout then throw exception"
    (is (thrown? java.util.concurrent.TimeoutException 
                 (let [fib (spawn-fiber (Fiber/park 100 TimeUnit/MILLISECONDS))]
                   (join fib 2 TimeUnit/MILLISECONDS)))))
  (testing "When join and no timeout then join"
    (is (= 15 
           (let [fib (spawn-fiber 
                      (Fiber/park 100 TimeUnit/MILLISECONDS)
                      15)]
             (join fib 200 TimeUnit/MILLISECONDS))))))

(deftest fiber-exception
  (testing "When fiber throws exception then join throws that exception"
    (is (thrown-with-msg? Exception #"my exception"
                 (let [fib (spawn-fiber (throw (Exception. "my exception")))]
                   (join fib))))))

(deftest interrupt-fiber
  (testing "When fiber interrupted while sleeping then InterruptedException thrown"
    (let [fib (spawn-fiber 
                      (is (thrown? FiberInterruptedException (Fiber/sleep 100))))]
      (Thread/sleep 20)
      (.interrupt fib))))
             