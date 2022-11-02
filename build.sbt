import Dependencies._

val Scala213 = "2.13.10"
val Scala3 = "3.2.0"
val http4sVersion = "1.0.0-M37"
val munitCatsEffectVersion = "2.0.0-M3"

ThisBuild / resolvers +=
  "s01 snapshots".at("https://s01.oss.sonatype.org/content/repositories/snapshots/")

ThisBuild / crossScalaVersions := Seq(Scala3, Scala213)
ThisBuild / scalaVersion := crossScalaVersions.value.filter(_.startsWith("2.")).last
ThisBuild / tlBaseVersion := "1.0"
ThisBuild / tlFatalWarningsInCi := !tlIsScala3.value // See SSLStage

// 11 and 17 blocked by https://github.com/http4s/blaze/issues/376
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("8"))

ThisBuild / developers ++= List(
  Developer(
    "bryce-anderson",
    "Bryce L. Anderson",
    "bryce.anderson22@gmail.com",
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
  )
  .dependsOn(testkit % Test, core % "test->test;compile->compile")

lazy val blazeCore = Project("http4s-blaze-core", file("blaze-core"))
  .settings(
    description := "Base library for binding blaze to http4s clients and servers",
    startYear := Some(2014),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
      logbackClassic % Test,
    ),
    Test / scalafixConfig := Some(file(".scalafix.test.conf")),
  )
  .dependsOn(http)

lazy val blazeServer = Project("http4s-blaze-server", file("blaze-server"))
  .settings(
    description := "blaze implementation for http4s servers",
    startYear := Some(2014),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
    ),
    Test / scalafixConfig := Some(file(".scalafix.test.conf")),
  )
  .dependsOn(blazeCore % "compile;test->test")

lazy val blazeClient = Project("http4s-blaze-client", file("blaze-client"))
  .settings(
    description := "blaze implementation for http4s clients",
    startYear := Some(2014),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-client" % http4sVersion,
      "org.http4s" %% "http4s-client-testkit" % http4sVersion % Test,
    ),
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
      "io.circe" %% "circe-generic" % "0.14.3",
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
