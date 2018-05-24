package co.cobli.sbt.sentry

import scala.xml._
import scala.xml.transform._

import java.io.File

object LogbackConfig {
  private val sentryAppenderName = "Sentry"

  private def sentryAppender: Elem = {
    <appender name={ sentryAppenderName } class="io.sentry.logback.SentryAppender">
      <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
        <level>WARN</level>
      </filter>
    </appender>
  }

  private def sentryAppenderRef: Elem = {
    <appender-ref ref={ sentryAppenderName }/>
  }

  private def addSentryAppenderRule = new RewriteRule {
    override def transform(n: Node): Seq[Node] = n match {
      case elem: Elem if elem.label == "configuration" =>
        elem.copy(child = sentryAppender ++ elem.child)
      case n => n
    }
  }

  private def addSentryAppenderRefRule = new RewriteRule {
    override def transform(n: Node): Seq[Node] = n match {
      case elem: Elem if elem.label == "root" =>
        elem.copy(child = elem.child ++ sentryAppenderRef)
      case n => n
    }
  }

  private def settingsTransform =
    new RuleTransformer(addSentryAppenderRule, addSentryAppenderRefRule)

  def addSentrySettings(node: Node): Node =
    settingsTransform(node)

  def addSentrySettings(source: File): String = {
    val doc = XML.loadFile(source)
    val newDoc = addSentrySettings(doc.head)
    val p = new PrettyPrinter(80, 2)
    p.format(newDoc)
  }
}
