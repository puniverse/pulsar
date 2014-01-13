(ns co.paralleluniverse.pulsar.examples.codeswap
  "Hot code swapping example"
  (:use [co.paralleluniverse.pulsar core actors])
  (:refer-clojure :exclude [promise await]))

(defsfn a [n]
        (println "I'm a simple actor" n)
        (when-let [m (receive-timed 1000)]
          (println "message:" m))
        (recur-swap a (inc n)))

(def s (sreify Server
         (init [_])
         (terminate [_ cause])
         (handle-call [_ from id [a b]]
           (sleep 50)
           (+ a b))))

(defn -main []
  (println "starting")
  (let [actor (spawn a 1)
        server (spawn (gen-server s))
        sender (spawn (fn [i]
                        (! actor i)
                        (println "server responded:" (call! server i 10))
                        (sleep 1500)
                        (recur (inc i)))
                      0)]
    (sleep 8 :sec)
    (println "swapping")
    (defsfn a [n]
            (println "I'm a simple, but better, actor" n)
            (when-let [m (receive-timed 1000)]
              (println "message!" m))
            (recur-swap a (inc n)))
    (def s (sreify Server
             (init [_])
             (terminate [_ cause])
             (handle-call [_ from id [a b]]
               (sleep 50)
               (* a 1000))))
    (sleep 8 :sec)
    ;(join actor)
    (println "done")))
