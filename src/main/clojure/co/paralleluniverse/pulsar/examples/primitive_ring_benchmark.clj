(ns co.paralleluniverse.pulsar.examples.primitive-ring-benchmark
  "An implementation of the ring benchmark using fibers and primitive (int) channels"
  (:use co.paralleluniverse.pulsar)
  (:import [co.paralleluniverse.fibers Fiber FiberInterruptedException TimeoutException]
           [co.paralleluniverse.strands.channels Channel IntChannel]))


(defn spawn-relay [^IntChannel prev n]
  (if (== n 0)
    prev
    (let [channel (int-channel 10)
          fiber (spawn-fiber (loop []
                               (let [m (receive-int channel)]
                                 ;(println n ": " m)
                                 (send-int prev (inc m))
                                 (recur))))]
      (recur channel (dec n)))))


(defn -main [M1 N1]
  (let [M (int (Integer/parseInt M1))
        N  (int (Integer/parseInt N1))]
    (println "M: " M " N: " N)
    (dotimes [i 1000]
      (let [num-messages
            (time
             (let [manager-channel (int-channel 10)
                   last-channel (spawn-relay manager-channel (dec N))
                   manager (spawn-fiber 
                            (send-int last-channel 1)  ; start things off
                            (loop [j (int 1)]
                              (let [m (receive-int manager-channel)]
                                (if (< j M)
                                  (do
                                    ;(println "m: " m)
                                    (send-int last-channel (inc m))
                                    (recur (inc j)))
                                  m))))]
               (join manager)))]
        (println i ": Messages " num-messages)))))
