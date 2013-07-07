(ns co.paralleluniverse.pulsar.examples.primitive-ring-benchmark
  "An implementation of the ring benchmark using fibers and primitive (int) channels"
  (:use co.paralleluniverse.pulsar.core))


(defn spawn-relay [prev n]
  (if (== n 0)
    prev
    (let [channel (int-channel 10)
          fiber (spawn-fiber #(loop []
                                (let [m (rcv-int channel)]
                                  ;(println n ": " m)
                                  (snd-int prev (inc m))
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
                              #(do (snd-int last-channel 1)  ; start things off
                                 (loop [j (int 1)]
                                   (let [m (rcv-int manager-channel)]
                                     (if (< j M)
                                       (do
                                         ;(println "m: " m)
                                         (snd-int last-channel (inc m))
                                         (recur (inc j)))
                                       m)))))]
                (join manager)))]
        (println i ": Messages " num-messages)))))
