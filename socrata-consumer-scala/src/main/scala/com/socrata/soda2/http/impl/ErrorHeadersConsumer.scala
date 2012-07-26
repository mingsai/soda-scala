package com.socrata.soda2.http
package impl

import scala.{collection => sc}

import com.rojoma.json.ast.JObject
import com.rojoma.json.util.SimpleJsonCodecBuilder
import com.rojoma.json.codec.JsonCodec

import com.socrata.soda2.exceptions.soda1.Soda1Exception
import com.socrata.http.{BodyConsumer, HeadersConsumer}
import com.socrata.soda2.InvalidResponseJsonException

import HeadersConsumerUtils._

private[http] class ErrorHeadersConsumer(resource: String, code: Int) extends HeadersConsumer[Nothing] {
  import ErrorHeadersConsumer._
  def apply(headers: sc.Map[String, Seq[String]]): Either[BodyConsumer[Nothing], Nothing] = {
    val codec = jsonCodec(headers)
    Left(new SingleJValueBodyConsumer(codec).map {
      case value: JObject =>
        if(headers.contains("x-error-code") || headers.contains("x-error-message")) processLegacyError(resource, code, value)
        else processError(value)
      case other =>
        throw new InvalidResponseJsonException(other, "Error response body was not an object")
    })
  }

  def processError(errorObject: JObject): Nothing = {
    error("NYI")
  }
}

private[http] object ErrorHeadersConsumer {
  case class LegacyError(code: String, message: Option[String])
  implicit val legacyCodec = SimpleJsonCodecBuilder[LegacyError].gen("code", _.code, "message", _.message)

  def processLegacyError(resource: String, code: Int, errorObject: JObject): Nothing = {
    JsonCodec.fromJValue[LegacyError](errorObject) match {
      case Some(legacyError) =>
        throw Soda1Exception(resource, code, legacyError.code, legacyError.message)
      case None =>
        throw new InvalidResponseJsonException(errorObject, "Response body was not interpretable as an error")
    }
  }
}
