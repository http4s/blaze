blaze ![Continuous Integration](https://github.com/http4s/blaze/workflows/Continuous%20Integration/badge.svg) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.http4s/blaze-http_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.http4s/blaze-http_2.12)
=====

blaze is a Scala library for building asynchronous pipelines, with a
focus on network IO.  Blaze is the original server and client backend
for the [http4s][http4s] project.

## Modules

blaze was conceived as a standalone project, but has seen the most use
through http4s.  This repository houses both the standalone modules
and the http4s backend.

### Standalone modules

These have minimal external dependencies.  They represent the original
vision of the blaze project and are less functional in nature.

* [`blaze-core`](./core): the core NIO library 
* [`blaze-http`](./blaze-http): HTTP codecs built on `blaze-core`.

### http4s backend

These modules were housed in the [http4s][http4s] repository through
v0.23.11.  They are now maintained alongside the blaze core.  These
modules depend on `http4s-core` and `blaze-http`.

* [`http4s-blaze-core`](./blaze-core): common, functional code to in
  support of the backends.  Most of this code is package private.
* [`http4s-blaze-server`](./blaze-server): the original server backend
  for http4s
* [`http4s-blaze-client`](./blaze-client): the original client backend
  for http4s

## Features
- Completely asynchronous, pull-based design
- Type safe pipeline composition
- NIO1 and NIO2 socket engines
- SSL integration
- HTTP/1.x and HTTP/2 codecs
- Basic HTTP/1.x server and client
- Basic HTTP/2 server

#### blaze's goals are
- Provides an IO framework to support the core http4s project
- Speed is the first priority
- Asynchronicity underpinned by Scala `Future`
- Java NIO backend for fast/asynchronous network and file I/O
- A framework for rapid development of fast, asynchronous pipelines using Scala

#### What blaze is not
- Purely functional. Functional is preferred except when in conflict with the first goal.

## Asynchronous network I/O
blaze was built with NIO2 features in mind. NIO2 offers a host of new features which are offered
in a unified way. blaze offers TCP socket server support for both NIO1 and NIO2, depending on your preference. 
NIO1 tends to be the better performing technology for raw sockets.

## Getting started
For a quick start, look in the examples project for some simple examples of HTTP, WebSocket, and a simple EchoServer.

#### Important blaze behaviors
* By default, read and write requests should be serialized either manually, or with a `SerializerStage`.

#### Data flow

Useful asynchronous processing is done through the construction of a pipeline. A pipeline is composed of individual
stages that perform operations such as decoding/encoding, SSL encryption, buffering, etc. The pipeline is constructed
in a type safe manner using the `PipelineBuilder` types.

blaze is a pull-based framework as opposed to the reactor and actor models found in other systems. Therefore,
data flow is typically controlled by the tail of the pipeline. Data is written and requested using the `Future`
type found in the Scala standard library.

blaze doesn't make any assumptions as to whether any stage is threadsafe, so as noted above, either manually
ensure a write is not performed before the previous write has completed and the same for reads, or use a 
`SerializerStage` or `Serializer` mixin to automatically sequence reads and writes for you.

### Contributors
[The Contributors](https://github.com/http4s/blaze/graphs/contributors?from=2013-01-01&type=c), as calculated by GitHub.
Contributions and comments in the form of issues or pull requests are greatly appreciated.

### Acknowledgments
Several concepts are inspired by the [Jetty][jetty] and [Netty][netty]
projects.

### Licence
Blaze is licensed under the Apache 2 license. See the [LICENSE][license] file in the root project directory for more information.

[http4s]: https://github.com/http4s/http4s
[jetty]: http://www.eclipse.org/jetty/
[license]: https://github.com/http4s/http4s/blob/main/LICENSE
[netty]: http://netty.io/
