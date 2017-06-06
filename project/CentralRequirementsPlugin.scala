package org.http4s.build

import sbt._, Keys._
import verizon.build.RigPlugin
import xerial.sbt.Sonatype.autoImport.sonatypeProfileName

object CentralRequirementsPlugin extends AutoPlugin {

  // tells sbt to automatically enable this plugin where ever
  // the sbt-rig plugin is enabled (which should be all sub-modules)
  override def trigger = allRequirements

  override def requires = RigPlugin

  override lazy val projectSettings = Seq(
    sonatypeProfileName := "org.http4s",

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
    startYear := Some(2014)
  )

}
