package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.util.HeaderNames
import org.http4s.blaze.util.Execution

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/** Post routing response generator */
trait RouteAction {
  /** Generate a HTTP response using the passed continuation
    *
    * @param commit function which commits the response prelude and provides an appropriate [[BodyWriter]]
    * @tparam Writer the type of the [[BodyWriter]] with a refined `Finished` type
    * @return an asynchronous `BodyWriter#Finished` object. This type enforces that the [[BodyWriter]]
    *         has been successfully closed.
    */
  def handle[Writer <: BodyWriter](commit: (HttpResponsePrelude => Writer)): Future[Writer#Finished]
}

object RouteAction {
  /** generate a streaming HTTP response
    *
    * @param body each invocation should generate the __next__ body chunk. Each chunk will be written
    *             before requesting another chunk. Termination is signaled by an __empty__ `ByteBuffer` as
    *             defined by the return value of `ByteBuffer.hasRemaining()`.
    */
  def Streaming(code: Int, status: String, headers: Headers)
               (body: () => Future[ByteBuffer])
               (implicit ec: ExecutionContext = Execution.trampoline): HttpResponse = HttpResponse(
    new RouteAction {
      override def handle[T <: BodyWriter](responder: (HttpResponsePrelude) => T): Future[T#Finished] = {
        val writer = responder(HttpResponsePrelude(code, status, headers))

//        // Not safe to do in scala 2.11 or lower :(
//        def go_(): Future[T#Finished] = body().flatMap {
//          case buff if buff.hasRemaining => writer.write(buff).flatMap(_ => go())
//          case _ => writer.close()
//        }

        val p = Promise[T#Finished]

        // Have to do this nonsense because a recursive Future loop isn't safe until scala 2.12+
        def go(): Unit = body().onComplete {
          case Failure(t) => p.tryFailure(t)
          case Success(buff) =>
            if (!buff.hasRemaining) p.completeWith(writer.close())
            else writer.write(buff).onComplete {
              case Success(_) => go()
              case Failure(t) => p.tryFailure(t)
            }
        }

        // Start our loop in the EC
        ec.prepare().execute(new Runnable { def run(): Unit = go() })

        p.future
      }
    }
  )

  /** generate a HTTP response from a single `ByteBuffer`
    *
    * @param body the single `ByteBuffer` which represents the body.
    *
    * Note: this method will not modify the passed `ByteBuffer`, instead making
    * read-only views of it when writing to the socket, so the resulting responses
    * can be reused multiple times.
    */
  def Buffer(code: Int, status: String, headers: Headers, body: ByteBuffer): HttpResponse = HttpResponse(
    new RouteAction {
      override def handle[T <: BodyWriter](responder: (HttpResponsePrelude) => T): Future[T#Finished] = {
        val finalHeaders = (HeaderNames.ContentLength, body.remaining().toString) +: headers
        val prelude = HttpResponsePrelude(code, status, finalHeaders)
        val writer = responder(prelude)

        writer.write(body.asReadOnlyBuffer()).flatMap(_ => writer.close())(Execution.directec)
      }
    }
  )

  /** generate a HTTP response from a String */
  def String(code: Int, status: String, headers: Headers, body: String): HttpResponse =
    Buffer(code, status, Utf8StringHeader +: headers, StandardCharsets.UTF_8.encode(body))

  /** Generate a 200 OK HTTP response from an `Array[Byte]` */
  def Ok(body: Array[Byte], headers: Headers = Nil): HttpResponse =
    Buffer(200, "OK", headers, ByteBuffer.wrap(body))

  /** Generate a 200 OK HTTP response from an `Array[Byte]` */
  def Ok(body: Array[Byte]): HttpResponse = Ok(body, Nil)

  /** Generate a 200 OK HTTP response from a `String` */
  def Ok(body: String, headers: Headers): HttpResponse =
    Ok(body.getBytes(StandardCharsets.UTF_8), Utf8StringHeader +: headers)

  /** Generate a 200 OK HTTP response from a `String` */
  def Ok(body: String): HttpResponse = Ok(body, Nil)

  def EntityTooLarge(): HttpResponse =
    String(413, "Request Entity Too Large", Nil, s"Request Entity Too Large")

  private val Utf8StringHeader = "content-type" -> "text/plain; charset=utf-8"
}