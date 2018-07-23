package com.evolutiongaming.kafka.journal.eventual.cassandra

import java.lang.{Long => LongJ}
import java.time.Instant

import com.datastax.driver.core.{BoundStatement, PreparedStatement}
import com.evolutiongaming.concurrent.async.Async
import com.evolutiongaming.kafka.journal.Alias.{SeqNr, Tags}
import com.evolutiongaming.kafka.journal.FoldWhileHelper._
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.kafka.journal.eventual.cassandra.CassandraHelper._
import com.evolutiongaming.kafka.journal.eventual.{PartitionOffset, Pointer}
import com.evolutiongaming.skafka.{Offset, Partition}

import scala.concurrent.ExecutionContext

object JournalStatement {

  def createTable(name: TableName): String = {
    s"""
       |CREATE TABLE IF NOT EXISTS ${ name.asCql } (
       |id text,
       |topic text,
       |segment bigint,
       |seq_nr bigint,
       |timestamp timestamp,
       |payload blob,
       |tags set<text>,
       |partition int,
       |offset bigint,
       |PRIMARY KEY ((id, topic, segment), seq_nr, timestamp))
       |""".stripMargin
    //        WITH gc_grace_seconds =${gcGrace.toSeconds} TODO
    //        AND compaction = ${config.tableCompactionStrategy.asCQL}
  }


  object InsertRecord {
    type Type = (Key, ReplicatedEvent, SegmentNr) => BoundStatement

    def apply(name: TableName, prepare: String => Async[PreparedStatement]): Async[Type] = {
      val query =
        s"""
           |INSERT INTO ${ name.asCql } (id, topic, segment, seq_nr, timestamp, payload, tags, partition, offset)
           |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
           |""".stripMargin

      for {
        prepared <- prepare(query)
      } yield {
        (key: Key, replicated: ReplicatedEvent, segment: SegmentNr) =>
          // TODO make up better way for creating queries
          val event = replicated.event
          prepared
            .bind()
            .encode("id", key.id)
            .encode("topic", key.topic)
            .encode("segment", segment.value)
            .encode("seq_nr", event.seqNr)
            .encode("timestamp", replicated.timestamp)
            .encode("payload", event.payload)
            .encode("tags", event.tags)
            .encode("partition", replicated.partitionOffset.partition)
            .encode("offset", replicated.partitionOffset.offset)
      }
    }
  }


  // TODO rename along with EventualRecord2
  object SelectLastRecord {

    type Type = (Key, SegmentNr, SeqNr) => Async[Option[Pointer]]

    def apply(name: TableName, session: PrepareAndExecute): Async[Type] = {
      val query =
        s"""
           |SELECT seq_nr, partition, offset
           |FROM ${ name.asCql }
           |WHERE id = ?
           |AND topic = ?
           |AND segment = ?
           |AND seq_nr >= ?
           |ORDER BY seq_nr
           |DESC LIMIT 1
           |""".stripMargin

      for {
        prepared <- session.prepare(query)
      } yield {
        (key: Key, segment: SegmentNr, from: SeqNr) =>
          val bound = prepared.bind(key.id, key.topic, segment.value: LongJ, from: LongJ)
          for {
            result <- session.execute(bound)
          } yield for {
            row <- Option(result.one())
          } yield {
            val partitionOffset = PartitionOffset(
              partition = row.decode[Partition]("partition"),
              offset = row.decode[Offset]("offset"))
            Pointer(
              seqNr = row.decode[SeqNr]("seq_nr"),
              partitionOffset = partitionOffset)
          }
      }
    }
  }


  object SelectRecords {

    trait Type {
      def apply[S](key: Key, segment: SegmentNr, range: SeqRange, state: S)(f: Fold[S, ReplicatedEvent]): Async[Switch[S]]
    }

    def apply(name: TableName, session: PrepareAndExecute)(implicit ec: ExecutionContext): Async[Type] = {
      val query =
        s"""
           |SELECT seq_nr, timestamp, payload, tags, partition, offset FROM ${ name.asCql }
           |WHERE id = ?
           |AND topic = ?
           |AND segment = ?
           |AND seq_nr >= ?
           |AND seq_nr <= ?
           |""".stripMargin

      for {
        prepared <- session.prepare(query)
      } yield {
        new Type {
          def apply[S](key: Key, segment: SegmentNr, range: SeqRange, s: S)(f: Fold[S, ReplicatedEvent]) = {

            val fetchSize = 10 // TODO
            val fetchThreshold = fetchSize / 2

            // TODO avoid casting via providing implicit converters
            val bound = prepared.bind(key.id, key.topic, segment.value: LongJ, range.from: LongJ, range.to: LongJ)
            bound.setFetchSize(fetchSize)

            for {
              result <- session.execute(bound)
              result <- result.foldWhile(fetchThreshold, s) { case (s, row) =>
                val partitionOffset = PartitionOffset(
                  partition = row.decode[Partition]("partition"),
                  offset = row.decode[Offset]("offset"))
                val event = Event(
                  seqNr = row.decode[SeqNr]("seq_nr"),
                  tags = row.decode[Tags]("tags"),
                  payload = row.decode[Bytes]("payload"))
                val replicated = ReplicatedEvent(
                  event = event,
                  timestamp = row.decode[Instant]("timestamp"),
                  partitionOffset = partitionOffset)
                f(s, replicated)
              }
            } yield result
          }
        }
      }
    }
  }

  object DeleteRecords {
    type Type = (Key, SegmentNr, SeqNr) => Async[Unit]

    def apply(name: TableName, session: PrepareAndExecute): Async[Type] = {
      val query =
        s"""
           |DELETE FROM ${ name.asCql }
           |WHERE id = ?
           |AND topic = ?
           |AND segment = ?
           |AND seq_nr <= ?
           |""".stripMargin

      for {
        prepared <- session.prepare(query)
      } yield {
        (key: Key, segment: SegmentNr, seqNr: SeqNr) =>
          // TODO avoid casting via providing implicit converters
          val bound = prepared.bind(key.id, key.topic, segment.value: LongJ, seqNr: LongJ)
          session.execute(bound).unit
      }
    }
  }
}
