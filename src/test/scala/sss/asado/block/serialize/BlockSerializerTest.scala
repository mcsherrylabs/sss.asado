package sss.asado.block.serialize

import block._
import org.scalatest.{FlatSpec, Matchers}
import sss.asado.account.PrivateKeyAccount
import sss.asado.ledger.serialize.SignedTxTest
import sss.asado.util.SeedBytes

/**
  * Created by alan on 2/15/16.
  */
class BlockSerializerTest extends FlatSpec with Matchers {


  lazy val pkPair = PrivateKeyAccount(SeedBytes(32))

  val height = 33
  val id = 20000
  val stx = SignedTxTest.createSignedTx

  "A Confirm Tx " should " be correctly serialised and deserialized " in {
    val c = ConfirmTx(stx, height, id)
    val asBytes = c.toBytes
    val backAgain = asBytes.toConfirmTx
    assert(backAgain === c)

  }

  "An Ack Confirm Tx" should " be corrrectly serialised and deserialized " in {
    val c = AckConfirmTx(stx.txId, height, id)
    val asBytes = c.toBytes
    val backAgain = asBytes.toAckConfirmTx
    assert(backAgain.height === c.height)
    assert(backAgain.id === c.id)
    assert(backAgain.txId === c.txId)
    assert(backAgain === c)

  }


  "A Find Leader " should " be corrrectly serialised and deserialized " in {
    val c = FindLeader(1234, 4, "Holy Karelia!")
    val asBytes = c.toBytes
    val backAgain = asBytes.toFindLeader
    assert(backAgain.height === c.height)
    assert(backAgain.nodeId === c.nodeId)
    assert(backAgain === c)
  }

  "A Get Page Tx" should " be corrrectly serialised and deserialized " in {
    val c = GetTxPage(Long.MaxValue, 4, 45)
    val asBytes = c.toBytes
    val backAgain: GetTxPage = asBytes.toGetTxPage
    assert(backAgain.pageSize === c.pageSize)
    assert(backAgain.index === c.index)
    assert(backAgain.blockHeight === c.blockHeight)
    assert(backAgain === c)
  }
}
