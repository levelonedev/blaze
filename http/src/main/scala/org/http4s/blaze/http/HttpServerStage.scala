package org.http4s.blaze.http


import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.{Command => Cmd, _}
import org.http4s.blaze.util.Execution._
import org.http4s.blaze.pipeline.Command.EOF

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext

class HttpServerStage(service: HttpService, maxNonBodyBytes: Int, ec: ExecutionContext)
  extends TailStage[ByteBuffer] { httpServerStage =>

  private implicit def implicitEC = trampoline

  val name = "HTTP/1.1_Stage"

  private[this] val codec = new  HttpCodec(maxNonBodyBytes, this)

  /////////////////////////////////////////////////////////////////////////////////////////

  // Will act as our loop
  override def stageStartup() {
    logger.debug("Starting HttpStage")
    dispatchLoop()
  }

  private def dispatchLoop(): Unit = {
     /* TODO: how can we do smart timeouts? What are the situations where one would want to do timeouts?
      * - Waiting for the request prelude
      *   - Probably needs to be done in the dispatch loop
      * - Waiting for the service
      *   - Can be a service middleware that races the service with a timeout
      * - Waiting for an entire body
      *   - Maybe this could be attached to the readers and writers
      * - Long durations of network silence when reading or rendering a body
      *   - These could probably be done by wrapping readers and writers
      */
    codec
      .getRequest()
      .flatMap(service)(ec)
      .onComplete {
        case Success(HttpResponse(resp)) =>
          codec.renderResponse(resp).onComplete {
            case Success(HttpCodec.Reload) => dispatchLoop()
            case Success(HttpCodec.Close) => sendOutboundCommand(Cmd.Disconnect)

            case Failure(EOF) => /* NOOP */
            case Failure(ex) =>
              logger.error(ex)("Failed to render response")
              shutdownWithCommand(Cmd.Error(ex))
          }

        case Success(WSResponseBuilder(resp)) => ???

        case Failure(EOF) => /* NOOP */
        case Failure(ex) =>
          val resp = make5xx(ex)
          codec.renderResponse(resp.action).onComplete { _ =>
            shutdownWithCommand(Cmd.Error(ex))
          }
      }
  }

  private def make5xx(error: Throwable): HttpResponse = {
    logger.error(error)("Failed to select a response. Sending 500 response.")
    RouteAction.string(500, "Internal Server Error", Nil, "Internal Server Error.")
  }

  private def shutdownWithCommand(cmd: Cmd.OutboundCommand): Unit = {
    stageShutdown()
    sendOutboundCommand(cmd)
  }

  override protected def stageShutdown(): Unit = {
    logger.info("Shutting down HttpPipeline")
    codec.shutdown()
  }
}
