val checkSentryProperties = taskKey[Unit]("")
checkSentryProperties := {
  import java.util.Properties
  import scala.collection.JavaConverters._

  val resourcesFiles = (resources in Compile).value
  val propsFile = resourcesFiles.filter(_.getName == "sentry.properties").head
  val actualMap = IO.reader(propsFile) { reader =>
    val p = new Properties
    p.load(reader)
    p.asScala
  }
  val expectedMap = Map(
    "release" -> "some-release",
    "dist" -> "some-dist",
    "stacktrace.app.packages" -> "some-package,other-package",
    "custom-prop-1" -> "1",
    "custom-prop-2" -> "2"
  )

  if (actualMap != expectedMap) {
    sys.error(s"Sentry properties differ from expected: $actualMap != $expectedMap")
  }
}
