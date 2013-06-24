# Pulsar [![Build Status](https://travis-ci.org/puniverse/pulsar.png?branch=master)](https://travis-ci.org/puniverse/pulsar)<br/>Lightweight threads and Erlang-like actors for Clojure

**NOTE: This is alpha software**

Pulsar wraps the [Quasar](https://github.com/puniverse/quasar) library with a Clojure API that's very similar to Erlang.

## Requirements

Java 7 and Clojure 1.5 are required to run Pulsar.

## Getting started

Add the following dependency to [Leiningen](http://github.com/technomancy/leiningen/)'s project.clj:

```clojure
[co.paralleluniverse/pulsar "0.1.1"]
```

Then, the following must be added to the `java` command line or to project.clj's `:jvm-opts`
section:

```
-javaagent:path-to-quasar-jar.jar
```

Alternatively, to build Pulsar from the source, clone the repository and run:

```
lein test
```

## Usage

Documentation ( *in progress* ) and examples can be found [here](http://puniverse.github.io/pulsar/).

You can also read the introductory [blog post](http://blog.paralleluniverse.co/post/49445260575/quasar-pulsar).

When running code that uses Pulsar, the instrumentation agent must be run by adding the following
to the `java` command line
or to the `:jvm-opts` section in project.clj:

```
-javaagent:path-to-quasar-jar.jar
```

## Documentation

* [User Guide](http://puniverse.github.io/pulsar/)
* [API](http://puniverse.github.io/pulsar/api/)
* [Marginalia](http://puniverse.github.io/pulsar/uberdoc.html)

## Community

* [Google group](https://groups.google.com/forum/?fromgroups#!forum/quasar-pulsar-user).

## License

Pulsar is free software published under the following license:


```
Copyright © 2013 Parallel Universe

This program and the accompanying materials are dual-licensed under
either the terms of the Eclipse Public License v1.0 as published by
the Eclipse Foundation

  or (per the licensee's choosing)

under the terms of the GNU Lesser General Public License version 3.0
as published by the Free Software Foundation.
```
