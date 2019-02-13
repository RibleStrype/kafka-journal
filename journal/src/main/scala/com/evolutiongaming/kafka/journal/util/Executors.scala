package com.evolutiongaming.kafka.journal.util

import java.util.concurrent.{ExecutorService, ScheduledExecutorService, Executors => ExecutorsJ}

import cats.effect.{Resource, Sync}
import com.evolutiongaming.kafka.journal.Runtime

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

object Executors {

  def blocking[F[_] : Sync : Runtime]: Resource[F, ExecutionContextExecutorService] = {
    for {
      cores       <- Resource.liftF(Runtime[F].availableCores)
      parallelism  = (cores + 2) * 4
      pool        <- forkJoin(parallelism)
    } yield pool
  }

  def nonBlocking[F[_] : Sync : Runtime]: Resource[F, ExecutionContextExecutorService] = {
    for {
      cores       <- Resource.liftF(Runtime[F].availableCores)
      parallelism  = cores + 4
      pool        <- forkJoin(parallelism)
    } yield pool
  }

  def forkJoin[F[_] : Sync](parallelism: Int): Resource[F, ExecutionContextExecutorService] = {
    resource { ExecutorsJ.newWorkStealingPool(parallelism) }
  }

  def scheduled[F[_] : Sync](parallelism: Int): Resource[F, ScheduledExecutorService] = {
    Resource.make {
      Sync[F].delay { ExecutorsJ.newScheduledThreadPool(parallelism) }
    } { es =>
      Sync[F].delay { es.shutdown() }
    }
  }

  private def resource[F[_] : Sync](es: => ExecutorService): Resource[F, ExecutionContextExecutorService] = {
    val result = Sync[F].delay {
      val ec = ExecutionContext.fromExecutorService(es)
      val release = Sync[F].delay { ec.shutdown() }
      (ec, release)
    }
    Resource(result)
  }
}