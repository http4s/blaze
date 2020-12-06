import com.typesafe.tools.mima.core._
import BlazePlugin._

// Global settings
ThisBuild / crossScalaVersions := Seq("2.11.12", "2.12.12", "2.13.3")
ThisBuild / scalaVersion := (ThisBuild / crossScalaVersions).value.filter(_.startsWith("2.")).last
ThisBuild / baseVersion := "0.14"
ThisBuild / publishGithubUser := "rossabaker"
ThisBuild / publishFullName   := "Ross A. Baker"

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
  // blaze_2.13 debuted in 0.14.5
  versionIntroduced := Map("2.13" -> "0.14.5")
)

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
      specs2,
      specs2Mock,
      logbackClassic
    ).map(_ % Test),
    buildInfoPackage := "org.http4s.blaze",
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      scalaVersion,
      git.gitHeadCommit
    ),
    buildInfoOptions += BuildInfoOption.BuildTime
  )

lazy val http = Project("blaze-http", file("http"))
  .enablePlugins(Http4sOrgPlugin)
  .settings(commonSettings)
  .settings(
    // General Dependencies
    libraryDependencies ++= Seq(
      twitterHPACK,
      alpn_api
    ),
    // Test Dependencies
    libraryDependencies ++= Seq(
      asyncHttpClient,
      scalacheck,
      specs2Scalacheck
    ).map(_ % Test),
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[MissingClassProblem]("org.http4s.blaze.http.http2.PingManager$PingState"),
      ProblemFilters.exclude[MissingClassProblem]("org.http4s.blaze.http.http2.PingManager$PingState$")
    )
  ).dependsOn(core % "test->test;compile->compile")

lazy val examples = Project("blaze-examples",file("examples"))
  .enablePlugins(AlpnBootPlugin, NoPublishPlugin)
  .settings(commonSettings)
  .settings(Revolver.settings)
  .settings(
    alpnBootModule := alpn_boot,
  ).dependsOn(http)

/* Helper Functions */

addCommandAlias("validate", ";scalafmtCheckAll ;javafmtCheckAll ;test ;unusedCompileDependenciesTest ;mimaReportBinaryIssues")
