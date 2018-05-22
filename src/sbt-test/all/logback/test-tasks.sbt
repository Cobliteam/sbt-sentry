val checkSentryScript = taskKey[Unit]("")
checkSentryScript := {
  makeBashScripts.value

  val scriptFile = (target in Universal).value / "scripts" / "bin" / name.value
  val scriptContent = IO.read(scriptFile)
  val agentPattern = raw"""addJava.*-agentpath:.*/sentry-agent/libsentry_agent_linux-(\S+?).so""".r

  val actualArchs = agentPattern.findAllMatchIn(scriptContent).map(_.group(1)).toSet
  val expectedArchs = Set("i686", "x86_64")

  if (actualArchs != expectedArchs) {
    sys.error(s"Missing Java options `-agentpath` line for some architectures: ${actualArchs} != ${expectedArchs}")
  }
}

val checkLogbackConfig = taskKey[Unit]("")
checkLogbackConfig := {
  val resourcesFiles = (resources in Compile).value
  val configName = sentryLogbackConfigName.value
  val configFile = resourcesFiles.filter(_.getName == configName).head
  val configContent = IO.read(configFile)
  val appenderPattern = raw"""<appender\s+name="Sentry"\s+class="io\.sentry\.logback\.SentryAppender">""".r.unanchored
  val appenderRefPattern = raw"""<appender-ref\s+ref="Sentry"/>""".r.unanchored

  configContent match {
    case appenderPattern(_*) =>
    case _ => sys.error("Missing Sentry appender in Logback config")
  }

  configContent match {
    case appenderRefPattern(_*) =>
    case _ => sys.error("Missing Sentry appender ref in Logback config")
  }
}
