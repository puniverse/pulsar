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
                 [co.paralleluniverse/quasar-core "0.2-SNAPSHOT"]
                 [com.yammer.metrics/metrics-core "2.0.2"]
                 [org.ow2.asm/asm "4.1"]
                 [org.ow2.asm/asm-analysis "4.1"]
                 [org.ow2.asm/asm-util "4.1"]
                 [com.google.guava/guava "11.0.1"]
                 [org.clojure/core.match "0.2.0-alpha12"]
                 [useful "0.8.3-alpha8"]
                 [gloss "0.2.2-beta4" :exclusions [com.yammer.metrics/metrics-core useful]]
                 [org.clojure/core.typed "0.1.14" :exclusions [org.apache.ant/ant org.clojure/core.unify]]]
  :manifest {"Premain-Class" "co.paralleluniverse.fibers.instrument.JavaAgent"
             "Can-Retransform-Classes" "true"}
  :jvm-opts ["-server"
             ~(str "-javaagent:" (System/getProperty "user.home") "/.m2/repository/co/paralleluniverse/quasar/0.2-SNAPSHOT/quasar-0.2-SNAPSHOT.jar")]
  :pedantic :warn
  :profiles {:dev
             {:plugins [[lein-midje "3.0.0"]]
              :dependencies [[midje "1.5.1" :exclusions [org.clojure/tools.namespace]]]
              :jvm-opts ["-ea"
                         ;"-Dco.paralleluniverse.debugMode=true"
                         ;"-Dco.paralleluniverse.lwthreads.verifyInstrumentation=true"
                         ;"-Dco.paralleluniverse.globalFlightRecorder=true"
                         ;"-Dco.paralleluniverse.monitoring.flightRecorderLevel=2"
                         "-Dco.paralleluniverse.flightRecorderDumpFile=pulsar.log"
                         "-Dlog4j.configurationFile=log4j.xml"
                         "-DLog4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector"]
              :global-vars {*warn-on-reflection* true}}
             :doc
             {:plugins [[lein-midje "3.0.0"]
                        [codox "0.6.4"]
                        [lein-marginalia "0.7.1"]]
              :dependencies [[midje "1.5.1" :exclusions [org.clojure/tools.namespace]]
                             [codox/codox.core "0.6.4" :exclusions [org.clojure/tools.namespace]] ; here just for the exclusions
                             [marginalia "0.7.1" :exclusions [org.clojure/tools.namespace]]]      ; here just for the exclusions
              :injections [(require 'clojure.test)
                           (alter-var-root #'clojure.test/*load-tests* (constantly false))]
              :codox {:include [co.paralleluniverse.pulsar co.paralleluniverse.pulsar.behaviors]
                      :output-dir "docs/api"}
              :global-vars {*warn-on-reflection* false}}})
