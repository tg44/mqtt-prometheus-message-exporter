package xyz.tg44.prometheus

import akka.actor.ActorSystem
import akka.util.Timeout
import cats.effect.Clock
import cats.{Functor, Id}
import xyz.tg44.prometheus.exporter.{Counter, Gauge, Histogram, Info, Quantile, Registry, Summary}

import concurrent.duration._
import scala.concurrent.ExecutionContext

object Main extends App {
  //init block
  implicit val actorSystem = ActorSystem("test")
  implicit val ec = ExecutionContext.global
  implicit val metricsRegistry = new Registry()

  //simple counter
  val counter = Counter("testC", "A meaningfull description...")

  counter.inc()
  counter.inc()
  counter.inc()
  counter.inc()
  counter.inc()

  printMetrics()

  //simple gauge
  import cats.custom.implicits._
  val gauge = Gauge("testG", "You need to check this!", 10)
  gauge.dec(2.4)

  printMetrics()

  //simple info
  implicit val timeout: Timeout = 500.millis
  val info = Info("testI", "Informative informations", Map("this" -> "important", "that"-> "also important"))

  printMetrics()

  //label example
  import exporter.implicits._
  import cats.implicits._

  object MyLabel extends Enumeration {
    val label1, label2 = Value
  }

  val labeledCounter = Counter.withLabels[MyLabel.type]("labeledC", "This is really useful!")
  labeledCounter.get(Map[MyLabel.Value, String](MyLabel.label1 -> "test")).inc(2)
  labeledCounter.get(Map[MyLabel.Value, String](MyLabel.label1 -> "test2")).inc(3)
  labeledCounter.get(Map[MyLabel.Value, String](MyLabel.label1 -> "test2", MyLabel.label2 -> "test3")).inc(5)

  printMetrics()

  //histogram example
  val histogram = Histogram("testH", "its needed", (1 to 10))
  (1 to 11).foreach(i => (1 to i).foreach(_ => histogram.observe(i)))
  //helpers also available
  val seq = startSequenceFrom(1).withFactor(1.2).numberOfElements(10).generate

  printMetrics()

  //summary example
  val summary = Summary("testS", "", Seq(Quantile(0.5, 0.05), Quantile(0.9, 0.01), Quantile(0.99, 0.001)), 10.seconds, 2)
  //(1 to 1000).foreach(i => summary.observe(i))
  (1 to 10000).foreach(i => summary.observe(i)) // 0.5 ~5000, 0.9 ~9000, 0.99 ~9900

  printMetrics()

  Thread.sleep(9000)
  (10000 to 20000).foreach(i => summary.observe(i)) // 0.5 ~10000, 0.9 ~18000, 0.99 ~19800

  printMetrics()

  Thread.sleep(9000)
  (10000 to 20000).foreach(i => summary.observe(i)) // 0.5 ~15000, 0.9 ~19000, 0.99 ~19900

  printMetrics()

  Thread.sleep(5000)
  System.exit(0)

  def printMetrics() = {
    Thread.sleep(1000)
    println("#"*12)
    metricsRegistry.getState().map(o => println(o.getOrElse("")))
  }
}
