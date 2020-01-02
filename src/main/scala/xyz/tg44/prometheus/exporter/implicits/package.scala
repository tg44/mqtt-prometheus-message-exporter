package xyz.tg44.prometheus.exporter

package object implicits extends
  Counter.implicits with
  Gauge.implicits with
  Histogram.implicits with
  Summary.implicits
