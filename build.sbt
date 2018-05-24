name := "sbt-sentry"
organization := "co.cobli"
licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html"))

scalacOptions ++= Seq("-deprecation")
javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

sbtPlugin := true
scalaVersion := "2.12.6"
crossSbtVersions := Vector("0.13.17", "1.1.2")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.4" % "provided")

libraryDependencies ++= {
  (pluginCrossBuild / sbtVersion).value match {
    case v if v.startsWith("1.") =>
      Seq("org.scala-sbt" %% "io" % "1.0.0",
          "org.scala-lang.modules" %% "scala-xml" % "1.0.6")
    case _ =>
      Seq.empty
  }
}

enablePlugins(GitVersioning)
git.useGitDescribe := true
git.uncommittedSignifier := None
git.gitTagToVersionNumber := {
  case v if v.startsWith("v") => v.drop(1) match {
    case VersionNumber(Seq(x, y, z), Seq(), Seq()) => Some(s"$x.$y.$z")
    case VersionNumber(Seq(x, y, z), Seq(since, commit), Seq()) => Some(s"$x.$y.${z + 1}-$since+$commit")
    case _ => None
  }
}

publishMavenStyle := true
bintrayOrganization := Some("cobli")
bintrayRepository := "sbt-plugins"

scriptedDependencies := (ScriptedConf / publishLocal).value
scriptedLaunchOpts += "-Dplugin.version=" + version.value
scriptedBufferLog := false
