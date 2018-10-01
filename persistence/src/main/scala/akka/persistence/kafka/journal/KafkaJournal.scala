package akka.persistence.kafka.journal

import akka.actor.ActorSystem
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.{AtomicWrite, PersistentRepr}
import com.evolutiongaming.cassandra.CreateCluster
import com.evolutiongaming.concurrent.async.Async
import com.evolutiongaming.config.ConfigHelper._
import com.evolutiongaming.kafka.journal.AsyncHelper._
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.kafka.journal.eventual.EventualJournal
import com.evolutiongaming.kafka.journal.eventual.cassandra.{EventualCassandra, EventualCassandraConfig}
import com.evolutiongaming.safeakka.actor.ActorLog
import com.evolutiongaming.skafka.consumer.Consumer
import com.evolutiongaming.skafka.producer.Producer
import com.typesafe.config.Config

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.Try
import scala.util.control.NonFatal

class KafkaJournal(config: Config) extends AsyncWriteJournal {
  import KafkaJournal._

  implicit val system: ActorSystem = context.system
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  val log: ActorLog = ActorLog(system, classOf[KafkaJournal])

  val adapter: JournalsAdapter = adapterOf(
    toKey(),
    origin(),
    serializer(),
    journalConfig(),
    metrics())

  def toKey(): ToKey = ToKey(config)

  def cassandraConfig(): EventualCassandraConfig = {
    config.getOpt[Config]("cassandra") match {
      case Some(config) => EventualCassandraConfig(config)
      case None         => EventualCassandraConfig.Default
    }
  }

  def journalConfig() = JournalConfig(config)

  def origin(): Option[Origin] = Some {
    Origin.HostName orElse Origin.AkkaHost(system) getOrElse Origin.AkkaName(system)
  }

  def serializer(): EventSerializer = EventSerializer(system)

  def metrics(): Metrics = Metrics.Empty

  def adapterOf(
    toKey: ToKey,
    origin: Option[Origin],
    serializer: EventSerializer,
    journalConfig: JournalConfig,
    metrics: Metrics): JournalsAdapter = {

    log.debug(s"Journal config: $journalConfig")

    val ecBlocking = system.dispatchers.lookup("evolutiongaming.kafka-journal.persistence.journal.blocking-dispatcher")

    val producer = {
      val producer = Producer(journalConfig.producer, ecBlocking)
      metrics.producer.fold(producer) { Producer(producer, _) }
    }

    val closeTimeout = 10.seconds // TODO from config
    val connectTimeout = 5.seconds // TODO from config

    system.registerOnTermination {
      val future = for {
        _ <- producer.flush()
        _ <- producer.close(closeTimeout)
      } yield ()
      try Await.result(future, closeTimeout) catch {
        case NonFatal(failure) => log.error(s"failed to shutdown producer $failure", failure)
      }
    }

    val topicConsumer = TopicConsumer(journalConfig.consumer, ecBlocking, metrics = metrics.consumer)

    val eventualJournal: EventualJournal = {
      val config = cassandraConfig()
      val cluster = CreateCluster(config.client)
      val session = Await.result(cluster.connect(), connectTimeout) // TODO handle this properly
      system.registerOnTermination {
        val result = for {
          _ <- session.close()
          _ <- cluster.close()
        } yield {}
        try {
          Await.result(result, closeTimeout)
        } catch {
          case NonFatal(failure) => log.error(s"failed to shutdown cassandra $failure", failure)
        }
      }
      // TODO read only cassandra statements

      {
        val log = ActorLog(system, classOf[EventualJournal])
        val journal = {
          val journal = EventualCassandra(session, config, Log(log))
          EventualJournal(journal, log)
        }
        metrics.eventualJournal.fold(journal) { EventualJournal(journal, _) }
      }
    }

    val journal = {
      val journal = Journal(
        producer = producer,
        origin = origin,
        topicConsumer = topicConsumer,
        eventual = eventualJournal,
        pollTimeout = journalConfig.pollTimeout,
        closeTimeout = journalConfig.closeTimeout)

      metrics.journal.fold(journal) { Journal(journal, _) }
    }
    JournalsAdapter(log, toKey, journal, serializer)
  }

  // TODO optimise concurrent calls asyncReplayMessages & asyncReadHighestSequenceNr for the same persistenceId
  def asyncWriteMessages(atomicWrites: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = {
    adapter.write(atomicWrites)
  }

  def asyncDeleteMessagesTo(persistenceId: PersistenceId, to: Long): Future[Unit] = {
    SeqNr.opt(to) match {
      case Some(to) => adapter.delete(persistenceId, to)
      case None     => Future.unit
    }
  }

  def asyncReplayMessages(persistenceId: PersistenceId, from: Long, to: Long, max: Long)
    (f: PersistentRepr => Unit): Future[Unit] = {

    val seqNrFrom = SeqNr(from, SeqNr.Min)
    val seqNrTo = SeqNr(to, SeqNr.Max)
    val range = SeqRange(seqNrFrom, seqNrTo)
    adapter.replay(persistenceId, range, max)(f)
  }

  def asyncReadHighestSequenceNr(persistenceId: PersistenceId, from: Long): Future[Long] = {
    val seqNr = SeqNr(from, SeqNr.Min)
    for {
      seqNr <- adapter.lastSeqNr(persistenceId, seqNr)
    } yield seqNr match {
      case Some(seqNr) => seqNr.value
      case None        => from
    }
  }
}

object KafkaJournal {

  final case class Metrics(
    journal: Option[Journal.Metrics[Async]] = None,
    eventualJournal: Option[EventualJournal.Metrics[Async]] = None,
    producer: Option[Producer.Metrics] = None,
    consumer: Option[Consumer.Metrics] = None)

  object Metrics {
    val Empty: Metrics = Metrics()
  }
}