package sss.asado.account

import org.scalatest.{FlatSpec, Matchers}
import sss.asado.DummySeedBytes
import sss.asado.util.ByteArrayComparisonOps

/**
  * Created by alan on 2/11/16.
  */
class NodeIdentitySpec extends FlatSpec with Matchers with ByteArrayComparisonOps {


  val nodeIdentityManager = new NodeIdentityManager(DummySeedBytes)
  val newId = "Totallyrandomw"
  val newTag = "tag1"
  val passPhrase = "not_password"
  nodeIdentityManager.deleteKey(newId, newTag)

  "An node identity " should " generate a new public and private key " in {
    val nodeIdentity = nodeIdentityManager(newId, newTag, passPhrase)
    assert(nodeIdentity.id == newId)
    assert(nodeIdentity.tag == newTag)
  }

  it should " be retrievable " in {
    val nodeIdentity = nodeIdentityManager(newId, newTag, passPhrase)
    assert(nodeIdentity.id == newId)
    assert(nodeIdentity.tag == newTag)
  }

}
