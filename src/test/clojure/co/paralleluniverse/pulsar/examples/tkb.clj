(ns co.paralleluniverse.pulsar.examples.tkb
  (:use co.paralleluniverse.pulsar.core)
  (:import [co.paralleluniverse.strands.channels ReceivePort$EOFException])
  (:refer-clojure :exclude [promise await])
  (:require [co.paralleluniverse.pulsar.rx :as rx]))


(let [t (topic)
      x (promise)
      f1 (spawn-fiber (fn []
                        (let [c (subscribe! t (channel 0))]
                          (loop []
                            (when-let [m (rcv c)]
                              (println "=>" (+ m @x))
                              (recur))))))
      f2 (spawn-fiber (sfn []
                        (let [c (subscribe! t (int-channel 3))]
                          (try
                            (loop []
                              (println "->" (* @x (rcv-int c)))
                              (recur))
                            (catch ReceivePort$EOFException e)))))]
  (spawn-fiber (fn []
                 (sleep 1000)
                 (deliver x 5)))
  (spawn-fiber (fn []
                    (dotimes [i 10]
                      (sleep 100)
                      (snd t (rand-int 100)))
                    (close! t)))

  (join [f1 f2]))

(defn -main[])


