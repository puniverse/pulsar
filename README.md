# Pulsar: lightweight threads and Erlang-like actors for Clojure

**NOTE: This is alpha software**

## Requirements

Java 7 and Clojure 1.5 are required to run Pulsar.

## Getting started

Getting started with Pulsar is a bit clunky at this stage because of its dependency on jsr166e.

1. Install [lein-localrepo](https://github.com/kumarshantanu/lein-localrepo) by adding
    `{:user {:plugins [[lein-localrepo "0.4.1"]]}}` to your `~/.lein/profiles.clj` file.
2. Donwload jsr166e by clicking [here](http://gee.cs.oswego.edu/dl/jsr166/dist/jsr166e.jar).
3. Deploy the jsr166e to the local maven repository with this command:
    ```
    lein localrepo install jsr166e.jar jsr166e/jsr166e 0.1
    ```
4. Finally, add the following dependency to [Leiningen](http://github.com/technomancy/leiningen/)'s project.clj:

    ```clojure
    [co.paralleluniverse/pulsar "0.1.0"]
    ```

Then, the following must be added to the `java` command line or to project.clj's `:jvm-opts`
section:

```
-javaagent:path-to-quasar-jar.jar
```

Alternatively, to build Pulsar from the source, clone the repository and run:

```
lein uberjar
lein test
```

## Usage

Currently, there isn’t much in the way of documentation (coming soon!).
In the meantime, you can study the examples [here](https://github.com/puniverse/pulsar/tree/master/src/test/clojure/co/paralleluniverse/pulsar_test/examples)
and the tests [here](https://github.com/puniverse/pulsar/blob/master/src/test/clojure/co/paralleluniverse/pulsar_test.clj).

You can also read the introductory [blog post]http://blog.paralleluniverse.co/post/49445260575/quasar-pulsar).

When running code that uses Pulsar, the instrumentation agent must be run by adding the following
to the `java` command line
or to the `:jvm-opts` section in project.clj:

```
-javaagent:path-to-quasar-jar.jar
```

## Getting help

Questions and suggestions are welcome at this [forum/mailing list](https://groups.google.com/forum/?fromgroups#!forum/quasar-pulsar-user).

## License

Copyright © 2013 Parallel Universe

This program and the accompanying materials are dual-licensed under
either the terms of the Eclipse Public License v1.0 as published by
the Eclipse Foundation

  or (per the licensee's choosing)

under the terms of the GNU Lesser General Public License version 3.0
as published by the Free Software Foundation.
