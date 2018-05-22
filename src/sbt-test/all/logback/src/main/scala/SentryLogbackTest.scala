import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

object SentryLogbackTest extends App {
    val logger = LoggerFactory.getLogger("example.Application");

    logger.debug("Debug message");
    logger.info("Info message");
    logger.warn("Warn message");

    try {
        1 / 0;
    } catch { case e: Exception =>
        logger.error("Caught exception!", e);
    }
}
