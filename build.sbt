import BlazePlugin._

/* Global Build Settings */
organization in ThisBuild := "org.http4s"

lazy val commonSettings = Seq(
  description := "NIO Framework for Scala",
  scalaVersion := "2.12.8",
  crossScalaVersions := Seq("2.11.12", scalaVersion.value, "2.13.0-M5", "2.13.0"),
  scalacOptions := scalacOptionsFor(scalaVersion.value),
  scalacOptions := {
    val opts = scalacOptions.value
    // Flags changed between 2.13.0-M5 and 2.13.  Trust that they're
    // well covered elsewhere and disable the ones that aren't on the
    // milestone.
    scalaVersion.value match {
      case "2.13.0-M5" =>
        opts
          .filterNot(Set("-Xlint:deprecation"))
          .filterNot(_.startsWith("-W"))
      case _ =>
        opts
    }
  },
  scalacOptions in Test ~= (_.filterNot(Set("-Ywarn-dead-code", "-Wdead-code"))), // because mockito
  scalacOptions in (Compile, doc) += "-no-link-warnings",
  unmanagedSourceDirectories in Compile ++= {
    (unmanagedSourceDirectories in Compile).value.map { dir =>
      val sv = scalaVersion.value
      CrossVersion.binaryScalaVersion(sv) match {
        // 2.13.0-M5 doesn't have the JdkConverters introduced by 2.13.0
        case "2.11" | "2.12" | "2.13.0-M5" => file(dir.getPath ++ "-2.11-2.12")
        case _ => file(dir.getPath ++ "-2.13")
      }
    }
  },
)

/* Projects */
lazy val blaze = project.in(file("."))
    .disablePlugins(MimaPlugin)
    .settings(
      cancelable in Global := true,
      skip in publish := true,
    )
    .settings(commonSettings)
    .aggregate(core, http, examples)

lazy val core = Project("blaze-core", file("core"))
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(TpolecatPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(log4s(scalaVersion.value)),
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
  .disablePlugins(MimaPlugin, TpolecatPlugin)
  .settings(commonSettings)
  .settings(Revolver.settings)
  .settings(
    // necessary to add ALPN classes to boot classpath
    fork := true,
    // Adds ALPN to the boot classpath for Http2 support
    libraryDependencies += alpn_boot % Runtime,
    javaOptions in run ++= addAlpnPath((managedClasspath in Runtime).value),
    skip in publish := true
  ).dependsOn(http)


/* Helper Functions */

def addAlpnPath(attList: Keys.Classpath): Seq[String] = {
  for {
    file <- attList.map(_.data)
    path = file.getAbsolutePath if path.contains("jetty.alpn")
  } yield { println(s"Alpn path: $path"); "-Xbootclasspath/p:" + path}
}

addCommandAlias("validate", ";test ;unusedCompileDependenciesTest ;mimaReportBinaryIssues")
