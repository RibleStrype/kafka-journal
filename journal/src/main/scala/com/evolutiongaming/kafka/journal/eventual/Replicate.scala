package com.evolutiongaming.kafka.journal.eventual

import com.evolutiongaming.kafka.journal.{PartitionOffset, ReplicatedEvent, SeqNr}


sealed trait Replicate

object Replicate {
  // TODO both cannot be empty
  final case class DeleteToKnown(deleteTo: Option[SeqNr], replicated: List[ReplicatedEvent]) extends Replicate

  // TODO consider creating case class for unbounded deletedTo
  final case class DeleteUnbound(deleteTo: SeqNr) extends Replicate
}


final case class Pointer(seqNr: SeqNr, partitionOffset: PartitionOffset)