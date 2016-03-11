package sss.asado.ledger.serialize

import ledger._
import org.scalatest.{FlatSpec, Matchers}
import sss.asado.util.SeedBytes

/**
  * Created by alan on 2/15/16.
  */

class TxIndexTest extends FlatSpec with Matchers {

  val randomTxId: TxId = SeedBytes(32)
  val copyRandomTxId: TxId = java.util.Arrays.copyOf(randomTxId.array, randomTxId.length)
  val txIndex = TxIndex(randomTxId, 3456)

  "A TxIndex" should " be parseable to bytes " in {
    val bytes: Array[Byte] = txIndex.toBytes
  }

  it should " be parseable from bytes to same instance " in {
    val bytes: Array[Byte] = txIndex.toBytes
    val backAgain = bytes.toTxIndex

    assert(backAgain.index === txIndex.index)
    assert(backAgain.txId === txIndex.txId)
    assert(backAgain === txIndex)

  }

  " TxIndex case classes created from same elements " should " be equal " in {
    val a = TxIndex(randomTxId, 342)
    val b = TxIndex(copyRandomTxId, 342)
    assert(randomTxId === copyRandomTxId)
    assert(a === b)
  }
}
