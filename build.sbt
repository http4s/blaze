import com.typesafe.tools.mima.core._
import Dependencies._

val Scala212 = "2.12.19"
val Scala213 = "2.13.14"
val Scala3 = "3.3.3"
val http4sVersion = "0.23.27"
val munitCatsEffectVersion = "2.0.0"

ThisBuild / resolvers +=
  "s01 snapshots".at("https://s01.oss.sonatype.org/content/repositories/snapshots/")

ThisBuild / crossScalaVersions := Seq(Scala3, Scala212, Scala213)
ThisBuild / scalaVersion := crossScalaVersions.value.filter(_.startsWith("2.")).last
ThisBuild / tlBaseVersion := "0.23"
ThisBuild / tlFatalWarnings := !tlIsScala3.value // See SSLStage

// 11 and 17 blocked by https://github.com/http4s/blaze/issues/376
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("8"))

ThisBuild / developers ++= List(
  Developer(
    "bryce-anderson",
    "Bryce L. Anderson",
    "bryce.anderson22@gamil.com",
    url("https://github.com/bryce-anderson"),
  ),
  Developer(
    "rossabaker",
    "Ross A. Baker",
    "ross@rossabaker.com",
    url("https://github.com/rossabaker"),
  ),
  Developer(
    "ChristopherDavenport",
    "Christopher Davenport",
    "chris@christopherdavenport.tech",
    url("https://github.com/ChristopherDavenport"),
  ),
)
ThisBuild / startYear := Some(2014)

lazy val commonSettings = Seq(
  description := "NIO Framework for Scala",
  Test / scalacOptions ~= (_.filterNot(Set("-Ywarn-dead-code", "-Wdead-code"))), // because mockito
  Compile / doc / scalacOptions += "-no-link-warnings",
  Compile / unmanagedSourceDirectories ++= {
    (Compile / unmanagedSourceDirectories).value.map { dir =>
      val sv = scalaVersion.value
      CrossVersion.binaryScalaVersion(sv) match {
        case "2.11" | "2.12" => file(dir.getPath ++ "-2.11-2.12")
        case _ => file(dir.getPath ++ "-2.13")
      }
    }
  },
  run / fork := true,
  scalafmtConfig := file(".scalafmt.blaze.conf"),
  scalafixConfig := Some(file(".scalafix.blaze.conf")),
)

// currently only publishing tags
ThisBuild / githubWorkflowPublishTargetBranches :=
  Seq(RefPredicate.StartsWith(Ref.Tag("v")), RefPredicate.Equals(Ref.Branch("main")))

ThisBuild / githubWorkflowBuild ++= Seq(
  WorkflowStep.Sbt(
    List("${{ matrix.ci }}", "javafmtCheckAll"),
    name = Some("Check Java formatting"),
  )
)

lazy val blaze = project
  .in(file("."))
  .enablePlugins(Http4sOrgPlugin)
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .settings(scalafmtConfig := file(".scalafmt.conf"))
  .aggregate(core, http, blazeCore, blazeServer, blazeClient, examples)

lazy val testkit = Project("blaze-testkit", file("testkit"))
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .settings(libraryDependencies ++= Seq(munit, scalacheckMunit))
  .settings(Revolver.settings)

lazy val core = Project("blaze-core", file("core"))
  .enablePlugins(Http4sOrgPlugin)
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(log4s),
    libraryDependencies += logbackClassic % Test,
    buildInfoPackage := "org.http4s.blaze",
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      scalaVersion,
      git.gitHeadCommit,
    ),
    buildInfoOptions += BuildInfoOption.BuildTime,
    mimaBinaryIssueFilters ++= Seq(
      // private constructor for which there are no sensible defaults
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "org.http4s.blaze.channel.nio1.NIO1SocketServerGroup.this"
      )
    ),
  )
  .dependsOn(testkit % Test)

lazy val http = Project("blaze-http", file("http"))
  .enablePlugins(Http4sOrgPlugin)
  .settings(commonSettings)
  .settings(
    // General Dependencies
    libraryDependencies += twitterHPACK,
    // Test Dependencies
    libraryDependencies += asyncHttpClient % Test,
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters
        .exclude[MissingClassProblem]("org.http4s.blaze.http.http2.PingManager$PingState"),
      ProblemFilters
        .exclude[MissingClassProblem]("org.http4s.blaze.http.http2.PingManager$PingState$"),
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.blaze.http.http2.client.ALPNClientSelector$ClientProvider"
      ),
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.blaze.http.http2.server.ALPNServerSelector$ServerProvider"
      ),
    ),
  )
  .dependsOn(testkit % Test, core % "test->test;compile->compile")

lazy val blazeCore = Project("http4s-blaze-core", file("blaze-core"))
  .settings(
    description := "Base library for binding blaze to http4s clients and servers",
    startYear := Some(2014),
    tlMimaPreviousVersions ++= (0 to 11).map(y => s"0.23.$y").toSet,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
      logbackClassic % Test,
    ),
    mimaBinaryIssueFilters := {
      if (tlIsScala3.value)
        Seq(
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blazecore.util.BodylessWriter.this"),
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blazecore.util.BodylessWriter.ec"),
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blazecore.util.EntityBodyWriter.ec"),
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blazecore.util.CachingChunkWriter.ec"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "org.http4s.blazecore.util.CachingStaticWriter.this"
          ),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "org.http4s.blazecore.util.CachingStaticWriter.ec"
          ),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "org.http4s.blazecore.util.FlushingChunkWriter.ec"
          ),
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blazecore.util.Http2Writer.this"),
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blazecore.util.Http2Writer.ec"),
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blazecore.util.IdentityWriter.this"),
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blazecore.util.IdentityWriter.ec"),
        )
      else Seq.empty
    },
    Test / scalafixConfig := Some(file(".scalafix.test.conf")),
  )
  .dependsOn(http)

lazy val blazeServer = Project("http4s-blaze-server", file("blaze-server"))
  .settings(
    description := "blaze implementation for http4s servers",
    startYear := Some(2014),
    tlMimaPreviousVersions ++= (0 to 11).map(y => s"0.23.$y").toSet,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
    ),
    mimaBinaryIssueFilters := Seq(
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "org.http4s.blaze.server.BlazeServerBuilder.this"
      ), // private
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "org.http4s.blaze.server.WebSocketDecoder.this"
      ), // private
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "org.http4s.blaze.server.BlazeServerBuilder.this"
      ), // private
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.blaze.server.BlazeServerBuilder$ExecutionContextConfig"
      ), // private
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.blaze.server.BlazeServerBuilder$ExecutionContextConfig$"
      ), // private
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.blaze.server.BlazeServerBuilder$ExecutionContextConfig$DefaultContext$"
      ), // private
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.blaze.server.BlazeServerBuilder$ExecutionContextConfig$ExplicitContext"
      ), // private
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.blaze.server.BlazeServerBuilder$ExecutionContextConfig$ExplicitContext$"
      ), // private
      ProblemFilters
        .exclude[DirectMissingMethodProblem]("org.http4s.blaze.server.BlazeServerBuilder.this"),
      ProblemFilters
        .exclude[DirectMissingMethodProblem]("org.http4s.blaze.server.WebSocketDecoder.this"),
    ) ++ {
      if (tlIsScala3.value)
        Seq(
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blaze.server.Http1ServerStage.apply"),
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blaze.server.Http1ServerStage.apply"),
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blaze.server.ProtocolSelector.apply"),
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blaze.server.ProtocolSelector.apply"),
          ProblemFilters.exclude[ReversedMissingMethodProblem](
            "org.http4s.blaze.server.WebSocketSupport.maxBufferSize"
          ),
          ProblemFilters.exclude[ReversedMissingMethodProblem](
            "org.http4s.blaze.server.WebSocketSupport.webSocketKey"
          ),
        )
      else Seq.empty,
    },
    Test / scalafixConfig := Some(file(".scalafix.test.conf")),
  )
  .dependsOn(blazeCore % "compile;test->test")

lazy val blazeClient = Project("http4s-blaze-client", file("blaze-client"))
  .settings(
    description := "blaze implementation for http4s clients",
    startYear := Some(2014),
    tlMimaPreviousVersions ++= (0 to 11).map(y => s"0.23.$y").toSet,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-client" % http4sVersion,
      "org.http4s" %% "http4s-client-testkit" % http4sVersion % Test,
    ),
    mimaBinaryIssueFilters ++= Seq(
      // private constructor
      ProblemFilters
        .exclude[IncompatibleMethTypeProblem]("org.http4s.blaze.client.BlazeClientBuilder.this"),
      ProblemFilters
        .exclude[IncompatibleMethTypeProblem]("org.http4s.blaze.client.Http1Support.this"),
      // These are all private to blaze-client and fallout from from
      // the deprecation of org.http4s.client.Connection
      ProblemFilters
        .exclude[IncompatibleMethTypeProblem]("org.http4s.blaze.client.BasicManager.invalidate"),
      ProblemFilters
        .exclude[IncompatibleMethTypeProblem]("org.http4s.blaze.client.BasicManager.release"),
      ProblemFilters.exclude[MissingTypesProblem]("org.http4s.blaze.client.BlazeConnection"),
      ProblemFilters
        .exclude[IncompatibleMethTypeProblem]("org.http4s.blaze.client.ConnectionManager.release"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "org.http4s.blaze.client.ConnectionManager.invalidate"
      ),
      ProblemFilters
        .exclude[ReversedMissingMethodProblem]("org.http4s.blaze.client.ConnectionManager.release"),
      ProblemFilters.exclude[ReversedMissingMethodProblem](
        "org.http4s.blaze.client.ConnectionManager.invalidate"
      ),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "org.http4s.blaze.client.ConnectionManager#NextConnection.connection"
      ),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "org.http4s.blaze.client.ConnectionManager#NextConnection.copy"
      ),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "org.http4s.blaze.client.ConnectionManager#NextConnection.copy$default$1"
      ),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "org.http4s.blaze.client.ConnectionManager#NextConnection.this"
      ),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "org.http4s.blaze.client.ConnectionManager#NextConnection.apply"
      ),
      ProblemFilters.exclude[MissingTypesProblem]("org.http4s.blaze.client.Http1Connection"),
      ProblemFilters
        .exclude[IncompatibleMethTypeProblem]("org.http4s.blaze.client.PoolManager.release"),
      ProblemFilters
        .exclude[IncompatibleMethTypeProblem]("org.http4s.blaze.client.PoolManager.invalidate"),
      ProblemFilters
        .exclude[IncompatibleMethTypeProblem]("org.http4s.blaze.client.BasicManager.this"),
      ProblemFilters
        .exclude[IncompatibleMethTypeProblem]("org.http4s.blaze.client.ConnectionManager.pool"),
      ProblemFilters
        .exclude[IncompatibleMethTypeProblem]("org.http4s.blaze.client.ConnectionManager.basic"),
      ProblemFilters
        .exclude[IncompatibleMethTypeProblem]("org.http4s.blaze.client.PoolManager.this"),
      // inside private trait/clas/object
      ProblemFilters
        .exclude[DirectMissingMethodProblem]("org.http4s.blaze.client.BlazeConnection.runRequest"),
      ProblemFilters.exclude[ReversedMissingMethodProblem](
        "org.http4s.blaze.client.BlazeConnection.runRequest"
      ),
      ProblemFilters
        .exclude[DirectMissingMethodProblem]("org.http4s.blaze.client.Http1Connection.runRequest"),
      ProblemFilters
        .exclude[DirectMissingMethodProblem]("org.http4s.blaze.client.Http1Connection.resetWrite"),
      ProblemFilters.exclude[MissingClassProblem]("org.http4s.blaze.client.Http1Connection$Idle"),
      ProblemFilters.exclude[MissingClassProblem]("org.http4s.blaze.client.Http1Connection$Idle$"),
      ProblemFilters.exclude[MissingClassProblem]("org.http4s.blaze.client.Http1Connection$Read$"),
      ProblemFilters
        .exclude[MissingClassProblem]("org.http4s.blaze.client.Http1Connection$ReadWrite$"),
      ProblemFilters.exclude[MissingClassProblem]("org.http4s.blaze.client.Http1Connection$Write$"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "org.http4s.blaze.client.Http1Connection.isRecyclable"
      ),
      ProblemFilters
        .exclude[IncompatibleResultTypeProblem]("org.http4s.blaze.client.Connection.isRecyclable"),
      ProblemFilters
        .exclude[ReversedMissingMethodProblem]("org.http4s.blaze.client.Connection.isRecyclable"),
    ) ++ {
      if (tlIsScala3.value)
        Seq(
          ProblemFilters.exclude[IncompatibleResultTypeProblem](
            "org.http4s.blaze.client.ConnectionManager#NextConnection._1"
          ),
          ProblemFilters
            .exclude[DirectMissingMethodProblem]("org.http4s.blaze.client.BlazeClient.makeClient"),
        )
      else Seq.empty
    },
    Test / scalafixConfig := Some(file(".scalafix.test.conf")),
  )
  .dependsOn(blazeCore % "compile;test->test")

lazy val examples = Project("blaze-examples", file("examples"))
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .settings(Revolver.settings)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-generic" % "0.14.8",
    ),
    Test / scalafixConfig := Some(file(".scalafix.test.conf")),
  )
  .dependsOn(blazeServer, blazeClient)

/* Helper Functions */

// use it in the local development process
addCommandAlias(
  "validate",
  ";scalafmtCheckAll ;scalafmtSbtCheck ;javafmtCheckAll ;+test:compile ;test ;unusedCompileDependenciesTest ;mimaReportBinaryIssues",
)
