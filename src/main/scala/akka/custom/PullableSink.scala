package akka.custom

import akka.stream.impl.QueueSink
import akka.stream.impl.QueueSink.{Output, Pull}
import akka.stream.scaladsl.SinkQueueWithCancel
import akka.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler}
import akka.stream.{Attributes, Inlet, SinkShape}

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

final class PullableSink[T]()
  extends GraphStageWithMaterializedValue[SinkShape[T], SinkQueueWithCancel[T]] {
  type Requested[E] = Promise[Option[E]]

  val in = Inlet[T]("pullableSink.in")
  override val shape: SinkShape[T] = SinkShape.of(in)

  override def toString: String = "PullableSink"

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val stageLogic = new GraphStageLogic(shape) with InHandler with SinkQueueWithCancel[T] {
      type Received[E] = Try[Option[E]]

      var currentRequest: List[Requested[T]] = Nil

      private val callback = getAsyncCallback[Output[T]] {
        case QueueSink.Pull(pullPromise) =>
          if(currentRequest.isEmpty) pull(in)
          currentRequest = pullPromise :: currentRequest
        case QueueSink.Cancel => completeStage()
      }

      def sendDownstream(promise: Requested[T], e: Received[T]): Unit = {
        promise.complete(e)
        e match {
          case Success(_: Some[_]) => //do nothing
          case Success(None)       => completeStage()
          case Failure(t)          => failStage(t)
        }
      }

      @tailrec
      def enqueueAndNotify(requested: Received[T]): Unit = {
        currentRequest match {
          case Nil =>
          case h :: t =>
            sendDownstream(h, requested)
            currentRequest = t
            enqueueAndNotify(requested)
        }
      }

      def onPush(): Unit = {
        enqueueAndNotify(Success(Some(grab(in))))
      }

      override def onUpstreamFinish(): Unit = enqueueAndNotify(Success(None))
      override def onUpstreamFailure(ex: Throwable): Unit = enqueueAndNotify(Failure(ex))

      setHandler(in, this)

      // SinkQueueWithCancel impl
      override def pull(): Future[Option[T]] = {
        val p = Promise[Option[T]]
        callback
          .invokeWithFeedback(Pull(p))
          .failed
          .foreach {
            case NonFatal(e) => p.tryFailure(e)
            case _           => ()
          }(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
        p.future
      }
      override def cancel(): Unit = {
        callback.invoke(QueueSink.Cancel)
      }
    }

    (stageLogic, stageLogic)
  }

}
