package xyz.tg44.prometheus.exporter.actors

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import xyz.tg44.prometheus.exporter.Registry
import xyz.tg44.prometheus.exporter.Registry.MetricMeta

private[exporter] object CounterActor {
  sealed trait Command
  case class Inc(amount: Double = 1) extends Command {
    require(amount >= 0)
  }
  case class Stop() extends Command

  def apply(meta: MetricMeta, registry: Registry): Behavior[Command] = Behaviors.setup { context =>
    new GaugeBehaviour(meta, registry, context).cnt(0)
  }

  private class GaugeBehaviour (
    meta: MetricMeta,
    registry: Registry,
    context: ActorContext[Command]
  ) {
    def cnt(state: Double): Behavior[Command] = Behaviors.receiveMessage {
      case Inc(amount) =>
        val newState = state + amount
        registry.offerValue(meta, newState)
        cnt(newState)
      case Stop() =>
        registry.offerValue(meta, state, last = true)
        Behaviors.stopped
    }
  }
}
