package sss.asado


import scorex.crypto.signatures.SigningFunctions.PublicKey
import sss.asado.account.PublicKeyAccount
import sss.asado.identityledger.serialize._
import sss.asado.util.ByteArrayComparisonOps._
import sss.asado.util.Serialize.ToBytes
import sss.asado.util.hash.SecureCryptographicHash

/**
  * Created by alan on 5/31/16.
  */
package object identityledger {

  abstract class IdentityLedgerMessage(toCheck: String*) {
    val txId: Array[Byte] = SecureCryptographicHash.hash(this.toBytes)
    require(toCheck.forall(_.forall(c => c.isLower || c.isDigit)),
      "Unsupported character used, simple lowercase alpha numerics only.")
    private[identityledger] val uniqueMessage = System.nanoTime()
  }

  private[identityledger] val ClaimCode = 1.toByte
  private[identityledger] val LinkCode = 2.toByte
  private[identityledger] val UnLinkByKeyCode = 3.toByte
  private[identityledger] val UnLinkCode = 4.toByte
  private[identityledger] val RescueCode = 5.toByte
  private[identityledger] val LinkRescuerCode = 6.toByte
  private[identityledger] val UnLinkRescuerCode = 7.toByte

  case class Claim(identity: Identity, pKey: PublicKey) extends IdentityLedgerMessage(identity.value) {
    override def equals(obj: scala.Any): Boolean = {
      obj match {
        case that: Claim =>
          that.identity == identity &&
          that.uniqueMessage == uniqueMessage &&
            (that.pKey isSame pKey)
        case _ => false
      }
    }

    override def hashCode(): Int = uniqueMessage.hashCode
  }

  case class Link(identity: Identity,
                  pKey: PublicKey,
                  tag: IdentityTag
                 ) extends IdentityLedgerMessage(identity.value) {

    override def equals(obj: scala.Any): Boolean = {
      obj match {
        case that: Link =>
          that.identity == identity &&
            that.tag == tag &&
            that.uniqueMessage == uniqueMessage &&
            (that.pKey isSame pKey)
        case _ => false
      }
    }

    override def hashCode(): Int = uniqueMessage.hashCode
  }

  case class Rescue(rescuer: Identity, identity: Identity, pKey: PublicKey, tag: IdentityTag)
    extends IdentityLedgerMessage(identity.value, rescuer.value) {

    override def equals(obj: scala.Any): Boolean = {
      obj match {
        case that: Rescue =>
          that.identity == identity &&
          that.rescuer == rescuer &&
            that.tag == tag &&
            that.uniqueMessage == uniqueMessage &&
            (that.pKey isSame pKey)
        case _ => false
      }
    }

    override def hashCode(): Int = uniqueMessage.hashCode
  }

  case class LinkRescuer(rescuer: Identity, identity: Identity) extends IdentityLedgerMessage(rescuer.value, identity.value)
  case class UnLinkRescuer(rescuer: Identity, identity: Identity) extends IdentityLedgerMessage(rescuer.value, identity.value)

  case class UnLinkByKey(identity: Identity, pKey: PublicKey) extends IdentityLedgerMessage(identity.value) {
    override def equals(obj: scala.Any): Boolean = {
      obj match {
        case that: UnLinkByKey =>
          that.identity == identity &&
            that.uniqueMessage == uniqueMessage &&
            (that.pKey isSame pKey)
        case _ => false
      }
    }

    override def hashCode(): Int = uniqueMessage.hashCode
  }
  case class UnLink(identity: Identity, tag: IdentityTag) extends IdentityLedgerMessage(identity.value, tag.value)

  case class TaggedIdentity(identity: Identity , tag:IdentityTag)
  case class TaggedPublicKeyAccount(account: PublicKeyAccount, tag:IdentityTag)

  implicit class IdentityLedgerMessageFromBytes(bytes: Array[Byte]) {
    def toIdentityLedgerMessage: IdentityLedgerMessage = bytes.head match {
      case ClaimCode => bytes.toClaim
      case LinkCode => bytes.toLink
      case UnLinkByKeyCode => bytes.toUnLinkByKey
      case UnLinkCode => bytes.toUnLink
      case RescueCode => bytes.toRescue
      case LinkRescuerCode => bytes.toLinkRescuer
      case UnLinkRescuerCode => bytes.toUnLinkRescuer
    }
  }
  implicit class IdentityLedgerMessageToBytes(idLedgerMsg: IdentityLedgerMessage) {
    def toBytes: Array[Byte] = idLedgerMsg match {
      case msg: Claim => msg.toBytes
      case msg: Link => msg.toBytes
      case msg: UnLink => msg.toBytes
      case msg: UnLinkByKey => msg.toBytes
      case msg: LinkRescuer => msg.toBytes
      case msg: Rescue => msg.toBytes
      case msg: UnLinkRescuer => msg.toBytes
    }
  }

  implicit class ClaimToBytes(claim: Claim) extends ToBytes {
    override def toBytes: Array[Byte] = ClaimSerializer.toBytes(claim)
  }

  implicit class ClaimFromBytes(bytes: Array[Byte])  {
    def toClaim: Claim = ClaimSerializer.fromBytes(bytes)
  }

  implicit class LinkToBytes(claim: Link) extends ToBytes {
    override def toBytes: Array[Byte] = LinkSerializer.toBytes(claim)
  }

  implicit class LinkFromBytes(bytes: Array[Byte])  {
    def toLink: Link = LinkSerializer.fromBytes(bytes)
  }

  implicit class UnLinkByKeyToBytes(claim: UnLinkByKey) extends ToBytes {
    override def toBytes: Array[Byte] = UnLinkByKeySerializer.toBytes(claim)
  }

  implicit class UnLinkByKeyFromBytes(bytes: Array[Byte])  {
    def toUnLinkByKey: UnLinkByKey = UnLinkByKeySerializer.fromBytes(bytes)
  }

  implicit class UnLinkToBytes(claim: UnLink) extends ToBytes {
    override def toBytes: Array[Byte] = UnLinkSerializer.toBytes(claim)
  }

  implicit class UnLinkFromBytes(bytes: Array[Byte])  {
    def toUnLink: UnLink = UnLinkSerializer.fromBytes(bytes)
  }

  implicit class LinkRescuerFromBytes(bytes: Array[Byte])  {
    def toLinkRescuer: LinkRescuer = LinkRescuerSerializer.fromBytes(bytes)
  }

  implicit class LinkRescuerToBytes(rescuer: LinkRescuer) extends ToBytes {
    override def toBytes: Array[Byte] = LinkRescuerSerializer.toBytes(rescuer)
  }

  implicit class UnLinkRescuerFromBytes(bytes: Array[Byte])  {
    def toUnLinkRescuer: UnLinkRescuer = UnLinkRescuerSerializer.fromBytes(bytes)
  }

  implicit class UnLinkRescuerToBytes(rescuer: UnLinkRescuer) extends ToBytes {
    override def toBytes: Array[Byte] = UnLinkRescuerSerializer.toBytes(rescuer)
  }

  implicit class RescueFromBytes(bytes: Array[Byte])  {
    def toRescue: Rescue = RescueSerializer.fromBytes(bytes)
  }

  implicit class RescueToBytes(rescue: Rescue) extends ToBytes {
    override def toBytes: Array[Byte] = RescueSerializer.toBytes(rescue)
  }
}


