package xyz.tg44.prometheus.exporter

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import cats.{Functor, Id}
import cats.effect.Clock
import xyz.tg44.prometheus.exporter.actors.GaugeActor.{Command, ModifyWith, SetTo, Stop}
import xyz.tg44.prometheus.exporter.Registry.{Metric, MetricMeta}
import xyz.tg44.prometheus.exporter.actors.{GaugeActor, LabeledMetricActor}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

class Gauge private[exporter] (actor: ActorRef[Command]) extends Metric {

  def inc(amount: Double = 1)(implicit clock: Clock[Id]): Unit = {
    actor ! ModifyWith(amount, clock.realTime(TimeUnit.MILLISECONDS))
  }

  def dec(amount: Double = 1)(implicit clock: Clock[Id]): Unit = {
    actor ! ModifyWith(-1 * amount, clock.realTime(TimeUnit.MILLISECONDS))
  }

  def set(amount: Double)(implicit clock: Clock[Id]): Unit = {
    actor ! SetTo(amount, clock.realTime(TimeUnit.MILLISECONDS))
  }

  def setToCurrentTime()(implicit clock: Clock[Id]): Unit = {
    actor ! ModifyWith(0, clock.realTime(TimeUnit.MILLISECONDS))
  }

  def measure[T](f: => T)(implicit clock: Clock[Id]): T = {
    val start = clock.monotonic(TimeUnit.SECONDS)
    val ret = f
    val end = clock.monotonic(TimeUnit.SECONDS)
    actor ! SetTo(end - start, clock.realTime(TimeUnit.MILLISECONDS))
    ret
  }

  override def remove(): Unit = {
    actor ! Stop()
  }
}

object Gauge {
  val mType = "gauge"

  trait implicits {
    implicit class GaugeFutureHelper[T](f: => Future[T]) {
      def measureWith(g: Gauge)(implicit clock: Clock[Id], ec: ExecutionContext): Future[T] = {
        val start = clock.monotonic(TimeUnit.SECONDS)
        val ret = f
        ret.onComplete {
          case Success(_) =>
            val end = clock.monotonic(TimeUnit.SECONDS)
            g.set(end - start)
          case _ =>
        }
        ret
      }
    }
    implicit class GaugeFHelper[F[_]](g: F[Gauge]) {
      import cats.implicits._
      def inc(amount: Double = 1)(implicit clock: Clock[Id], ev: Functor[F]): Unit = {
        g.map(_.inc(amount))
      }
      def dec(amount: Double = 1)(implicit clock: Clock[Id], ev: Functor[F]): Unit = {
        g.map(_.dec(amount))
      }
      def set(amount: Double)(implicit clock: Clock[Id], ev: Functor[F]): Unit = {
        g.map(_.set(amount))
      }
      def setToCurrentTime()(implicit clock: Clock[Id], ev: Functor[F]): Unit = {
        g.map(_.setToCurrentTime())
      }
      def measure[T](f: => T)(implicit clock: Clock[Id], ev: Functor[F]): F[T] = {
        g.map(_.measure(f))
      }
      def remove()(implicit ev: Functor[F]): Unit = {
        g.map(_.remove())
      }
    }
  }

  def apply(name: String, description: String, initial: Double = 0)(implicit clock: Clock[Id], as: ActorSystem, registry: Registry): Gauge = {
    import akka.actor.typed.scaladsl.adapter._
    val meta = MetricMeta(Gauge.mType, name, Map.empty[String, String], description, None)
    new Gauge(as.spawn(GaugeActor(meta, registry, initial), name))
  }

  def withLabels[L <: Enumeration](name: String, description: String)(implicit clock: Clock[Id], as: ActorSystem, registry: Registry, executionContext: ExecutionContext): LabeledMetric[Gauge, L] = {
    import akka.actor.typed.scaladsl.adapter._
    new LabeledMetric[Gauge, L](as.spawn(
      LabeledMetricActor(name, description, mType)(mm => new Gauge(as.spawnAnonymous(actors.GaugeActor(mm, registry)))
      ), name))
  }


}
