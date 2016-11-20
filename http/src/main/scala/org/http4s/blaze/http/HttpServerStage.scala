package org.http4s.blaze.http


import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.pipeline.{Command => Cmd, _}
import org.http4s.blaze.util.Execution._
import org.http4s.blaze.pipeline.Command.EOF

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext

class HttpServerStage(handleRequest: HttpService, maxNonBodyBytes: Int, ec: ExecutionContext)
  extends TailStage[ByteBuffer] { httpServerStage =>

  private implicit def implicitEC = trampoline

  val name = "HTTP/1.1_Stage"

  private[this] val codec = new  HttpCodec(maxNonBodyBytes, this)
  
  /////////////////////////////////////////////////////////////////////////////////////////

  // Will act as our loop
  override def stageStartup() {
    logger.debug("Starting HttpStage")
    requestLoop()
  }

  private def requestLoop(): Unit = {
    codec
      .getRequest()
      .flatMap(handleRequest)(ec)
      .onComplete {
        case Success(HttpResponse(resp)) =>
          codec.renderResponse(resp).onComplete {
            case Success(HttpCodec.Reload) => requestLoop()
            case Success(HttpCodec.Close) => sendOutboundCommand(Cmd.Disconnect)

            case Failure(EOF) => /* NOOP */
            case Failure(ex) =>
              logger.error(ex)("Failed to render response")
              shutdownWithCommand(Cmd.Error(ex))
          }

        case Success(WSResponseBuilder(resp)) => ???

        case Failure(EOF) => /* NOOP */
        case Failure(ex) =>
          val resp = send500(ex)
          codec.renderResponse(resp.action).onComplete { _ =>
            shutdownWithCommand(Cmd.Error(ex))
          }
      }
  }

  private def send500(error: Throwable): HttpResponse = {
    logger.error(error)("Failed to select a response. Sending 500 response.")
    val body = ByteBuffer.wrap("Internal Service Error".getBytes(StandardCharsets.ISO_8859_1))

    RouteAction.byteBuffer(500, "Internal Server Error", Nil, body)
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
