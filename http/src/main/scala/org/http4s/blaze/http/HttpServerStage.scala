package org.http4s.blaze.http


import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.pipeline.{Command => Cmd, _}
import org.http4s.blaze.util.Execution._
import org.http4s.websocket.WebsocketBits.WebSocketFrame
import org.http4s.websocket.WebsocketHandshake
import org.http4s.blaze.http.parser.BaseExceptions.BadRequest
import org.http4s.blaze.http.websocket.WebSocketDecoder
import org.http4s.blaze.pipeline.Command.EOF

import scala.util.{Failure, Success}
import scala.concurrent.{ExecutionContext, Future}

class HttpServerStage(maxNonBodyBytes: Int, ec: ExecutionContext)(handleRequest: HttpService)
  extends TailStage[ByteBuffer] { httpServerStage =>

  private implicit def implicitEC = trampoline

  val name = "HTTP/1.1_Stage"

  private[this] val codec = new  HttpCodec(maxNonBodyBytes, this)
  
  /////////////////////////////////////////////////////////////////////////////////////////

  // Will act as our loop
  override def stageStartup() {
    logger.info("Starting HttpStage")
    requestLoop()
  }

  private def requestLoop(): Unit = {
    codec
      .getRequest()
      .flatMap(handleRequest)
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

//  /** Deal with route response of WebSocket form */
//  private def handleWebSocket(reqHeaders: Headers, wsBuilder: LeafBuilder[WebSocketFrame]): Future[RouteResult] = {
//    val sb = new StringBuilder(512)
//    WebsocketHandshake.serverHandshake(reqHeaders) match {
//      case Left((i, msg)) =>
//        logger.info(s"Invalid handshake: $i: $msg")
//        sb.append("HTTP/1.1 ").append(i).append(' ').append(msg).append('\r').append('\n')
//          .append('\r').append('\n')
//
//        channelWrite(ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1)))
//          .map(_ => Close)
//
//      case Right(hdrs) =>
//        logger.info("Starting websocket request")
//        sb.append("HTTP/1.1 101 Switching Protocols\r\n")
//        hdrs.foreach { case (k, v) => sb.append(k).append(": ").append(v).append('\r').append('\n') }
//        sb.append('\r').append('\n')
//
//        // write the accept headers and reform the pipeline
//        channelWrite(ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1))).map{ _ =>
//          logger.debug("Switching pipeline segments for upgrade")
//          val segment = wsBuilder.prepend(new WebSocketDecoder(false))
//          this.replaceInline(segment)
//          Upgrade
//        }
//    }
//  }

  private def shutdownWithCommand(cmd: Cmd.OutboundCommand): Unit = {
    stageShutdown()
    sendOutboundCommand(cmd)
  }

  override protected def stageShutdown(): Unit = {
    logger.info("Shutting down HttpPipeline")
    codec.shutdown()
  }
}
