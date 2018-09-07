import BlazePlugin._

/* Global Build Settings */
organization in ThisBuild := "org.http4s"

lazy val commonSettings = Seq(
  version := "0.14.0-M4",
  description := "NIO Framework for Scala",
  crossScalaVersions := Seq("2.11.11", scalaVersion.value),
  scalacOptions in (Compile, doc) ++= Seq("-no-link-warnings") // Suppresses problems with Scaladoc @throws links
  //  as discussed in http://www.scala-archive.org/Scaladoc-2-11-quot-throws-tag-quot-cannot-find-any-member-to-link-td4641850.html
)

/* Projects */
lazy val blaze = project.in(file("."))
    .enablePlugins(DisablePublishingPlugin)
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
  ).dependsOn(core % "test->test;compile->compile")

lazy val examples = Project("blaze-examples",file("examples"))
  .enablePlugins(DisablePublishingPlugin)
  .disablePlugins(TpolecatPlugin)
  .settings(commonSettings)
  .settings(Revolver.settings)
  .settings(
    // necessary to add ALPN classes to boot classpath
    fork := true,
    // Adds ALPN to the boot classpath for Http2 support
    libraryDependencies += alpn_boot,
    javaOptions in run ++= addAlpnPath((managedClasspath in Runtime).value)

  ).dependsOn(http)


/* Helper Functions */

def addAlpnPath(attList: Keys.Classpath): Seq[String] = {
  for {
    file <- attList.map(_.data)
    path = file.getAbsolutePath if path.contains("jetty.alpn")
  } yield { println(s"Alpn path: $path"); "-Xbootclasspath/p:" + path}
}

addCommandAlias("validate", ";test ;mimaReportBinaryIssues")
