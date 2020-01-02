package xyz.tg44.prometheus.exporter

import java.util.concurrent.TimeUnit

import akka.actor.typed.ActorRef
import xyz.tg44.prometheus.exporter.actors.LabeledMetricActor.{Command, Stop}
import xyz.tg44.prometheus.exporter.Registry.{Metric, MetricMeta}
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import akka.{actor => classic}
import cats.{Functor, Id}
import cats.effect.Clock
import xyz.tg44.prometheus.exporter.actors.{CounterActor, GaugeActor, LabeledMetricActor}

import scala.concurrent.ExecutionContext

class Histogram private[exporter] (
  originalRange: Seq[Double],
  meta: MetricMeta,
  buckets: ActorRef[LabeledMetricActor.Command],
  infBucket: ActorRef[CounterActor.Command],
  sum: ActorRef[GaugeActor.Command],
  count: ActorRef[CounterActor.Command]
)(implicit ec: ExecutionContext, to: Timeout, as: classic.ActorSystem) extends Metric {
  val range = originalRange.sorted(Ordering[Double].reverse)
  import akka.actor.typed.scaladsl.adapter._
  implicit val typedSystem = as.toTyped

  def observe(amount: Double)(implicit clock: Clock[Id]): Unit = {
    range.takeWhile(amount <= _).foreach{ b =>
      val labels = meta.labels + ("le" -> b.toString)
      buckets.ask((ar: ActorRef[LabeledMetricActor.Response[Counter]]) => LabeledMetricActor.Select[Counter](labels, ar)).map(_.m.inc())
    }
    infBucket ! CounterActor.Inc()
    count ! CounterActor.Inc()
    sum ! GaugeActor.ModifyWith(amount, clock.realTime(TimeUnit.MILLISECONDS))
  }

  override def remove(): Unit = {
    sum ! GaugeActor.Stop()
    count ! CounterActor.Stop()
    buckets ! LabeledMetricActor.Stop()
  }
}

object Histogram {
  val mType = "gauge"

  trait implicits {
    case class Linear(first: Double, step: Double, count: Int) {
      def generate: Seq[Double] = {
        @scala.annotation.tailrec
        def rec(c: Int, l: List[Double]): List[Double] = {
          if(c > 0) {
            rec(c-1, (l.head + step) :: l)
          } else {
            l
          }
        }
        rec(count -1, first :: Nil).reverse
      }
    }
    case class Exponential(first: Double, factor: Double, count: Int) {
      def generate: Seq[Double] = {
        @scala.annotation.tailrec
        def rec(c: Int, l: List[Double]): List[Double] = {
          if(c > 0) {
            rec(c-1, (l.head * factor) :: l)
          } else {
            l
          }
        }
        rec(count -1, first :: Nil).reverse
      }
    }
    case class SequenceStart(d: Double){
      def withFactor(factor: Double) = ExponentialStartStep(d, factor)
      def withLinearSteps(step: Double) = LinearStartStep(d, step)
    }
    case class LinearStartStep(start: Double, step: Double) {
      def numberOfElements(count: Int) = Linear(start, step, count)
    }
    case class ExponentialStartStep(start: Double, factor: Double) {
      def numberOfElements(count: Int) = Exponential(start, factor, count)
    }
    def startSequenceFrom(d: Double) = SequenceStart(d)

    implicit def rangeHelper(range: Range): Seq[Double] = range.map(_.toDouble)
    implicit class HistogramFHelper[F[_]](c: F[Histogram]) {
      import cats.implicits._
      def observe(amount: Double = 1)(implicit ev: Functor[F], clock: Clock[Id]): Unit = {
        c.map(_.observe(amount))
      }

      def remove()(implicit ev: Functor[F]): Unit = {
        c.map(_.remove())
      }
    }
  }

  def apply(name: String, description: String, range: Seq[Double])(implicit clock: Clock[Id], to: Timeout, ec: ExecutionContext, as: classic.ActorSystem, registry: Registry): Histogram = {
    val meta = MetricMeta(mType, name, Map.empty, description, Some(name))
    Histogram(meta, range)
  }

  def withLabels[L <: Enumeration](name: String, description: String, range: Seq[Double])(implicit clock: Clock[Id], to: Timeout, ec: ExecutionContext, as: classic.ActorSystem, registry: Registry): LabeledMetric[Histogram, L] = {
    import akka.actor.typed.scaladsl.adapter._
    new LabeledMetric[Histogram, L](as.spawn(
      LabeledMetricActor[Histogram](name, description, Histogram.mType)(mm => Histogram(mm, range)
      ), name))
  }

  private def apply(meta: MetricMeta, range: Seq[Double])(implicit clock: Clock[Id], to: Timeout, ec: ExecutionContext, as: classic.ActorSystem, registry: Registry): Histogram = {
    import akka.actor.typed.scaladsl.adapter._
    val sum = as.spawnAnonymous(actors.GaugeActor(meta.copy(name = s"${meta.name}_sum"), registry))
    val inf = as.spawnAnonymous(actors.CounterActor(meta.copy(name = s"${meta.name}_bucket", labels = meta.labels + ("le"-> "+Inf")), registry))
    val counter = as.spawnAnonymous(actors.CounterActor(meta.copy(name = s"${meta.name}_count"), registry))
    val buckets = as.spawnAnonymous(actors.LabeledMetricActor(meta.copy(name = s"${meta.name}_bucket"))(mm => new Counter(as.spawnAnonymous(actors.CounterActor(mm, registry)))))
    new Histogram(range, meta, buckets, inf, sum, counter)
  }
}
