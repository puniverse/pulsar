---
layout: default
title: core.async
weight: 3
---

core.async is a new [asynchronous programming library](https://github.com/clojure/core.async/) for Clojure built by Rich Hickey and other contributors. It provides something akin to fibers (though more limited than Pulsar fibers) and channels, and is also available in ClojureScript. Because core.async provides a subset of Pulsar's capability, Pulsar provides an optional API that's compatible with core.async which some people may prefer.

The core.async implementation is found in the `co.paralleluniverse.pulsar.async` namespace. It defines the following names: `chan`, `buffer`, `dropping-buffer`, `sliding-buffer`, `go`, `thread`, `close!`, `take!`, `put!`, `>!`, `>!!`, `<!`, `<!!`, `alts!`, `alts!!`, `alt!`, `alt!!` and `timeout`.

These definitions are no more than thin wrappers around Pulsar functions and macros:

* `(chan)` is the same as calling `(channel)`.
* `(chan (buffer n))` or `(chan n)` are the same as `(channel n :block)` and `(channel n)`.
* `(chan (dropping-buffer n))` is the same as `(channel n :drop)`
* `(chan (sliding-buffer n))` is the same as `(channel n :displace)`
* `close!` is the same as `close!`
* `>!` and `>!!` are the same as `snd`
* `<!` and `<!!` are the same as `rcv`

The core.async API and the Pulsar API may be used interchangably, so you can call '>!' on a channel created with `channel` or `snd` on a channel created with `chan`. They are one and the same.

`go` simply performs its body within a newly spawned fiber.

These are the differences between the Pulsar implementation of the core.async API, and the orgiginal implementation:

* Channels created with `(chan (sliding-buffer n))` are single-consumer.
* You may use the `!` and the `!!` defs interchangeably (the original implementation forces the use of the single-bang defs in go-blocks and the double-bang defs in regular threads). This means that in the Pulsar implementation all of the double-bang names (`>!!`, `<!!`, `alts!!` and `alt!!`) are actually redundant (but included for compatibility).

In addition, there are performance differences, mostly resulting from the fact that Pulsar uses fork-join pools to schedule fibers, while core.async uses regular thread pools. In short, if there is little interaction between go blocks (say, each go block writes something to a channel and completes), you can expet better performance from the original implementation. If there is a lot of interaction between go blocks (there's non-trivial message passing among them), then the Pulsar implementation will yield better performance.
