package com.socrata.soda2.consumer.http

import scala.io.Codec

import java.net.URL

import com.ning.http.client.AsyncHttpClient

import com.socrata.soda2.consumer.{LowLevel, RowProducer}
import com.socrata.soda2.http._
import com.socrata.future.{ExecutionContext, Future}
import com.socrata.http.Authorization
import com.socrata.http.implicits._
import com.socrata.iteratee.CharIteratee

// should this be moved to soda2.http?
class LowLevelHttp(val client: AsyncHttpClient, val host: String, val port: Int, val authorization: Authorization)(implicit executionContext: ExecutionContext) extends LowLevel {
  def execute[T](resource: String, getParameters: Map[String, Seq[String]], iteratee: CharIteratee[T]): Future[T] =
    doGet(urlForResource(resource).toString, resource.toString, Some(getParameters), iteratee).flatMap(maybeRetry(resource, getParameters, iteratee, _))

  def doGet[T](url: String, resource: String, queryParameters: Option[Map[String, Seq[String]]], iteratee: CharIteratee[T]): Future[Retryable[T]] = {
    def bodyConsumer(codec: Codec) = new RowProducer(codec, iteratee)
    val builder = client.prepareGet(url).
      maybeSetQueryParametersS(queryParameters).
      setFollowRedirects(true).
      setHeader("Accept", "application/json").
      authorize(authorization)
    builder.makeRequest(new StandardConsumer(resource, bodyConsumer))
  }

  def urlForResource(resource: String) =
    new URL("https", host, port, resource)

  def maybeRetry[T](resource: String, getParameters: Map[String, Seq[String]], iteratee: CharIteratee[T], x: Retryable[T]): Future[T] = x match {
    case Right(result) =>
      Future.now(result)
    case Left(newRequest) =>
      // TODO: some sort of progress based on newRequest.details
      executionContext.in(newRequest.retryAfter) {
        newRequest match {
          case Retry(_, _) =>
            execute(resource, getParameters, iteratee)
          case RetryWithTicket(ticket, _, _) =>
            execute(resource, getParameters + ("ticket" -> Seq(ticket)), iteratee)
          case Redirect(url, _, _) =>
            val target = urlForResource(resource).toURI.resolve(url).toURL.toString
            doGet(target, resource, None, iteratee).flatMap(maybeRetry(resource, getParameters, iteratee, _))
        }
      }.flatten
  }
}
