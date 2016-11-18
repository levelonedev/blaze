package org.http4s.blaze.http

import java.util.IdentityHashMap

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

final class HttpPipeliner(maxConcurrentRequests: Int, codec: HttpCodec)(service: HttpService) {

  private type Id = HttpRequest

  private case class PendingWrite(response: RouteAction, p: Promise[HttpCodec.RouteResult])

  private val inFlightRequests = new mutable.Queue[HttpRequest]()
  private val pendingWrites = new IdentityHashMap[HttpRequest, PendingWrite]

  private val lock: Object = inFlightRequests


  def readRequest(): Future[HttpRequest] = {
    codec.getRequest().map(request => lock.synchronized {
      inFlightRequests += request
      request
    })
  }

  private def checkPendingWrites(): Unit = lock.synchronized {
    if (!pendingWrites.isEmpty) {
      val h = inFlightRequests.head // If there are pending writes, this is guaranteed non-empty
      val p = pendingWrites.get(h)
      if (p != null) {
        // We can write it now
        val _ = inFlightRequests.dequeue()
        val f = codec.renderResponse(p.response)
        f.onSuccess { case HttpCodec.Reload => checkPendingWrites() }
        p.p.completeWith(f)
      }
    }
  }

  def renderResponse(request: HttpRequest, response: ResponseBuilder): Future[HttpCodec.RouteResult] = lock.synchronized {
    val resp = response match {
      case HttpResponse(resp) => resp
      case other => ??? // This is awkward: received a websocket response. How do we pipeline that?
    }

     if (inFlightRequests.head eq request) {
       // We are ready to roll
       val _ = inFlightRequests.dequeue()
       val f = codec.renderResponse(resp)
       f.onSuccess { case HttpCodec.Reload => checkPendingWrites() }
       f
     } else {
       // Need to queue up this response
       val p = Promise[HttpCodec.RouteResult]
       pendingWrites.put(request, PendingWrite(resp, p))
       p.future
     }
  }

  def loop(): Future[Unit] = {

    val p = Promise[Unit]


    def innerLoop(): Unit = {
      readRequest()
        .flatMap { request =>
          service(request)
            .flatMap(renderResponse(request, _))
        }
        .onComplete {
          case _ if p.isCompleted => () // Finished
          case Success(HttpCodec.Reload) => innerLoop()
          case Success(HttpCodec.Close) => p.trySuccess(())
          case Failure(e) => p.tryFailure(e)
        }
    }

    innerLoop()
    p.future
  }

}
