package co.cobli.sbt.sentry

import java.io.{File, InputStream}
import java.net.{HttpURLConnection, URL}
import java.util.Properties

import com.typesafe.sbt.SbtNativePackager.Universal
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.archetypes.scripts.BashStartScriptPlugin
import sbt.Keys._
import sbt._


object SentryPlugin extends AutoPlugin {
  import co.cobli.sbt.Compat._

  object Keys {
    val sentryVersion = settingKey[String]("Sentry: version of the agent and libs to use")

    val sentryAppRelease = settingKey[String]("Sentry: release to store in properties file")
    val sentryAppDist = settingKey[String]("Sentry: dist to store in properties file")
    val sentryAppPackages = settingKey[Seq[String]]("Sentry: list of app packages to store in properties file")
    val sentryExtraProperties = settingKey[Map[String ,String]]("Sentry: values to add to sentry.properties resource")

    val sentryJavaAgentPackageDir = settingKey[String]("Sentry: directory to store the Java agent files when packaging")

    val sentryLogbackEnabled = settingKey[Boolean]("Sentry: automatically modify Logback config in the classpath")
    val sentryLogbackConfigName = settingKey[String]("Sentry: name of the new Logback config to generate")
    val sentryLogbackSource = taskKey[File]("Sentry: Logback config file to use as a base.")
    val sentryLogbackLogLevel = settingKey[String]("Sentry: minimum log level to report errors")

    val sentryProperties = taskKey[Map[String, String]]("")
    val sentryJavaAgentPaths = taskKey[Map[String, File]]("")
    val sentryJavaAgentBashDefines = taskKey[Seq[String]]("")
  }

  val autoImport = Keys
  import autoImport._

  private val supportedArchs = Seq("i686", "x86_64")

  private def agentFileName(arch: String) =
    s"libsentry_agent_linux-${arch}.so"

  private def agentUrl(version: String, arch: String) =
    new URL(s"https://github.com/getsentry/sentry-java/releases/download/v${version}/${agentFileName(arch)}")

  private def agentDownloadFile(version: String, arch: String, destDir: File): File =
    destDir / s"${agentFileName(arch)}-${version}"

  private def httpConnection = {
    val open = (u: URL) => {
      val conn = u.openConnection().asInstanceOf[HttpURLConnection]
      conn.connect()
      (conn, conn.getInputStream())
    }
    val close: ((HttpURLConnection, InputStream)) => Unit = { case (conn, stream) =>
      stream.close()
      conn.disconnect()
    }

    Using.resource(open, close)
  }

  private def downloadAgents(version: String, destDir: File): Map[String, File] = {
    supportedArchs.map { arch =>
      val destFile = agentDownloadFile(version, arch, destDir)

      httpConnection(agentUrl(version, arch)) { case (conn, contentStream) =>
        val lastModified = conn.getLastModified
        if (destFile.length != conn.getContentLengthLong || destFile.lastModified != lastModified) {
          IO.transfer(contentStream, destFile)
          destFile.setLastModified(lastModified)
        }

        arch -> destFile
      }
    }.toMap
  }

  /* The following 3 functions are based on code from the sbt-javagent plugin */

  private def normalizePath(path: String, expected: Char, actual: Char = File.separatorChar): String = {
    if (actual == expected) path else path.replace(actual, expected)
  }

  private def normalizeBashPath(path: String, separator: Char = File.separatorChar): String =
    normalizePath(path, '/')

  private def agentBashScriptDefines(packageDir: String): Seq[String] = {
    val dir = normalizeBashPath(packageDir)

    Seq(s"""
      |case "$$(uname -m)" in
      |x86_64)
      |    addJava "-agentpath:$${app_home}/../${dir}/${agentFileName("x86_64")}" ;;
      |*86)
      |    addJava "-agentpath:$${app_home}/../${dir}/${agentFileName("i686")}" ;;
      |*)
      |    echo "Warning: only x86 and x86_64 archs. are supported by the Sentry Java agent, ignoring it" >&2
      |esac
      |""".stripMargin)
  }

  private def agentMappings = Def.task[Seq[(File, String)]] {
    val libDir = sentryJavaAgentPackageDir.value
    sentryJavaAgentPaths.value.toSeq.map { case (arch, agentFile) =>
      agentFile -> s"${libDir}/${agentFileName(arch)}"
    }
  }

  private def agentOptions = Def.task[Seq[String]] {
    val agentPaths = sentryJavaAgentPaths.value
    val agentPath = (System.getProperty("os.name"), System.getProperty("os.arch")) match {
      case ("Linux", "amd64") =>
        Some(agentPaths("x86_64"))
      case ("Linux", "x86") =>
        Some(agentPaths("i686"))
      case _ =>
        val log = streams.value.log
        log.warn("Unsupported OS/arch combination for Sentry Java agent, ignoring it")
        None
    }

    agentPath.map(path => s"-agentpath:${path}").toSeq
  }

  private def generateLogbackConfig = Def.task[File] {
    val sourceFile = sentryLogbackSource.value
    val destFile = resourceManaged.value / "sentry" / sentryLogbackConfigName.value
    val logLevel = sentryLogbackLogLevel.value

    try {
      val content = new LogbackConfig(logLevel).addSentrySettings(sourceFile)
      IO.write(destFile, content)
    } catch { case e: Exception =>
      sys.error(s"Failed to generate Sentry Logback config: ${e}")
    }

    destFile
  }

  private def defaultProperties = Def.task[Map[String, String]] {
    Map(
      "release" -> sentryAppRelease.value,
      "dist" -> sentryAppDist.value,
      "stacktrace.app.packages" -> sentryAppPackages.value.mkString(",")
    )
  }

  private def generatePropertiesFile = Def.task[File] {
    val destFile = resourceManaged.value / "sentry" / "sentry.properties"
    val props = sentryProperties.value.foldLeft(new Properties) { case (p, (key, value)) =>
      p.put(key, value)
      p
    }
    val projectName = name.value

    try {
      IO.write(props, s"Sentry Settings for $projectName", destFile)
    } catch { case e: Exception =>
      sys.error(s"Failed to generate Sentry properties file: ${e}")
    }

    destFile
  }

  private def packagingSettings = Seq(
    mappings in Universal ++= agentMappings.value,
    bashScriptExtraDefines ++= sentryJavaAgentBashDefines.value
  )

  override def requires = BashStartScriptPlugin

  override def projectSettings = {
    Seq(
      sentryVersion := "1.7.4",
      sentryAppRelease := version.value,
      sentryAppDist := "jvm",
      sentryAppPackages := Seq(organization.value),
      sentryExtraProperties := Map.empty,

      sentryJavaAgentPackageDir := s"sentry-agent",

      sentryLogbackEnabled := false,
      sentryLogbackConfigName := "logback.xml",
      sentryLogbackSource := {
        val curResources = (unmanagedResources in Compile).value
        curResources.filter(_.getName == "logback.xml").headOption.getOrElse {
          sys.error("Failed to find logback.xml in classpath. Specify the path manually by setting `sentryLogbackSource`.")
        }
      },
      sentryLogbackLogLevel := "WARN",

      libraryDependencies += "io.sentry" % "sentry" % sentryVersion.value,
      libraryDependencies ++= {
        if (sentryLogbackEnabled.value) {
          Seq("io.sentry" % "sentry-logback" % sentryVersion.value)
        } else {
          Seq.empty
        }
      },

      resourceGenerators in Compile += Def.taskDyn[Seq[File]] {
        if (sentryLogbackEnabled.value) {
          Def.task(Seq(generateLogbackConfig.value))
        } else {
          Def.task(Seq.empty)
        }
      }.taskValue,
      resourceGenerators in Compile += Def.task {
        Seq(generatePropertiesFile.value)
      }.taskValue,

      sentryProperties := {
        defaultProperties.value ++ sentryExtraProperties.value
      },
      sentryJavaAgentPaths := {
        val version = sentryVersion.value
        val destDir = (resourceManaged in Compile).value / "sentry"
        downloadAgents(version, destDir)
      },
      sentryJavaAgentBashDefines := {
        val pkgDir = sentryJavaAgentPackageDir.value
        agentBashScriptDefines(pkgDir)
      }
    ) ++ packagingSettings
  }
}
