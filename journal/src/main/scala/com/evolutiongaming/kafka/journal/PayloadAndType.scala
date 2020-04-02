package com.evolutiongaming.kafka.journal


import cats.data.{NonEmptyList => Nel}
import cats.{Applicative, Monad}
import com.evolutiongaming.kafka.journal.util.PlayJsonHelper._
import com.evolutiongaming.kafka.journal.util.ScodecHelper._
import play.api.libs.json._
import scodec.Codec
import scodec.bits.ByteVector

import scala.util.Try


final case class PayloadAndType(
  payload: ByteVector,
  payloadType: PayloadType.BinaryOrJson)

object PayloadAndType {

  def apply(action: Action.Append): PayloadAndType = {
    PayloadAndType(action.payload, action.header.payloadType)
  }


  final case class EventJson(
    seqNr: SeqNr,
    tags: Tags,
    payloadType: Option[PayloadType.TextOrJson] = None,
    payload: Option[JsValue] = None)

  object EventJson {

    implicit val formatEventJson: OFormat[EventJson] = Json.format


    implicit val writesNelEventJson: Writes[Nel[EventJson]] = nelWrites

    implicit val readsNelEventJson: Reads[Nel[EventJson]] = nelReads
  }


  final case class PayloadJson(events: Nel[EventJson], metadata: Option[PayloadMetadata])

  object PayloadJson {

    implicit val formatPayloadJson: OFormat[PayloadJson] = Json.format


    implicit def codecPayloadJson(implicit jsonCodec: JsonCodec[Try]): Codec[PayloadJson] = formatCodec // TODO not used


    implicit def toBytesPayloadJson[F[_] : Applicative: JsonCodec.Encode]: ToBytes[F, PayloadJson] =
      ToBytes.fromWrites

    implicit def fromBytesPayloadJson[F[_] : Monad : FromJsResult: JsonCodec.Decode]: FromBytes[F, PayloadJson] =
      FromBytes.fromReads
  }
}