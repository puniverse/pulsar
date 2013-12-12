(ns co.paralleluniverse.pulsar.examples.selective-gen-server
  "An implementation of the selctive example using gen-server"
  (:use [co.paralleluniverse.pulsar core actors])
  (:refer-clojure :exclude [promise await])
  (:import [co.paralleluniverse.strands Strand]))


(def adder (gen-server (reify Server
                         (init [_])
                         (terminate [_ cause])
                         (handle-call [_ from id [command a b]]
                                      (case command
                                        :add (+ a b))))))

(defn computer [adder]
  (gen-server (reify Server
                (init [_])
                (terminate [_ cause])
                (handle-call [_ from id [command a b c d]]
                             (case command
                               :compute (call-timed! adder 10 :ms :add (* a b) (* c d)))))))

(defsfn curious [nums computer]
  (when (seq nums)
    (let [[a b c d] (take 4 nums)
          res (call! computer :compute a b c d)]
      (println a b c d "->" res)
      (recur (drop 4 nums) computer))))

(defn -main []
  (let [ad (spawn adder)
        cp (spawn (computer ad))
        cr (spawn curious (take 20 (repeatedly #(rand-int 10))) cp)]
    (join cr)
    :ok))
