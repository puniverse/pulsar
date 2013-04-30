(ns co.paralleluniverse.pulsar-test
  (:use clojure.test
        co.paralleluniverse.pulsar)
  (:import [java.util.concurrent TimeUnit]
           [co.paralleluniverse.common.util Debug]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.fibers Fiber FiberInterruptedException TimeoutException]))


(defn fail []
  (do-report {:type :fail}))


;; ## fibers

(deftest fiber-timeout
  (testing "When join and timeout then throw exception"
    (is (thrown? java.util.concurrent.TimeoutException 
                 (let [fib (spawn-fiber #(Fiber/park 100 TimeUnit/MILLISECONDS))]
                   (join fib 2 TimeUnit/MILLISECONDS)))))
  (testing "When join and no timeout then join"
    (is (= 15 
           (let [fib (spawn-fiber 
                      #(do
                         (Fiber/park 100 TimeUnit/MILLISECONDS)
                         15))]
             (join fib 200 TimeUnit/MILLISECONDS))))))

(deftest fiber-exception
  (testing "When fiber throws exception then join throws that exception"
    (is (thrown-with-msg? Exception #"my exception"
                          (let [fib (spawn-fiber #(throw (Exception. "my exception")))]
                            (join fib))))))

(deftest interrupt-fiber
  (testing "When fiber interrupted while sleeping then InterruptedException thrown"
    (let [fib (spawn-fiber
               #(is (thrown? FiberInterruptedException (Fiber/sleep 100))))]
      (Thread/sleep 20)
      (.interrupt fib))))

;; ## channels

;; ## actors

(deftest actor-exception
  (testing "When fiber throws exception then join throws it"
    (is (thrown-with-msg? Exception #"my exception"
                          (let [actor (spawn #(throw (Exception. "my exception")))]
                            (join actor))))))

(deftest actor-ret-value
  (testing "When actor returns a value then join returns it"
    (is (= 42
           (let [actor (spawn #(+ 41 1))]
             (join actor))))))


(def ^:dynamic *foo* 40)

(deftest actor-bindings
  (testing "Test dynamic binding around spawn"
    (is (= 40 (let [actor 
                    (binding [*foo* 20]
                      (spawn 
                       #(let [v1 *foo*]
                          (Fiber/sleep 200)
                          (let [v2 *foo*]
                            (+ v1 v2)))))]
                (join actor)))))
  (testing "Test dynamic binding in spawn"
    (is (= 30 (let [actor 
                    (spawn 
                     #(binding [*foo* 15]
                        (let [v1 *foo*]
                          (Fiber/sleep 200)
                          (let [v2 *foo*]
                            (+ v1 v2)))))]
                (join actor))))))

(deftest actor-receive
  (testing "Test simple actor send/receive"
    (is (= :abc (let [actor (spawn #(receive))]
                  (! actor :abc)
                  (join actor)))))
  (testing "Test receive after sleep"
    (is (= 25 (let [actor 
                    (spawn #(let [m1 (receive)
                                  m2 (receive)]
                              (+ m1 m2)))]
                (! actor 13)
                (Thread/sleep 200)
                (! actor 12)
                (join actor)))))
  (testing "When simple receive and timeout then return nil"
    (let [actor 
          (spawn #(let [m1 (receive-timed 50)
                        m2 (receive-timed 50)
                        m3 (receive-timed 50)]
                    (is (= 1 m1))
                    (is (= 2 m2))
                    (is (nil? m3))))]
      (! actor 1)
      (Thread/sleep 20)
      (! actor 2)
      (Thread/sleep 100)
      (! actor 3)
      (join actor))))

(deftest ^:selected matching-receive
  (testing "Test actor matching receive 1"
      (is (= "yes!" (let [actor (spawn 
                                 #(receive
                                   :abc "yes!"
                                   :else "oy"))]
                      (! actor :abc)
                      (join actor)))))
  (testing "Test actor matching receive 2"
      (is (= "because!" (let [actor (spawn 
                                     #(receive
                                       :abc "yes!"
                                       [:why? answer] answer
                                       :else "oy"))]
                          (! actor [:why? "because!"])
                          (join actor)))))
  (testing "When matching receive and timeout then run :after clause"
    (let [actor 
          (spawn 
           #(receive
             [:foo] nil
             :else (println "got it!")
             :after 30 :timeout))]
      (Thread/sleep 150)
      (! actor 1)
      (is (= :timeout (join actor))))))

(deftest selective-receive
  (testing "Test selective receive"
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
      (is (= @res [1 3 2])))))

(deftest actor-link
  (testing "When an actor dies, its link gets an exception"
    (let [actor1 (spawn #(Fiber/sleep 100))
          actor2 (spawn 
                  #(try 
                     (loop [] (receive [m] :foo :bar) (recur)) 
                     (catch co.paralleluniverse.actors.LifecycleException e nil)))]
      (link! actor1 actor2)
      (join actor1)
      (join actor2)))
  (testing "When an actor dies, and its link traps, then its link gets a message"
    (let [actor1 (spawn #(Fiber/sleep 100))
          actor2 (spawn :trap true
                        #(receive [m] 
                                  [:exit _ actor reason] actor))]
      (link! actor1 actor2)
      (join actor1)
      (is (= actor1 (join actor2))))))

(deftest actor-monitor
  (testing "When an actor dies, its monitor gets a message"
    (let [actor1 (spawn #(Fiber/sleep 200))
          actor2 (spawn
                  #(receive
                    [:exit monitor actor reason] monitor
                    ))]
      (let [mon (monitor! actor2 actor1)]
        (join actor1)
        (is (= mon (join actor2)))))))

