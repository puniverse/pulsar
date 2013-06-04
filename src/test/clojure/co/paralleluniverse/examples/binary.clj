(ns co.paralleluniverse.pulsar-test.examples.binary
  "A binary-data buffer message example"
  (:use co.paralleluniverse.pulsar.core
        [gloss core io]))

;; This is an example of sending, receiving and matching binary data buffers

;; This is the layout of our binary buffer:
(def fr (compile-frame {:a :int16, :b :float32}))

(defsusfn receiver []
  (receive [buffer #(decode fr %)]
           {:a 1 :b b} (println "Got buffer (a=1) b: " b)
           {:a a :b b} (println "Got unexpected buffer" buffer "a: " a "b: " b)
           :after 100 (println "timeout!")))

(defn -main []
  (let [r (spawn receiver)
        buffer (encode fr {:a 1 :b 2.3})]
    (! r buffer)
    (join r)))
