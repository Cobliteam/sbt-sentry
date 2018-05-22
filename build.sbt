name := "sbt-sentry"
organization := "co.cobli"
licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html"))

Compile / scalacOptions ++= Seq("-deprecation")
javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

sbtPlugin := true
Global / scalaVersion := "2.12.6"
crossSbtVersions := Vector("0.13.17", "1.1.2")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.4" % "provided")
libraryDependencies ++= {
  (pluginCrossBuild / sbtVersion).value match {
    case v if v.startsWith("1.") =>
      Seq("org.scala-sbt" %% "io" % "1.0.0")
    case _ =>
      Seq.empty
  }
}

publishMavenStyle := true
bintrayOrganization := Some("cobli")
bintrayRepository := "sbt-plugins"
