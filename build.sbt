import com.typesafe.tools.mima.core._
import Dependencies._

val Scala212 = "2.12.15"
val Scala213 = "2.13.8"
val Scala3 = "3.0.2"

ThisBuild / crossScalaVersions := Seq(Scala212, Scala213, Scala3)
ThisBuild / scalaVersion := crossScalaVersions.value.filter(_.startsWith("2.")).last
ThisBuild / tlBaseVersion := "0.15"

ThisBuild / tlVersionIntroduced := Map(
  "2.13" -> "0.14.5",
  "3" -> "0.15.0",
)

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
  developers ++= List(
    Developer(
      "bryce-anderson",
      "Bryce L. Anderson",
      "bryce.anderson22@gamil.com",
      url("https://github.com/bryce-anderson")),
    Developer(
      "rossabaker",
      "Ross A. Baker",
      "ross@rossabaker.com",
      url("https://github.com/rossabaker")),
    Developer(
      "ChristopherDavenport",
      "Christopher Davenport",
      "chris@christopherdavenport.tech",
      url("https://github.com/ChristopherDavenport"))
  ),
  startYear := Some(2014),
)

// currently only publishing tags
ThisBuild / githubWorkflowPublishTargetBranches :=
  Seq(RefPredicate.StartsWith(Ref.Tag("v")), RefPredicate.Equals(Ref.Branch("main")))

ThisBuild / githubWorkflowBuild ++= Seq(
  WorkflowStep.Sbt(List("${{ matrix.ci }}", "javafmtCheckAll"), name = Some("Check Java formatting")),
)

lazy val blaze = project
  .in(file("."))
  .enablePlugins(Http4sOrgPlugin)
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .aggregate(core, http, examples)

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
      git.gitHeadCommit
    ),
    buildInfoOptions += BuildInfoOption.BuildTime,
    mimaBinaryIssueFilters ++= Seq(
      // private constructor for which there are no sensible defaults
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "org.http4s.blaze.channel.nio1.NIO1SocketServerGroup.this")
    )
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
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.blaze.http.http2.PingManager$PingState"),
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.blaze.http.http2.PingManager$PingState$"),
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.blaze.http.http2.client.ALPNClientSelector$ClientProvider"),
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.blaze.http.http2.server.ALPNServerSelector$ServerProvider")
    )
  )
  .dependsOn(testkit % Test, core % "test->test;compile->compile")

lazy val examples = Project("blaze-examples", file("examples"))
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .settings(Revolver.settings)
  .dependsOn(http)

/* Helper Functions */

// use it in the local development process
addCommandAlias(
  "validate",
  ";scalafmtCheckAll ;scalafmtSbtCheck ;javafmtCheckAll ;+test:compile ;test ;unusedCompileDependenciesTest ;mimaReportBinaryIssues")
