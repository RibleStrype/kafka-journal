package com.evolutiongaming.kafka.journal.eventual.cassandra

import cats.Parallel
import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import com.evolutiongaming.kafka.journal.{Origin, Setting, Settings}
import com.evolutiongaming.scassandra.TableName
import com.evolutiongaming.catshelper.{BracketThrowable, FromFuture, LogOf, ToFuture}
import com.evolutiongaming.kafka.journal.eventual.cassandra.CassandraHelper._

import scala.util.Try

object SetupSchema { self =>

  def migrate[F[_] : BracketThrowable : CassandraSession : CassandraSync : Settings](
    schema: Schema,
    fresh: CreateSchema.Fresh
  ): F[Unit] = {

    def addHeaders(table: TableName)(implicit cassandraSync: CassandraSync[F]) = {
      val query = JournalStatements.addHeaders(table)
      val fa = query.execute.first.redeem[Unit](_ => (), _ => ())
      cassandraSync { fa }
    }

    val schemaVersion = "schema-version"

    def version(setting: Option[Setting]) = {
      for {
        setting <- setting
        version <- Try(setting.value.toInt).toOption
      } yield version
    }

    def migrate = {
      for {
        _ <- if (fresh) ().pure[F] else addHeaders(schema.journal)
        _ <- Settings[F].setIfEmpty(schemaVersion, "0")
      } yield {}
    }

    for {
      setting <- Settings[F].get(schemaVersion)
      _       <- version(setting).fold(migrate)(_ => ().pure[F])
    } yield {}
  }

  def apply[F[_] : Concurrent : Parallel : Timer : CassandraCluster : CassandraSession : FromFuture : ToFuture : LogOf](
    config: SchemaConfig,
    origin: Option[Origin]
  ): F[Schema] = {

    def migrate(
      schema: Schema,
      fresh: CreateSchema.Fresh)(implicit
      cassandraSync: CassandraSync[F],
      settings: Settings[F]
    ) = {

      self.migrate[F](schema, fresh)
    }

    def createSchema(implicit cassandraSync: CassandraSync[F]) = CreateSchema(config)
    
    for {
      cassandraSync   <- CassandraSync.of[F](config, origin)
      ab              <- createSchema(cassandraSync)
      (schema, fresh)  = ab
      settings        <- SettingsCassandra.of[F](schema, origin)
      _               <- migrate(schema, fresh)(cassandraSync, settings)
    } yield schema
  }
}
