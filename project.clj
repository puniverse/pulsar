(defproject co.paralleluniverse/pulsar "0.2-SNAPSHOT"
  :description "A Clojure actor library"
  :url "http://github.com/puniverse/pulsar"
  :license {:name "Eclipse Public License - v 1.0" :url "http://www.eclipse.org/legal/epl-v10.html"}
  :distribution :repo
  :source-paths ["src/main/clojure"]
  :test-paths ["src/test/clojure"]
  :resource-paths ["src/main/resources"]
  :java-source-paths ["src/main/java"]
  :javac-options     ["-target" "1.7" "-source" "1.7"]
  :repositories {"snapshots" "https://oss.sonatype.org/content/repositories/snapshots"}
  :test-selectors {:selected :selected}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [co.paralleluniverse/quasar "0.2-SNAPSHOT"]
                 [com.yammer.metrics/metrics-core "2.0.2"]
                 [org.ow2.asm/asm "4.1"]
                 [org.ow2.asm/asm-analysis "4.1"]
                 [org.ow2.asm/asm-util "4.1"]
                 [com.google.guava/guava "11.0.1"]
                 [org.clojure/core.match "0.2.0-alpha12"]
                 [gloss "0.2.2-beta4" :exclusions [com.yammer.metrics/metrics-core]]]
  :manifest {"Premain-Class" "co.paralleluniverse.fibers.instrument.JavaAgent"
             "Can-Retransform-Classes" "true"}
  :profiles {:dev
             {:plugins [[codox "0.6.4"]
                        [lein-marginalia "0.7.1"]]
              :codox {:include co.paralleluniverse.pulsar
                      :output-dir "docs"}
              :jvm-opts ["-server"
                         ~(str "-javaagent:" (System/getProperty "user.home") "/.m2/repository/co/paralleluniverse/quasar/0.1.1/quasar-0.1.1.jar")
                         "-ea"
                         ;"-Dco.paralleluniverse.debugMode=true"
                         ;"-Dco.paralleluniverse.lwthreads.verifyInstrumentation=true"
                         "-Dco.paralleluniverse.globalFlightRecorder=true"
                         "-Dco.paralleluniverse.monitoring.flightRecorderLevel=2"
                         "-Dco.paralleluniverse.flightRecorderDumpFile=pulsar.log"]
              :global-vars {*warn-on-reflection* true}}})
