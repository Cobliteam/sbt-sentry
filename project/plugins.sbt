import scala.sys.process._

lazy val build = (project in file(".")).dependsOn(ProjectRef(cobliSbtPlugin, "cobli-sbt-settings"))
lazy val cobliSbtPluginVersion = "3.1"
lazy val cobliSbtPlugin = {
  val localPath = sys.props.get("cobli.sbt-settings.path").orElse(sys.env.get("COBLI_SBT_SETTINGS_PATH"))
  localPath.map { path =>
    uri(s"file://$path")
  }.getOrElse {
    if ((Seq("sh", "-c", "git remote get-url $(git remote | head -n1) >&2 | grep -Eq 'https?://'") !) == 0)
      uri(s"https://github.com/Cobliteam/cobli-sbt-settings.git#v$cobliSbtPluginVersion")
    else
      uri(s"ssh://git@github.com/Cobliteam/cobli-sbt-settings.git#v$cobliSbtPluginVersion")
  }
}

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value
