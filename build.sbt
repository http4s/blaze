import BlazePlugin._

/* Global Build Settings */
organization in ThisBuild := "org.http4s"

lazy val commonSettings = Seq(
  version := "0.14.0-SNAPSHOT",
  description := "NIO Framework for Scala",
  crossScalaVersions := Seq("2.10.6", scalaVersion.value, "2.12.2")
)



/* Projects */
lazy val blaze = project.in(file("."))
    .enablePlugins(DisablePublishingPlugin)
    .settings(cancelable in Global := true)
    .aggregate(core, http, examples)

lazy val core = Project("blaze-core", file("core"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(log4s),
    libraryDependencies ++= Seq(
      specs2,
      specs2Mock,
      logbackClassic
    ).map(_ % Test)
  )

lazy val http = Project("blaze-http", file("http"))
  .settings(commonSettings)
  .settings(
    // General Dependencies
    libraryDependencies ++= Seq(
      http4sWebsocket,
      twitterHPACK,
      alpn_api
    ),
    // Version Specific Dependencies
    libraryDependencies ++= {
      VersionNumber(scalaBinaryVersion.value).numbers match {
        case Seq(2, 10) => Seq.empty
        case _ => Seq(scalaXml)
      }
    },
    // Test Dependencies
    libraryDependencies ++= Seq(
      asyncHttpClient,
      scalacheck,
      specs2Scalacheck
    ).map(_ % Test)
  )
  .dependsOn(core % "test->test;compile->compile")

lazy val examples = Project("blaze-examples",file("examples"))
  .enablePlugins(DisablePublishingPlugin)
  .settings(commonSettings)
  .settings(Revolver.settings)
  .settings(
    // necessary to add ALPN classes to boot classpath
    fork := true,
    // Adds ALPN to the boot classpath for Http2 support
    javaOptions in run ++= addAlpnPath((managedClasspath in Runtime).value)

  ).dependsOn(http)


/* Helper Functions */

def addAlpnPath(attList : Keys.Classpath): Seq[String] = {
  for {
    file <- attList.map(_.data)
    path = file.getAbsolutePath if path.contains("jetty.alpn")
  } yield { println(s"Alpn patth: $path"); "-Xbootclasspath/p:" + path}
}

addCommandAlias("validate", ";test ;mimaReportBinaryIssues")
