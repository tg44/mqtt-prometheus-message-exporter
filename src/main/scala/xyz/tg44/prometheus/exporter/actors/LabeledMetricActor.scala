package xyz.tg44.prometheus.exporter.actors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import xyz.tg44.prometheus.exporter.Registry
import xyz.tg44.prometheus.exporter.Registry.{Metric, MetricMeta}

private[exporter] object LabeledMetricActor {
  type MetricCreator[M <: Metric] = MetricMeta => M

  sealed trait Command
  case class Select[M <: Metric](selector: Map[String, String], replyTo: ActorRef[Response[M]]) extends Command
  case class Remove(selector: Map[String, String]) extends Command
  case class RemoveAll() extends Command
  case class Stop() extends Command

  case class Response[M <: Metric](m: M)

  def apply[M <: Metric](name: String, description: String, mType: String)(creator: MetricCreator[M])(implicit registry: Registry): Behavior[Command] = {
    Behaviors.setup { context =>
      val meta = MetricMeta(mType, name, Map.empty, description, Some(name))
      new LabeledMetricBehaviour[M](meta, registry, creator, context).cnt(Map.empty)
    }
  }

  def apply[M <: Metric](meta: MetricMeta)(creator: MetricCreator[M])(implicit registry: Registry): Behavior[Command] = {
    Behaviors.setup { context =>
      new LabeledMetricBehaviour[M](meta, registry, creator, context).cnt(Map.empty)
    }
  }

  private class LabeledMetricBehaviour[M <: Metric] (
    meta: MetricMeta,
    registry: Registry,
    creator: MetricCreator[M],
    context: ActorContext[Command]
  ) {
    def cnt(state: Map[Map[String, String], M]): Behavior[Command] = Behaviors.receiveMessage {
      case s: Select[M] =>
        val newState = state.get(s.selector).fold{
          val newMeta = MetricMeta(meta.mType, meta.name, meta.labels ++ s.selector, meta.description, meta.group)
          val m = creator(newMeta)
          s.replyTo ! Response(m)
          state + (s.selector -> m)
        }{ m =>
          s.replyTo ! Response(m)
          state
        }
        cnt(newState)
      case r: Remove =>
        val newState = state.get(r.selector).fold{
          state
        }{ m =>
          m.remove()
          state - r.selector
        }
        cnt(newState)
      case _: RemoveAll =>
        state.foreach(_._2.remove())
        cnt(Map.empty)
      case Stop() =>
        state.foreach(_._2.remove())
        Behaviors.stopped
      case other =>
        context.log.warn(s"Unhandled request: $other")
        Behaviors.same
    }
  }
}
