package com.evolutiongaming.kafka.journal

import cats.data.{NonEmptyList => Nel}
import cats.effect.{ContextShift, Resource}
import cats.implicits._
import cats.~>
import com.evolutiongaming.catshelper.{BracketThrowable, Log}
import com.evolutiongaming.kafka.journal.conversions.ConsumerRecordToActionRecord
import com.evolutiongaming.skafka.consumer.ConsumerRecord
import com.evolutiongaming.skafka.{Offset, Partition, TopicPartition}
import com.evolutiongaming.sstream.Stream
import scodec.bits.ByteVector


trait ConsumeActionRecords[F[_]] {

  def apply(key: Key, partition: Partition, from: Offset): Stream[F, ActionRecord[Action]]
}

object ConsumeActionRecords {

  def apply[F[_]: BracketThrowable: ContextShift](
    consumer: Resource[F, Journal.Consumer[F]],
    log: Log[F])(implicit
    consumerRecordToActionRecord: ConsumerRecordToActionRecord[F]
  ): ConsumeActionRecords[F] = {
    (key: Key, partition: Partition, from: Offset) => {

      val topicPartition = TopicPartition(topic = key.topic, partition = partition)

      def seek(consumer: Journal.Consumer[F]) = {
        for {
          _ <- consumer.assign(Nel.of(topicPartition))
          _ <- consumer.seek(topicPartition, from)
          _ <- log.debug(s"$key consuming from $partition:$from")
        } yield {}
      }

      def filter(records: List[Nel[ConsumerRecord[String, ByteVector]]]) = {
        for {
          records <- records
          record  <- records.toList if record.key.exists { _.value == key.id }
        } yield record
      }

      def poll(consumer: Journal.Consumer[F]) = {
        for {
          records0 <- consumer.poll
          _        <- if (records0.values.isEmpty) ContextShift[F].shift else ().pure[F]
          records   = filter(records0.values.values.toList)
          actions  <- records.traverseFilter(consumerRecordToActionRecord.apply)
        } yield actions
      }

      for {
        consumer <- Stream.fromResource(consumer)
        _        <- Stream.lift(seek(consumer))
        records  <- Stream.repeat(poll(consumer))
        record   <- Stream[F].apply(records)
      } yield record
    }
  }


  implicit class ConsumeActionRecordsOps[F[_]](val self: ConsumeActionRecords[F]) extends AnyVal {

    def mapK[G[_]](to: F ~> G, from: G ~> F): ConsumeActionRecords[G] = {
      (key: Key, partition: Partition, from1: Offset) => {
        self(key, partition, from1).mapK[G](to, from)
      }
    }
  }
}