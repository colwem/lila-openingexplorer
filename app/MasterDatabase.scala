package lila.openingexplorer

import java.io.File
import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }

import fm.last.commons.kyoto.{ KyotoDb, WritableVisitor }

import chess.{ Hash, Situation, MoveOrDrop, PositionHash }

final class MasterDatabase {

  private val db = Util.wrapLog(
    "Loading master database...",
    "Master database loaded!"
  ) {
      Kyoto.builder(Config.explorer.master.kyoto).buildAndOpen
    }

  def query(situation: Situation, maxMoves: Int, maxGames: Int): QueryResult = {
    val entry = probe(situation)
    new QueryResult(
      entry.totalWhite,
      entry.totalDraws,
      entry.totalBlack,
      entry.averageRating,
      entry.moves.toList
        .filterNot(_._2.isEmpty)
        .sortBy(-_._2.total)
        .take(maxMoves).flatMap {
          case (uci, stats) => Util.moveFromUci(situation, uci).map(_ -> stats)
        },
      List.empty,
      entry.gameRefs.distinct.sortBy(-_.averageRating).take(maxGames)
    )
  }

  def probe(situation: Situation): SubEntry = probe(MasterDatabase.hash(situation))

  private def probe(h: PositionHash): SubEntry = {
    Option(db.get(h)) match {
      case Some(bytes) => unpack(bytes)
      case None => SubEntry.empty
    }
  }

  private def unpack(b: Array[Byte]): SubEntry = {
    val in = new ByteArrayInputStream(b)
    SubEntry.read(in)
  }

  private def pack(entry: SubEntry): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    entry.write(out)
    out.toByteArray
  }

  def exists(situation: Situation): Boolean = db.exists(MasterDatabase.hash(situation))

  def merge(gameRef: GameRef, move: MoveOrDrop) = {
    val hash = MasterDatabase.hash(move.fold(_.situationBefore, _.situationBefore))
    val uci = move.left.map(_.toUci).right.map(_.toUci)

    db.accept(hash, new WritableVisitor {
      def record(key: PositionHash, value: Array[Byte]): Array[Byte] =
        pack(unpack(value).withGameRef(gameRef, uci))

      def emptyRecord(key: PositionHash): Array[Byte] =
        pack(SubEntry.fromGameRef(gameRef, uci))
    })
  }

  def subtract(gameRef: GameRef, move: MoveOrDrop) = {
    val hash = MasterDatabase.hash(move.fold(_.situationBefore, _.situationBefore))
    val uci = move.left.map(_.toUci).right.map(_.toUci)

    db.accept(hash, new WritableVisitor {
      def record(key: PositionHash, value: Array[Byte]): Array[Byte] = {
        val subtracted = unpack(value).withoutExistingGameRef(gameRef, uci)
        if (subtracted.isEmpty) WritableVisitor.REMOVE else pack(subtracted)
      }

      // should not happen
      def emptyRecord(key: PositionHash): Array[Byte] = WritableVisitor.NOP
    })
  }

  def uniquePositions = db.recordCount()

  def close = {
    db.close()
  }
}

object MasterDatabase {

  val hash = new Hash(16) // 128 bit Zobrist hasher
}
