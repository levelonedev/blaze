//package org.http4s.blaze.http
//
//import java.util.IdentityHashMap
//import java.util.ArrayDeque
//
//import org.http4s.blaze.util.Execution
//
//import scala.concurrent.{ExecutionContext, Future, Promise}
//import scala.util.control.NonFatal
//import scala.util.{Failure, Success}
//
//final class HttpPipeliner(service: HttpService, maxConcurrentRequests: Int, codec: HttpCodec, ec: ExecutionContext) {
//  private[this] val requestQueue = new ArrayDeque[HttpRequest]()
//  private[this] val pendingWrites = new IdentityHashMap[HttpRequest, RouteAction]
//  private[this] val lock: Object = requestQueue
//  private[this] var readLoopRunning = false
//
//  def loop(): Future[Unit] = {
//    val p = Promise[Unit]
//    lock.synchronized {
//      readLoopRunning = true
//    }
//    readLoop(p)
//    p.future
//  }
//
//  // Must only be called from within a block synchronized on `lock`
//  private[this] def reachedMaxPending(): Boolean = requestQueue.size() >= maxConcurrentRequests
//
//  /**
//    * Calls to codec.getRequest() and the subsequent insertion of the request into the
//    * pipeline queue must be executing sequentially. The general idea is that the readLoop
//    * will continue to loop until the max pending requests has been saturated. Once that
//    * happens its the duty of the write loop (`renderResponse`) to restart the read loop.
//    */
//  private[this] def readLoop(p: Promise[Unit]): Unit = {
//    codec.getRequest().onComplete {
//      case _ if p.isCompleted => // NOOP. We've terminated
//      case Success(request) =>  lock.synchronized {
//
//        // Note: this MUST come before the next call to readLoop or we risk out of order pipelining
//        requestQueue.push(request)
//
//        // Here is where we fork the execution to start reading the next request and begin servicing the previous
//        if (codec.readyForNextRequest() && !reachedMaxPending()) {
//          // start running another request.
//          readLoop(p)
//        } else {
//          readLoopRunning = false
//        }
//        serviceRequest(p, request)
//      }
//
//      case Failure(e) => p.tryFailure(e)
//    }(ec)
//  }
//
//  private[this] def serviceRequest(p: Promise[Unit], request: HttpRequest): Unit = {
//    service(request).onComplete {
//      case Success(HttpResponse(responseBuilder)) => renderResponse(p, request, responseBuilder)
//      case Success(WSResponseBuilder(_)) => ???
//      case Failure(NonFatal(err)) => ???
//      case Failure(fatal) => ???
//    }(Execution.directec)
//  }
//
//  /**
//    * Render responses in the order of which they were received.
//    *
//    * This function has two duties: if it receives the response that is currently at the head
//    * of the line, it renders it and then continues the loop. If it receives a response out of
//    * order, it puts it in the queue and exits the loop.
//    *
//    * If the head of the queue is rendered, and the read loop has been stalled, the renderLoop
//    * is responsible for restarting the readLoop.
//    */
//  private[this] def renderResponse(p: Promise[Unit], request: HttpRequest, response: RouteAction): Unit = lock.synchronized {
//    if (requestQueue.peekFirst() eq request) {
//      // We are ready to render this response
//      val _ = requestQueue.pollFirst()
//      codec.renderResponse(response).onSuccess {
//        case HttpCodec.Reload => lock.synchronized {
//
//          // Finished rendering a response and didn't close the connection,
//          // so we need to see if its time to read another request
//          if (reachedMaxPending()) ec.prepare().execute(new Runnable {
//            override def run(): Unit = readLoop(p)
//          })
//
//          // Check to see if the next response has been queued up, and if so, write it.
//          val nextReq = requestQueue.peekFirst()
//          if (nextReq != null) {
//            val nextWriter = pendingWrites.remove(nextReq)
//
//            if (nextWriter != null) {
//              renderResponse(p, nextReq, nextWriter)
//            }
//
//          }
//        }
//        case HttpCodec.Close => ???  // TODO: we should have a 'closed' writer that will just returns EOF for all ops.
//      }(Execution.trampoline)
//    } else {
//      // Need to queue up this response
//      pendingWrites.put(request, response)
//    }
//  }
//
//  private[this] def maybeInitReadLoop(p: Promise[Unit]): Unit = lock.synchronized {
//    if (!readLoopRunning && !reachedMaxPending() && codec.readyForNextRequest()) {
//      readLoopRunning = true
//      readLoop(p)
//    }
//  }
//}
