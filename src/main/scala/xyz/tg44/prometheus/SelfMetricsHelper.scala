package xyz.tg44.prometheus

import akka.NotUsed
import akka.actor.Cancellable
import akka.stream.scaladsl.Source
import xyz.tg44.prometheus.Config.{AppConfig, SelfMetricsConfig}
import xyz.tg44.prometheus.exporter.Registry.{Line, MetricMeta}

import concurrent.duration._

class SelfMetricsHelper(selfMetricsConfig: SelfMetricsConfig) {

  def uptimeSource: Source[(Line, Boolean), Cancellable] = {
    val meta = MetricMeta("counter", s"${selfMetricsConfig.prefix}_uptime", Map.empty, "The uptime of the mqtt-exporter", None)
    Source.tick(0.seconds, 1.seconds, NotUsed)
      .conflateWithSeed(_ => 1L)((a, _) => a + 1)
      .statefulMapConcat { () =>
        var counter = 0L
        v => {
          counter += v
          List(counter)
        }
      }
      .map(v => (Line(meta, v, None), false))
  }
}
