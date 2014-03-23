(ns co.paralleluniverse.pulsar.examples.tkb
  (:use co.paralleluniverse.pulsar.core)
  (:import [co.paralleluniverse.strands.channels ReceivePort$EOFException])
  (:refer-clojure :exclude [promise await])
  (:require [co.paralleluniverse.pulsar.rx :as rx]))

;(let [numbers (topic)
;      letters (topic)
;
;      f1 (spawn-fiber
;           (fn []
;             (let [c (->>
;                       (rx/zip (->>
;                                 (subscribe! letters (channel 0))
;                                 (rx/mapcat #(repeat 3 %)))
;                               (->>
;                                 (subscribe! numbers (channel 0))
;                                 (rx/filter odd?)
;                                 (rx/mapcat #(list % (* 10 %) (* 100 %)))))
;                       (rx/map (fn [[c n]] (str "letter: " c " number: " n))))]
;               (loop []
;                 (when-let [m (rcv c)]
;                   (println "=> " m )
;                   (recur))))))]
;  (spawn-fiber (fn []
;                 (doseq [x (seq "abcdefghijklmnopqrstuwvxyz")]
;                   (sleep 50)
;                   (snd letters x))
;                 (close! letters)))
;
;  (spawn-fiber (fn []
;                 (doseq [x (range 1000)]
;                   (sleep 70)
;                   (snd numbers x))
;                 (close! numbers)))
;
;  (join [f1]))

(defn -main[])


