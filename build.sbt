import BlazePlugin._

/* Global Build Settings */
organization in ThisBuild := "org.http4s"

lazy val commonSettings = Seq(
  description := "NIO Framework for Scala",
  scalaVersion := "2.12.8",
  crossScalaVersions := Seq("2.11.12", scalaVersion.value, "2.13.0-M5"),
  scalacOptions := scalacOptionsFor(scalaVersion.value),
  scalacOptions in Test -= "-Ywarn-dead-code", // because mockito
  scalacOptions in (Compile, doc) += "-no-link-warnings"
)

/* Projects */
lazy val blaze = project.in(file("."))
    .enablePlugins(DisablePublishingPlugin)
    .disablePlugins(MimaPlugin)
    .settings(cancelable in Global := true)
    .settings(commonSettings)
    .aggregate(core, http, examples)

lazy val core = Project("blaze-core", file("core"))
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
  .settings(commonSettings)
  .disablePlugins(TpolecatPlugin)
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
  .enablePlugins(DisablePublishingPlugin)
  .disablePlugins(MimaPlugin, TpolecatPlugin)
  .settings(commonSettings)
  .settings(Revolver.settings)
  .settings(
    // necessary to add ALPN classes to boot classpath
    fork := true,
    // Adds ALPN to the boot classpath for Http2 support
    libraryDependencies += alpn_boot % Runtime,
    javaOptions in run ++= addAlpnPath((managedClasspath in Runtime).value)

  ).dependsOn(http)


/* Helper Functions */

def addAlpnPath(attList: Keys.Classpath): Seq[String] = {
  for {
    file <- attList.map(_.data)
    path = file.getAbsolutePath if path.contains("jetty.alpn")
  } yield { println(s"Alpn path: $path"); "-Xbootclasspath/p:" + path}
}

addCommandAlias("validate", ";test ;coverageOff ;unusedCompileDependenciesTest ;mimaReportBinaryIssues")
