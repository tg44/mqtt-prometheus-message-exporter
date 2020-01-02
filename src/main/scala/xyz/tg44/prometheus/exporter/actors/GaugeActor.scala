package xyz.tg44.prometheus.exporter.actors

import java.util.concurrent.TimeUnit

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import cats.effect.Clock
import cats.{Id, Semigroup}
import xyz.tg44.prometheus.exporter.Registry
import xyz.tg44.prometheus.exporter.Registry.MetricMeta

private[exporter] object GaugeActor {

  sealed trait Command
  sealed trait GaugeValue {
    def amount: Double
    def timeStamp: Long
  }

  case class ModifyWith(amount: Double, timeStamp: Long) extends GaugeValue with Command
  case class SetTo(amount: Double, timeStamp: Long) extends GaugeValue with Command
  case class Stop() extends Command

  def apply(meta: MetricMeta, registry: Registry, initial: Double = 0)(implicit clock: Clock[Id]): Behavior[Command] = Behaviors.setup { context =>
    val now = clock.realTime(TimeUnit.MILLISECONDS)
    new GaugeBehaviour(meta, registry, context).cnt(SetTo(initial, now))
  }

  private class GaugeBehaviour (
    meta: MetricMeta,
    registry: Registry,
    context: ActorContext[Command]
  ) {
    import cats.implicits._
    def cnt(state: GaugeValue): Behavior[Command] = Behaviors.receiveMessage {
      case v: GaugeValue =>
        val newState = state |+| v
        registry.offerValue(meta, newState.amount, Some(state.timeStamp))
        cnt(newState)
      case Stop() =>
        registry.offerValue(meta, state.amount, Some(state.timeStamp), last = true)
        Behaviors.stopped
    }
  }

  //I'm not sure this is a valid semigroup or not...
  //TODO: check the laws
  implicit val gaugeValueSemiGroup: Semigroup[GaugeValue] = new Semigroup[GaugeValue] {
    @scala.annotation.tailrec
    override def combine(x: GaugeValue, y: GaugeValue): GaugeValue = {
      if(x.timeStamp > y.timeStamp) {
        combine(y, x)
      } else {
        (x, y) match {
          case (_, SetTo(_, _)) => y
          case (SetTo(a1, _), ModifyWith(a2, t2)) => SetTo(a1 + a2, t2)
          case (ModifyWith(a1, _), ModifyWith(a2, t2)) => ModifyWith(a1+a2, t2)
        }
      }
    }
  }
}
