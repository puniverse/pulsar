---
layout: default
title: Getting Started
weight: 100
---

{% comment %}
{% include outofdate.html %}
{% endcomment %}

* foo
{:toc}

## System requirements

Java 7 and Clojure 1.5 are required to run Pulsar.

## Using Leiningen {#lein}

Add the following dependency to [Leiningen](http://github.com/technomancy/leiningen/)'s project.clj:

~~~ clj
    [co.paralleluniverse/pulsar "0.1.1"]
~~~

[Leiningen]: http://github.com/technomancy/leiningen/

## Building Pulsar {#build}

Clone the repository:

    git clone git://github.com/puniverse/pulsar.git pulsar

and run:

    lein test

To build the documentation, you need to have [Jekyll] installed. Then run:

    jekyll build

To generate the API documentation run

    lein doc

[Jekyll]: http://jekyllrb.com/


{% comment %}
**Note**: Blah blah blah 
{:.centered .alert .alert-info}

**Note**: Blah blah blah 
{:.alert}

**Note**: Blah blah blah 
{:.alert .alert-error}
{% endcomment %}
