---
layout: default
title: Clustering
weight: 4
---

Pulsar is able to run on a cluster, thereby letting actors and channels communicate across machines. The Pulsar/Quasar cluster runs on top of [Galaxy](http://puniverse.github.io/galaxy/), Parallel Universe's in-memory data grid. 

In this version, clustering is pretty rudimentary, but essential features should work: actors can be made discoverable on the network, messages can be passed among actors on different nodes, and an actor on a failing node will behave as expected of a dying actor with respect to exit messages sent to other, remote, *watching* it or *linked* to it.

## Enabling Clustering

First, you will need to add `quasar-galaxy` as a dependency to your project:

~~~ clojure
[co.paralleluniverse/quasar-galaxy "0.2"]
~~~

To make an actor discoverable, all you need to do is register it:

~~~ clojure
(register actor :global-actor1)
~~~

or, if the actor already has a name (set in `spawn`), simply call:

~~~ clojure
(register)
~~~

That's it. The actor is now known throughout the cluster. If you want to send a message to it, call

~~~ clojure
(! :global-actor1 [:hi-from @self])
~~~

Though this call looks up the actor in the registry every time it's called. A better way might be:

~~~ clojure
(let [a (whereis :global-actor1)]
   (! a [:hi-from @self]))
~~~

An actor doesn't have to be registered in order to be reachable on the network. Registering it simply makes it *discoverable*. If we pass a local actor in a message to a remote actor, the remote actor will be able to send messages to the local actor as well. In the simple example above, we are sending @self to :global-actor1; :global-actor1 will be able to send messages back to us.

## Example

## Cluster Configuration

For instructions on how to configure the Galaxy cluster, please refere to Galaxy's [getting started guide](http://puniverse.github.io/galaxy/start/getting-started.html).

