package xyz.tg44.prometheus.exporter.actors

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import xyz.tg44.prometheus.exporter.Registry.MetricMeta
import xyz.tg44.prometheus.exporter.{Quantile, Registry}

import scala.concurrent.duration.FiniteDuration

private[exporter] object CKMSQuantileActor {
  sealed trait Command
  case class Rotate() extends Command
  case class Observe(d: Double) extends Command
  case class Stop() extends Command

  def apply(meta: MetricMeta, quantiles: Seq[Quantile], rotationTime: FiniteDuration, numberOfBuckets:Int, registry: Registry): Behavior[Command] = {
    Behaviors.setup { _ =>
      Behaviors.withTimers { timers =>
        def beh(dataPoints: Seq[CKMSQuantiles]): Behavior[Command] = {
          Behaviors.receiveMessage {
            case Rotate() =>
              beh(dataPoints.tail ++ Seq(new CKMSQuantiles(quantiles)))
            case Observe(d) =>
              dataPoints.foreach(_.insert(d))
              val data = dataPoints.head.getAllQuantiles
              data.foreach{case (q, v) =>
                val fixedMeta = meta.copy(labels = meta.labels.updated("quantile", q.quantile.toString))
                registry.offerValue(fixedMeta, v)
              }
              Behaviors.same
            case Stop() =>
              val data = dataPoints.head.getAllQuantiles
              data.foreach{case (q, v) =>
                val fixedMeta = meta.copy(labels = meta.labels.updated("quantile", q.quantile.toString))
                registry.offerValue(fixedMeta, v, last = true)
              }
              Behaviors.stopped
          }
        }
        //timers.startTimerWithFixedDelay("rotate", Rotate(), rotationTime)
        timers.startPeriodicTimer("rotate", Rotate(), rotationTime)
        beh(Seq.fill(numberOfBuckets)(new CKMSQuantiles(quantiles)))
      }
    }
  }


  private class CKMSQuantiles(quantiles: Seq[Quantile]) {
    //Most of the code in this class ported from:
    //https://raw.githubusercontent.com/prometheus/client_java/master/simpleclient/src/main/java/io/prometheus/client/CKMSQuantiles.java
    var count: Int =0
    var compressIdx = 0
    val sample: java.util.LinkedList[QItem] = new java.util.LinkedList[QItem]()
    var bufferCount = 0
    val buffer = new Array[Double](500)

    def insert(value: Double): Unit = {
      buffer(bufferCount) = value
      bufferCount += 1
      if (bufferCount == buffer.length) {
        insertAndCompress()
      }
    }

    def getAllQuantiles: Seq[(Quantile, Double)] = {
      //TODO why the hack we do the insert and compress every time?
      quantiles.map(q => q -> get(q.quantile))
    }

    def get(q: Double): Double = {
      insertAndCompress()
      if (sample.size == 0) {
        Double.NaN
      } else {
        var rankMin = 0
        val desired = (q * count).toInt
        val it: java.util.ListIterator[QItem] = sample.listIterator
        var prev: QItem = null
        var cur: QItem = null
        cur = it.next
        while (it.hasNext) {
          prev = cur
          cur = it.next
          rankMin += prev.g
          if (rankMin + cur.g + cur.delta > desired + (allowableError(desired) / 2)) return prev.value
        }
        sample.getLast.value
      }
    }

    private def allowableError(rank: Int) = {
      //TODO this code is really strange, as I understands the math this could be really off
      val size = sample.size
      var minError: Double = size + 1
      for (q <- quantiles) {
        val error =
          if (rank <= q.quantile * size) {
            q.u * (size - rank)
          }
          else {
            q.v * rank
          }
        if (error < minError) minError = error
      }
      minError
    }

    private def insertAndCompress(): Unit = {
      insertBatch()
      compress()
    }

    private def insertBatch(): Boolean = {
      if (bufferCount == 0) {
        false
      } else {
        java.util.Arrays.sort(buffer, 0, bufferCount)
        var start = 0
        if (sample.size == 0) {
          val newItem = QItem(buffer(0), 1, 0)
          sample.add(newItem)
          start += 1
          count += 1
        }
        val it: java.util.ListIterator[QItem] = sample.listIterator
        var item = it.next

        for (i <- start until bufferCount) {
          val v = buffer(i)
          while (it.nextIndex < sample.size && item.value < v) {
            item = it.next
          }
          if (item.value > v) {
            it.previous
          }
          val delta =
            if ((it.previousIndex == 0) || (it.nextIndex == sample.size)) {
              0
            }
            else {
              Math.floor(allowableError(it.nextIndex)).toInt - 1
            }
          val newItem = QItem(v, 1, delta)
          it.add(newItem)
          count += 1
          item = newItem
        }

        bufferCount = 0
        true
      }
    }

    private def compress(): Unit = {
      if (sample.size < 2) {}
      else {
        val it: java.util.ListIterator[QItem] = sample.listIterator
        var prev: QItem = null
        var next = it.next
        while ( it.hasNext ) {
          prev = next
          next = it.next
          if (prev.g + next.g + next.delta <= allowableError(it.previousIndex)) {
            val modded = next.copy(g = next.g + prev.g)
            it.set(modded)
            it.previous()
            it.previous()
            it.remove()
            it.next()
            next = modded
          }
        }
      }
    }
  }
  case class QItem(value: Double = .0, g: Int = 0, delta: Int = 0)
}
