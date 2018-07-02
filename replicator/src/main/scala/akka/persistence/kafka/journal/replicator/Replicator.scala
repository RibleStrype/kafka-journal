package akka.persistence.kafka.journal.replicator

import java.util.UUID

import com.evolutiongaming.cassandra.{CassandraConfig, CreateCluster}
import com.evolutiongaming.kafka.journal.ally.cassandra.{AllyCassandra, SchemaConfig}
import com.evolutiongaming.kafka.journal.ally.{AllyDb, AllyRecord, PartitionOffset}
import com.evolutiongaming.kafka.journal.{Action, EventsSerializer}
import com.evolutiongaming.skafka.Bytes
import com.evolutiongaming.skafka.consumer._
import play.api.libs.json.Json

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

object Replicator {

  type Shutdown = () => Future[Unit]

  object Shutdown {
    val Empty: Shutdown = () => Future.unit
  }

  def apply(implicit ec: ExecutionContext): Shutdown = {
    val groupId = UUID.randomUUID().toString
    val consumerConfig = ConsumerConfig.Default.copy(
      groupId = Some(groupId),
      autoOffsetReset = AutoOffsetReset.Earliest)
    val consumer = CreateConsumer[String, Bytes](consumerConfig)
    val cassandraConfig = CassandraConfig.Default
    val cluster = CreateCluster(cassandraConfig)
    val session = cluster.connect()
    val schemaConfig = SchemaConfig.Default
    val allyDb = AllyCassandra(session, schemaConfig)
    apply(consumer, allyDb)
  }

  def apply(
    consumer: Consumer[String, Bytes],
    allyDb: AllyDb,
    pollTimeout: FiniteDuration = 100.millis,
    closeTimeout: FiniteDuration = 10.seconds)(implicit ec: ExecutionContext): Shutdown = {

    //    val executorService = Executors.newSingleThreadExecutor()
    //    val executionContext = ExecutionContext.fromExecutor(executorService)

    val topics = List("journal-test9")
    consumer.subscribe(topics)

    val ecBlocking = ec // TODO

    // TODO replace with StateVar
    @volatile var shutdown = Option.empty[Promise[Unit]]


    def poll(): Unit = {

      shutdown match {
        case Some(shutdown) =>
          val future = Future {
            consumer.close(closeTimeout)
          }(ecBlocking)

          shutdown.completeWith(future)

        case None =>
          val records = consumer.poll(pollTimeout)
          val futures = for {
            (topicPartition, records) <- records.values
          } yield {

            def toAction(record: ConsumerRecord[String, Bytes]) = {
              val headers = record.headers
              val header = headers.find { _.key == "journal.action" }.get
              val json = Json.parse(header.value)
              json.as[Action]
            }

            val allyRecords = for {
              record <- records
              id <- record.key.toIterable
              action = toAction(record)
              action <- action match {
                case action: Action.Truncate => Nil // TODO
                case action: Action.Append   => List(action)
                case action: Action.Mark     => Nil
              }
              topic = record.topic
              event <- EventsSerializer.EventsFromBytes(record.value, topic).events.to[Iterable]
            } yield {

              // TODO different created and inserted timestamps

              val seqNr = event.seqNr

              println(s"replicate id: $id, topic: $topic, seqNr: $seqNr")

              AllyRecord(
                id = id,
                seqNr = seqNr,
                timestamp = Platform.currentTime,
                payload = event.payload,
                tags = Set.empty, // TODO
                partitionOffset = PartitionOffset(
                  partition = record.partition,
                  offset = record.offset))
            }

            allyDb.save(allyRecords)
          }

          val future = Future.sequence(futures)
          Await.result(future, 1.minute)

          poll()
      }
    }

    val future = Future {
      poll()
    }

    future.failed.foreach { failure =>
      failure.printStackTrace()
    }

    () => {
      val promise = Promise[Unit]()
      shutdown = Some(promise)
      promise.future
    }
  }
}