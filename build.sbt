import com.typesafe.tools.mima.core._
import Dependencies._

ThisBuild / publishGithubUser := "rossabaker"
ThisBuild / publishFullName := "Ross A. Baker"
ThisBuild / baseVersion := "0.15"

ThisBuild / versionIntroduced := Map(
  "2.13" -> "0.14.5",
  "3.0.0-M3" -> "0.15.0",
  "3.0.0-RC1" -> "0.15.0",
  "3.0.0-RC2" -> "0.15.0",
  "3.0.0-RC3" -> "0.15.0"
)

ThisBuild / crossScalaVersions := Seq("2.12.13", "2.13.5", "3.0.0-RC2", "3.0.0-RC3")
ThisBuild / scalaVersion := crossScalaVersions.value.filter(_.startsWith("2.")).last

lazy val commonSettings = Seq(
  description := "NIO Framework for Scala",
  scalacOptions in Test ~= (_.filterNot(Set("-Ywarn-dead-code", "-Wdead-code"))), // because mockito
  scalacOptions in (Compile, doc) += "-no-link-warnings",
  unmanagedSourceDirectories in Compile ++= {
    (unmanagedSourceDirectories in Compile).value.map { dir =>
      val sv = scalaVersion.value
      CrossVersion.binaryScalaVersion(sv) match {
        case "2.11" | "2.12" => file(dir.getPath ++ "-2.11-2.12")
        case _ => file(dir.getPath ++ "-2.13")
      }
    }
  },
  fork in run := true,
  developers ++= List(
    Developer("bryce-anderson"       , "Bryce L. Anderson"     , "bryce.anderson22@gamil.com"       , url("https://github.com/bryce-anderson")),
    Developer("rossabaker"           , "Ross A. Baker"         , "ross@rossabaker.com"              , url("https://github.com/rossabaker")),
    Developer("ChristopherDavenport" , "Christopher Davenport" , "chris@christopherdavenport.tech"  , url("https://github.com/ChristopherDavenport"))
  ),

  licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")),

  homepage := Some(url("https://github.com/http4s/blaze")),

  scmInfo := Some(
    ScmInfo(
      url("https://github.com/http4s/blaze"),
      "scm:git:https://github.com/http4s/blaze.git",
      Some("scm:git:git@github.com:http4s/blaze.git")
    )
  ),
  startYear := Some(2014),
)

ThisBuild / githubWorkflowJavaVersions := Seq("adopt@1.8")

ThisBuild / githubWorkflowPublishTargetBranches := Seq(
  RefPredicate.Equals(Ref.Branch("main"))
)
ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("validate"))
)

lazy val blaze = project.in(file("."))
  .enablePlugins(Http4sOrgPlugin)
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .aggregate(core, http, examples)

lazy val core = Project("blaze-core", file("core"))
  .enablePlugins(Http4sOrgPlugin)
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(log4s),
    libraryDependencies ++= Seq(
      specs2.withDottyCompat(scalaVersion.value),
      specs2Mock.withDottyCompat(scalaVersion.value),
      logbackClassic
    ).map(_ % Test),
    buildInfoPackage := "org.http4s.blaze",
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      scalaVersion,
      git.gitHeadCommit
    ),
    buildInfoOptions += BuildInfoOption.BuildTime,
    mimaBinaryIssueFilters ++= Seq(
      // private constructor for which there are no sensible defaults
      ProblemFilters.exclude[DirectMissingMethodProblem]("org.http4s.blaze.channel.nio1.NIO1SocketServerGroup.this")
    )
  )

lazy val http = Project("blaze-http", file("http"))
  .enablePlugins(Http4sOrgPlugin)
  .settings(commonSettings)
  .settings(
    // General Dependencies
    libraryDependencies += twitterHPACK,
    // Test Dependencies
    libraryDependencies ++= Seq(
      asyncHttpClient,
      scalacheck.withDottyCompat(scalaVersion.value),
      specs2Scalacheck.withDottyCompat(scalaVersion.value)
    ).map(_ % Test),
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[MissingClassProblem]("org.http4s.blaze.http.http2.PingManager$PingState"),
      ProblemFilters.exclude[MissingClassProblem]("org.http4s.blaze.http.http2.PingManager$PingState$"),
      ProblemFilters.exclude[MissingClassProblem]("org.http4s.blaze.http.http2.client.ALPNClientSelector$ClientProvider"),
      ProblemFilters.exclude[MissingClassProblem]("org.http4s.blaze.http.http2.server.ALPNServerSelector$ServerProvider")
    )
  ).dependsOn(core % "test->test;compile->compile")

lazy val examples = Project("blaze-examples",file("examples"))
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .settings(Revolver.settings)
  .dependsOn(http)

/* Helper Functions */

addCommandAlias("validate", ";scalafmtCheckAll ;javafmtCheckAll ;test ;unusedCompileDependenciesTest ;mimaReportBinaryIssues")
