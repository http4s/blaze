import BlazePlugin._

lazy val commonSettings = Seq(
  description := "NIO Framework for Scala",
  scalaVersion := "2.12.11",
  crossScalaVersions := Seq("2.11.12", scalaVersion.value, "2.13.1"),
  scalacOptions := scalacOptionsFor(scalaVersion.value),
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
)

/* Projects */
lazy val blaze = project.in(file("."))
  .enablePlugins(Http4sOrgPlugin)
  .enablePlugins(PrivateProjectPlugin)
  .settings(commonSettings)
  .aggregate(core, http, examples)

lazy val core = Project("blaze-core", file("core"))
  .enablePlugins(Http4sOrgPlugin)
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(TpolecatPlugin)
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
  .disablePlugins(TpolecatPlugin)
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
    ).map(_ % Test)
  ).dependsOn(core % "test->test;compile->compile")

lazy val examples = Project("blaze-examples",file("examples"))
  .enablePlugins(AlpnBootPlugin, PrivateProjectPlugin)
  .disablePlugins(TpolecatPlugin)
  .settings(commonSettings)
  .settings(Revolver.settings)
  .settings(
    alpnBootModule := alpn_boot,
  ).dependsOn(http)

/* Helper Functions */

addCommandAlias("validate", ";scalafmtCheckAll ;test ;unusedCompileDependenciesTest ;mimaReportBinaryIssues")
