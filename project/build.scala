import sbt._
import Keys._

import sbtassembly.Plugin._
import AssemblyKeys._
import spray.revolver.RevolverPlugin._

object ApplicationBuild extends Build {

  lazy val buildSettings = Defaults.defaultSettings ++ Revolver.settings ++ Seq(
    organization := "brycea",
    version := "0.0.1-SNAPSHOT",
    scalaVersion in ThisBuild := "2.10.3",

    libraryDependencies += scalatest % "test",
    libraryDependencies += scalameter,
    libraryDependencies += scalaloggingSlf4j,
    libraryDependencies += logbackClassic,

    mainClass in Revolver.reStart := Some("blaze.examples.SSLHttpServer"),
    fork := true,
    javaOptions in run += "-Djavax.net.debug=all"
  )

  lazy val main = Project("blaze",
                    new File("."),
                    settings = buildSettings ++ assemblySettings
    ).settings(
      mainClass in assembly := Some("blaze.examples.HttpServer"),
      mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
        {
          case x if x.endsWith("MANIFEST.MF")     => 
            println("Found : " + x.getClass)
            MergeStrategy.discard
          case x if x.endsWith("rootdoc.txt")     => 
            println("Found : " + x)
            MergeStrategy.concat
          case x => old(x)
        }
      }
    )
    
  lazy val http4s = Project("http4s",
                      new File("http4s"),
                      settings = buildSettings ++ assemblySettings
              ).dependsOn(main, http4score, http4sdsl)
   
   lazy val scalatest  = "org.scalatest"  %% "scalatest" % "2.0.RC3"
   lazy val scalameter = "com.github.axel22" % "scalameter_2.10" % "0.4"
   
   lazy val scalaloggingSlf4j   = "com.typesafe"   %% "scalalogging-slf4j" % "1.0.1"
   lazy val logbackClassic      = "ch.qos.logback" %  "logback-classic"    % "1.0.9"
   
   lazy val http4score = ProjectRef(file("../http4s"), "core")
   lazy val http4sdsl = ProjectRef(file("../http4s"), "dsl")
            
}
