blaze ![Continuous Integration](https://github.com/http4s/blaze/workflows/Continuous%20Integration/badge.svg) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.http4s/blaze-http_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.http4s/blaze-http_2.12)
=====

blaze is a Scala library for building asynchronous pipelines, with a focus on network IO. Its main goal is
to service the network needs of the [http4s][http4s] project, but may find application elsewhere.

## Features
- Completely asynchronous pull based design
- Type safe pipeline composition
- NIO1 and NIO2 socket engines
- SSL integration
- HTTP/1.x and HTTP2 codecs
- Basic HTTP/1.x server and client
- Basic HTTP2 server

#### blazes goals are
- Provides an IO framework to support the core http4s project
- Speed is the first priority
- Asynchronous using Scala 2.10 Futures as the underpinning
- Java NIO backend for fast/asynchronous network and file I/O
- Offer a framework for rapid development of fast, asynchronous pipelines using Scala

#### What blaze is not
- Purely functional. Functional is preferred except when in conflict with the first goal.
- Raw blaze doesn't promise a stable API. The primary goal of blaze is to support the http4s project
  and as such, the raw blaze API may evolve to meet the needs of http4s. The API of http4s is rapidly
  approaching 1.0, and at that point the raw blaze API will begin the process of stabilizing.

## Asynchronous network I/O
blaze was built with the Java 7 NIO2 features in mind. NIO2 offers a host of new features which are offered 
in a unified way. blaze offers TCP socket server support for both NIO1 and NIO2, depending on your preference. 
NIO1 tends to be the better performing technology for raw sockets.

## Getting started
For a quick start, look in the examples project for some simple examples of HTTP, WebSocket, and a simple EchoServer.

### Requirements
Blaze requires a modern, supported version of the JVM to build and run, as it relies on server APIs unavailable before
[JDK8u252](https://webtide.com/jetty-alpn-java-8u252/). Any JDK newer than JDK8u252, including 9+ is supported.

#### Important blaze behaviors
* By default, read and write requests should be serialized either manually, or with a `SerializerStage`.

#### Data flow

Useful asynchronous processing is done through the construction of a pipeline. A pipeline is composed of individual
stages that perform operations such as decoding/encoding, SSL encryption, buffering, etc. The pipeline is constructed
in a type safe manner using the `PipelineBuilder` types.

blaze is a pull based framework as opposed to the reactor and actor models found in other systems. Therefore, 
data flow is typically controlled by the tail of the pipeline. Data is written and requested using the `Future`
type found in the Scala standard library.

blaze doesn't make any assumptions as to whether any stage is thread safe, so as noted above, either manually
ensure a write is not performed before the previous write has completed and the same for reads, or use a 
`SerializerStage` or `Serializer` mixin to automatically sequence reads and writes for you.

## Development
blaze is under active development and at this stage, everything is candidate for change. However, many features are
reaching maturity, and will _likely_ remain stable. 

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
