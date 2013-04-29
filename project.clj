(defproject co.paralleluniverse/pulsar "0.1.0-SNAPSHOT"
  :description "A Clojure actor library"
  :url "http://github.com/puniverse/pulsar"
  :license {:name "LGPL"
            :url "http://www.gnu.org/copyleft/lesser.html"}
  :distribution :repo
  :source-paths ["src/main/clojure"]
  :test-paths ["src/test/clojure"]
  :java-source-paths ["src/main/java"]
  :javac-options     ["-target" "1.7" "-source" "1.7"]
  :repositories {"project" "file:lib"}
  :test-selectors {:selected :selected}
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [jsr166e/jsr166e "0.1"]
                 [co.paralleluniverse/quasar "0.1-SNAPSHOT"]
                 [com.yammer.metrics/metrics-core "2.0.2"]
                 [org.ow2.asm/asm "4.1"]
                 [org.ow2.asm/asm-analysis "4.1"]
                 [org.ow2.asm/asm-util "4.1"]
                 [com.google.guava/guava "11.0.1"]
                 [net.sf.trove4j/trove4j "3.0.2"]
                 [org.clojure/core.match "0.2.0-alpha12"]]
  :jvm-opts ^:replace ["-server"
                       "-javaagent:/Users/pron/Projects/quasar/build/libs/quasar-0.1-SNAPSHOT.jar"]
  ;             "-javaagent:/Users/pron/Projects/lib/btrace-bin/build/btrace-agent.jar=script=/Users/pron/Projects/btraces/build/classes/PulsarTrace.class"]
  :profiles {:dev 
             {:plugins [[codox "0.6.4"]
                        [lein-marginalia "0.7.1"]]
              :codox {:include co.paralleluniverse.pulsar
                      :output-dir "docs"}
              :jvm-opts ["-ea" 
                         "-Dco.paralleluniverse.debugMode=true" 
                         "-Dco.paralleluniverse.lwthreads.verifyInstrumentation=true"
                         "-Dco.paralleluniverse.globalFlightRecorder=true" "-Dco.paralleluniverse.flightRecorderDumpFile=pulsar.log"]
              :global-vars {*warn-on-reflection* true}}})
