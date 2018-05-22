import co.cobli.sbt.sentry.SentryPlugin

version := "0.1"
scalaVersion := "2.12.6"

sentryLogbackEnabled := true
sentryLogbackConfigName := "logback.sentry.xml"
enablePlugins(SentryPlugin)

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-api" % "1.7.25",
  "ch.qos.logback" % "logback-classic" % "1.2.3"
)
