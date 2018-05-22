package co.cobli.sbt.sentry

import java.io.{File, InputStream}
import java.net.{HttpURLConnection, URL}

import com.typesafe.sbt.SbtNativePackager.Universal
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.archetypes.JavaAppPackaging
import sbt.Keys._
import sbt._


object SentryPlugin extends AutoPlugin {
  import co.cobli.sbt.Compat._

  object Keys {
    val sentryVersion = settingKey[String]("Version of the Sentry agent to use")
    val sentryJavaAgentPackageDir = settingKey[String]("Directory to store the Sentry Java agent in native packages")
    val sentryJavaAgentPaths = taskKey[Map[String, File]]("Local paths of downloaded Sentry Java agent files")
  }

  val autoImport = Keys
  import autoImport._

  private val supportedArchs = Seq("x86", "x86_64")

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

  private def agentBashScriptDefines = Def.task[Seq[String]] {
    val libDir = normalizeBashPath(sentryJavaAgentPackageDir.value)

    Seq(s"""|case "$$(uname -m)" in
            |x86_64)
            |    addJava "-agentpath:$${app_home}/${libDir}/${agentFileName("x86_64")}"
            |*86)
            |    addJava "-agentpath:$${app_home}/${libDir}/${agentFileName("x86")}"
            |*)
            |    echo "Warning: only x86 and x86_64 archs. are supported by the Sentry Java agent, ignoring it" >&2
            |esac
            |""")
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
        Some(agentPaths("x86"))
      case _ =>
        val log = streams.value.log
        log.warn("Unsupported OS/arch combination for Sentry Java agent, ignoring it")
        None
    }

    agentPath.map(path => s"-agentpath:${path}").toSeq
  }

  private def packagingSettings = {
    Seq(
      mappings in Universal ++= agentMappings.value,
      bashScriptExtraDefines ++= agentBashScriptDefines.value
    )
  }

  override def requires = JavaAppPackaging

  override def projectSettings = {
    Seq(
      sentryVersion := "1.7.4",
      sentryJavaAgentPackageDir := s"sentry-agent/",
      sentryJavaAgentPaths := {
        val version = sentryVersion.value
        val destDir = (resourceManaged in Compile).value / "sentry-agent"
        downloadAgents(version, destDir)
      },
      libraryDependencies += "io.sentry" % "sentry-all" % sentryVersion.value,
      javaOptions in run ++= agentOptions.value,
      javaOptions in Test ++= agentOptions.value
    ) ++ packagingSettings
  }
}
