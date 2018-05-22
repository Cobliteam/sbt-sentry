package co.cobli.sbt.sentry

import java.io.{File, InputStream}
import java.net.{HttpURLConnection, URL}

import com.typesafe.sbt.SbtNativePackager.Universal
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.archetypes.scripts.BashStartScriptPlugin
import sbt.Keys._
import sbt._


object SentryPlugin extends AutoPlugin {
  import co.cobli.sbt.Compat._

  object Keys {
    val sentryVersion = settingKey[String]("Version of the Sentry agent to use")
    val sentryJavaAgentPackageDir = settingKey[String]("Directory to store the Sentry Java agent in native packages")

    val sentryLogbackEnabled = settingKey[Boolean]("Automatically modify the Logback configuration for Sentry")
    val sentryLogbackConfigName = settingKey[String]("Name of the Logback config file to generate for Sentry")
    val sentryLogbackSource = settingKey[Option[File]]("Custom Logback config file to use as a base for Sentry")

    val sentryJavaAgentPaths = taskKey[Map[String, File]]("")
    val sentryJavaAgentBashDefines = taskKey[Seq[String]]("")
    val sentryLogbackSourcePath = taskKey[File]("")
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

  /* The following are based on code from the sbt-javagent plugin */

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
      |    addJava "-agentpath:$${app_home}/${dir}/${agentFileName("x86_64")}" ;;
      |*86)
      |    addJava "-agentpath:$${app_home}/${dir}/${agentFileName("i686")}" ;;
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

  private def generateLogbackConfig = Def.taskDyn[Seq[File]] {
    val enabled = sentryLogbackEnabled.value
    if (!enabled) {
      Def.task {
        Seq.empty
      }
    } else {
      Def.task {
        val sourceFile = sentryLogbackSourcePath.value
        val destName = sentryLogbackConfigName.value
        val destFile = (resourceManaged in Compile).value / "sentry-logback" / destName

        try {
          val content = LogbackConfig.addSentrySettings(sourceFile)
          IO.write(destFile, content)
        } catch { case e: Exception =>
          sys.error(s"Failed to generate Sentry Logback config: ${e}")
        }

        Seq(destFile)
      }
    }
  }

  private def packagingSettings = Seq(
    mappings in Universal ++= agentMappings.value,
    bashScriptExtraDefines ++= sentryJavaAgentBashDefines.value
  )

  override def requires = BashStartScriptPlugin

  override def projectSettings = {
    Seq(
      sentryVersion := "1.7.4",
      sentryJavaAgentPackageDir := s"sentry-agent",
      sentryLogbackEnabled := false,
      sentryLogbackConfigName := "logback.xml",
      sentryLogbackSource := None,

      libraryDependencies += "io.sentry" % "sentry-all" % sentryVersion.value,
      javaOptions in run ++= agentOptions.value,
      javaOptions in Test ++= agentOptions.value,
      resourceGenerators in Compile += generateLogbackConfig.taskValue,

      sentryJavaAgentPaths := {
        val version = sentryVersion.value
        val destDir = (resourceManaged in Compile).value / "sentry-agent"
        downloadAgents(version, destDir)
      },
      sentryJavaAgentBashDefines := {
        val pkgDir = sentryJavaAgentPackageDir.value
        agentBashScriptDefines(pkgDir)
      },
      sentryLogbackSourcePath := {
        val customSource = sentryLogbackSource.value
        customSource.getOrElse {
          val resources = (unmanagedResources in Compile).value
          resources.filter(_.getName == "logback.xml").head
        }
      }
    ) ++ packagingSettings
  }
}
