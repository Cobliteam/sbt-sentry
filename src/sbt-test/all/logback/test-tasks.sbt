val checkSentryScript = taskKey[Unit]("")
checkSentryScript := {
  makeBashScripts.value

  val scriptFile = (target in Universal).value / "scripts" / "bin" / name.value
  val scriptContent = IO.read(scriptFile)
  val agentPattern = raw"""addJava.*-agentpath:.*/sentry-agent/libsentry_agent_linux-(\S+?).so""".r

  val actualArchs = agentPattern.findAllMatchIn(scriptContent).map(_.group(1)).toSet
  val expectedArchs = Set("i686", "x86_64")

  assert(actualArchs == expectedArchs,
         s"Java `-agentpath` option must be present for all architectures: $actualArchs != $expectedArchs")
}

val checkDeps = taskKey[Unit]("")
checkDeps := {
  import java.net.URLClassLoader

  val cp = (fullClasspath in Compile).value
  try {
    val loader = new URLClassLoader(Path.toURLs(cp.files))
    Class.forName("io.sentry.logback.SentryAppender", false, loader)
  } catch { case _: ClassNotFoundException =>
    sys.error("sentry-logback must be present in project dependencies")
  }
}

val checkLogbackConfig = taskKey[Unit]("")
checkLogbackConfig := {
  val resourcesFiles = (resources in Compile).value
  val configName = sentryLogbackConfigName.value
  val configFile = resourcesFiles.filter(_.getName == configName).head
  val configContent = IO.read(configFile)
  val appenderPattern = raw"""<appender\s+name="Sentry"\s+class="io\.sentry\.logback\.SentryAppender">""".r
  val appenderRefPattern = raw"""<appender-ref\s+ref="Sentry"/>""".r

  assert(appenderPattern.findFirstIn(configContent).isDefined,
         "Sentry appender must be present in Logback config")
  assert(appenderRefPattern.findFirstIn(configContent).isDefined,
         "Sentry appender must be reference in root logger in Logback config")
}
