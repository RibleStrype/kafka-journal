package com.evolutiongaming.kafka.journal.replicator

import java.time.Instant

import cats.data.{NonEmptyList => Nel}
import cats.effect._
import cats.syntax.all._
import cats.{Applicative, Parallel}
import com.evolutiongaming.catshelper.ClockHelper._
import com.evolutiongaming.catshelper.{BracketThrowable, Log}
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.kafka.journal.conversions.{ConsRecordToActionRecord, KafkaRead}
import com.evolutiongaming.kafka.journal.eventual._
import com.evolutiongaming.kafka.journal.replicator.TopicReplicator.Metrics
import com.evolutiongaming.kafka.journal.util.TemporalHelper._

import scala.concurrent.duration.FiniteDuration


trait ReplicateRecords[F[_]] {

  def apply(records: Nel[ConsRecord], timestamp: Instant): F[Unit]
}

object ReplicateRecords {

  def apply[F[_] : BracketThrowable : Clock : Parallel, A](
    consRecordToActionRecord: ConsRecordToActionRecord[F],
    journal: ReplicatedKeyJournal[F],
    metrics: Metrics[F],
    kafkaRead: KafkaRead[F, A],
    eventualWrite: EventualWrite[F, A],
    log: Log[F]
  ): ReplicateRecords[F] = {

    (records: Nel[ConsRecord], timestamp: Instant) => {

      def apply(records: Nel[ActionRecord[Action]]) = {
        val head = records.head
        val key = head.action.key
        val id = key.id

        def measurements(records: Int) = {
          for {
            now <- Clock[F].instant
          } yield {
            Metrics.Measurements(
              replicationLatency = now diff head.action.timestamp,
              deliveryLatency = timestamp diff head.action.timestamp,
              records = records)
          }
        }

        def append(partitionOffset: PartitionOffset, records: Nel[ActionRecord[Action.Append]]) = {

          val bytes = records.foldLeft(0L) { case (bytes, record) => bytes + record.action.payload.size }

          val events = records.flatTraverse { record =>
            val action = record.action
            val payloadAndType = PayloadAndType(action)
            val events = kafkaRead(payloadAndType).adaptError { case e =>
              JournalError(s"ReplicateRecords failed for id: $id, offset: $partitionOffset: $e", e)
            }
            for {
              events <- events
              eventualEvents <- events.events.traverse(_.traverse(eventualWrite.apply))
            } yield for {
              event <- eventualEvents
            } yield {
              EventRecord(record, event, events.metadata)
            }
          }

          def msg(events: Nel[EventRecord[EventualPayloadAndType]], latency: FiniteDuration, expireAfter: Option[ExpireAfter]) = {
            val seqNrs =
              if (events.tail.isEmpty) s"seqNr: ${ events.head.seqNr }"
              else s"seqNrs: ${ events.head.seqNr }..${ events.last.seqNr }"
            val origin = records.head.action.origin
            val originStr = origin.foldMap { origin => s", origin: $origin" }
            val expireAfterStr = expireAfter.foldMap { expireAfter => s", expireAfter: $expireAfter" }
            s"append in ${ latency.toMillis }ms, id: $id, offset: $partitionOffset, $seqNrs$originStr$expireAfterStr"
          }

          def measure(events: Nel[EventRecord[EventualPayloadAndType]], expireAfter: Option[ExpireAfter]) = {
            for {
              measurements <- measurements(records.size)
              _            <- metrics.append(events = events.length, bytes = bytes, measurements = measurements)
              _            <- log.info(msg(events, measurements.replicationLatency, expireAfter))
            } yield {}
          }

          for {
            events       <- events
            expireAfter   = events.last.metadata.payload.expireAfter
            appended     <- journal.append(partitionOffset, timestamp, expireAfter, events)
            _            <- if (appended) measure(events, expireAfter) else Applicative[F].unit
          } yield {}
        }

        def delete(partitionOffset: PartitionOffset, deleteTo: DeleteTo, origin: Option[Origin]) = {

          def msg(latency: FiniteDuration) = {
            val originStr = origin.foldMap { origin => s", origin: $origin" }
            s"delete in ${ latency.toMillis }ms, id: $id, offset: $partitionOffset, deleteTo: $deleteTo$originStr"
          }

          def measure() = {
            for {
              measurements <- measurements(1)
              latency       = measurements.replicationLatency
              _            <- metrics.delete(measurements)
              _            <- log.info(msg(latency))
            } yield {}
          }

          for {
            deleted <- journal.delete(partitionOffset, timestamp, deleteTo, origin)
            _       <- if (deleted) measure() else Applicative[F].unit
          } yield {}
        }

        def purge(partitionOffset: PartitionOffset, origin: Option[Origin]) = {

          def msg(latency: FiniteDuration) = {
            val originStr = origin.foldMap { origin => s", origin: $origin" }
            s"purge in ${ latency.toMillis }ms, id: $id, offset: $partitionOffset$originStr"
          }

          def measure() = {
            for {
              measurements <- measurements(1)
              latency       = measurements.replicationLatency
              _            <- metrics.purge(measurements)
              _            <- log.info(msg(latency))
            } yield {}
          }

          for {
            purged <- journal.purge(partitionOffset.offset, timestamp)
            _      <- if (purged) measure() else Applicative[F].unit // measure() //

          } yield {}
        }

        Batch
          .of(records)
          .foldMapM {
            case batch: Batch.Appends => append(batch.partitionOffset, batch.records)
            case batch: Batch.Delete  => delete(batch.partitionOffset, batch.to, batch.origin)
            case batch: Batch.Purge   => purge(batch.partitionOffset, batch.origin)
          }
      }

      for {
        records <- records.toList.traverseFilter { a => consRecordToActionRecord(a).value }
        _       <- records.toNel.traverse { records => apply(records) }
      } yield {}
    }
  }
}
