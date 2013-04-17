(ns co.paralleluniverse.pulsar.examples.ring-benchmark
  (:use co.paralleluniverse.pulsar)
  (:import [co.paralleluniverse.fibers Fiber FiberInterruptedException TimeoutException]))


(defsusfn foo1 [prev]
  (! prev (inc (co.paralleluniverse.pulsar.PulsarActor/selfReceive)))
  (recur prev))

(defn spawn-relay-actor [prev n]
  (if (= n 0)
    prev
    (let [actor (spawn :mailbox-size 10 
                       (foo1 prev)
                       #_(loop []
                           (! prev (co.paralleluniverse.pulsar.PulsarActor/selfReceive))
                           (recur)))]
      (recur actor (dec n)))))


(defn -main [M1 N1]
  (let [M (Integer/parseInt M1)
        N (Integer/parseInt N1)]
    (println "M: " M " N: " N)
    (dotimes [i 1000]
      (let [num-messages
            (time
             (let [manager 
                   (spawn :mailbox-size 10 
                          (let [last-actor (spawn-relay-actor @self (dec N))]
                            (! last-actor 1) ; start things off
                            (loop [j (int 1)]
                              (let [m (co.paralleluniverse.pulsar.PulsarActor/selfReceive)]
                                ;(println "- j: " j " m: " m)
                                (if (< j M)
                                  (do
                                    (! last-actor (inc m))
                                    (recur (inc j)))
                                  m)))))]
               (join manager)))]
        (println i ": Messages " num-messages)))))
