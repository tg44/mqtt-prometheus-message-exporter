package xyz.tg44.prometheus

import java.io.PrintStream
import java.util.logging.Level

import org.slf4j.bridge.SLF4JBridgeHandler

object LogBridge {

  def initLogBridge(): Unit = {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
    java.util.logging.Logger.getLogger("").setLevel(Level.FINEST)
    System.setErr(new PrintStream((b: Int) => {}))
  }
}
