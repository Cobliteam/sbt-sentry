import co.cobli.sbt.sentry.SentryPlugin

version := "0.1"
scalaVersion := "2.12.6"

sentryAppRelease := "some-release"
sentryAppDist := "some-dist"
sentryAppPackages := Seq("some-package", "other-package")
sentryExtraProperties ++= Map(
  "custom-prop-1" -> "1",
  "custom-prop-2" -> "2"
)
enablePlugins(SentryPlugin)
