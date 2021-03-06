package sss.openstar.wallet

import akka.actor.ActorRef
import scorex.crypto.signatures.SigningFunctions.PublicKey
import sss.ancillary.Logging
import sss.openstar.account.NodeIdentity
import sss.openstar.{MessageKeys, UniqueNodeIdentifier}
import sss.openstar.balanceledger._
import sss.openstar.chains.TxWriterActor.{InternalCommit, InternalTxResult}
import sss.openstar.contract._
import sss.openstar.identityledger.IdentityServiceQuery
import sss.openstar.ledger._
import sss.openstar.tools.SendTxSupport.SendTx
import sss.openstar.wallet.Wallet.PublicKeyFilter
import sss.openstar.wallet.WalletPersistence.Lodgement

import scala.concurrent.Await
import scala.concurrent.duration.Duration


/**
  * This wallet will attempt to double spend depending on when the unspent are marked spent!
  *
  */
object Wallet {
  type PublicKeyFilter = PublicKey => Boolean


  case class Payment(identity: String,
                     amount: Int,
                     txIdentifier: Option[String] = None,
                     blockHeight: Long = 0)
  case class UnSpent(txIndex: TxIndex, out: TxOutput)
}

class WalletIndexTracker(nodeControlsPublicKey: PublicKeyFilter,
                         identityServiceQuery: IdentityServiceQuery,
                         val id: UniqueNodeIdentifier,
                         walletPersistence: WalletPersistence
                    ) extends ((TxIndex, TxOutput) => Option[Lodgement]) {


  override def apply(txIndex: TxIndex, txOutput: TxOutput): Option[Lodgement] = {
    toLodgement(txIndex, txOutput)
      .map (credit (_))
  }

  def credit(lodgement: Lodgement): Lodgement = {
    walletPersistence.track(lodgement)
  }

  def toLodgement(txIndex: TxIndex, txOutput: TxOutput): Option[Lodgement] = {

    txOutput.encumbrance match {
      case SinglePrivateKey(pKey, minBlockHeight) =>

        if(nodeControlsPublicKey(pKey)) {
          Option(Lodgement(txIndex, txOutput, minBlockHeight))
        } else {
          identityServiceQuery.identify(pKey).flatMap { acc =>
            if (acc.identity == id)
              Option(Lodgement(txIndex, txOutput, minBlockHeight))
            else
              None
          }
        }

      case SingleIdentityEnc(nId, minBlockHeight) if nId == id =>
        Option(Lodgement(txIndex, txOutput, minBlockHeight))

      case SaleOrReturnSecretEnc(returnIdentity, _, _, _) if returnIdentity == id =>
        Option(Lodgement(txIndex, txOutput, 0))

      case NullEncumbrance => Option(Lodgement(txIndex, txOutput, 0))
      case _ => None
    }
  }
}

class Wallet(val identity: NodeIdentity,
             balanceLedger: BalanceLedgerQuery,
             identityServiceQuery: IdentityServiceQuery,
             walletPersistence: WalletPersistence,
             currentBlockHeight: () => Long,
             nodeControlsPublicKey: PublicKeyFilter
            )(implicit sendTx: SendTx) extends Logging {

  val walletTracker: WalletIndexTracker = new WalletIndexTracker(nodeControlsPublicKey, identityServiceQuery, identity.id, walletPersistence)

  import Wallet._
  import scala.concurrent.ExecutionContext.Implicits.global

  def payAsync(sendingActor: ActorRef, payment: Payment): Unit = {
    log.info(s"Attempting to create a tx for ${payment.amount} with wallet balance ${balance()}")
    val tx = createTx(payment.amount)
    val enc = encumberToIdentity(payment.blockHeight, payment.identity) //SingleIdentityEnc(someIdentity, atBlockHeight)
    val finalTxUnsigned = appendOutputs(tx, TxOutput(payment.amount, enc))
    val txIndex = TxIndex(finalTxUnsigned.txId, finalTxUnsigned.outs.size - 1)
    val signedTx = sign(finalTxUnsigned)

    sendTx(LedgerItem(MessageKeys.BalanceLedger, signedTx.txId, signedTx.toBytes)).map {
      case r@InternalCommit(_, blockChainTxId) =>
        update(blockChainTxId.blockTxId.txId, tx.ins, tx.outs, blockChainTxId.height)
        r
      case x => x
    }.map(sendingActor ! _)

  }

  def credit(lodgement: Lodgement): Unit = walletTracker.credit(lodgement)

  def pay(payment: Payment)(implicit timeout: Duration): InternalTxResult = {
    val tx = createTx(payment.amount)
    val enc = encumberToIdentity(payment.blockHeight, payment.identity)
    val finalTxUnsigned = appendOutputs(tx, TxOutput(payment.amount, enc))
    val txIndex = TxIndex(finalTxUnsigned.txId, finalTxUnsigned.outs.size - 1)
    val signedTx = sign(finalTxUnsigned)

    val f = sendTx(LedgerItem(MessageKeys.BalanceLedger, signedTx.txId, signedTx.toBytes)).map {
      case r@InternalCommit(_, blockChainTxId) =>
        update(blockChainTxId.blockTxId.txId, tx.ins, tx.outs, blockChainTxId.height)
        r
      case x => x
    }
    Await.result(f, timeout)

  }


  def markSpent(spentIns: Seq[TxInput]): Unit = {
    spentIns.foreach { in =>
      walletPersistence.markSpent(in.txIndex)
    }
  }

  def update(txId: TxId,
             debits: Seq[TxInput],
             creditsOrderedByIndex: Seq[TxOutput],
             inBlock: Long = currentBlockHeight()): Unit = walletPersistence.tx {
    markSpent(debits)
    creditsOrderedByIndex.indices foreach { i =>
      walletTracker.credit(Lodgement(TxIndex(txId, i), creditsOrderedByIndex(i), inBlock))
    }
  }

  def prependOutputs(tx: Tx, txOutput: TxOutput*): Tx = {
    val newTxOuts = txOutput ++ tx.outs
    StandardTx(tx.ins, newTxOuts)
  }

  def appendOutputs(tx: Tx, txOutput: TxOutput*): Tx = {
    val newTxOuts = tx.outs ++ txOutput
    StandardTx(tx.ins, newTxOuts)
  }

  def sign(tx: Tx, secret: Array[Byte] = Array()): SignedTxEntry = {
    val sigs = tx.ins.map { in =>
      in.sig match {
        case PrivateKeySig => PrivateKeySig.createUnlockingSignature(identity.sign(tx.txId))
        case NullDecumbrance => Seq()
        case SingleIdentityDec => SingleIdentityDec.createUnlockingSignature(tx.txId, identity.tag, identity.sign)
        case SaleSecretDec => SaleSecretDec.createUnlockingSignature(tx.txId, identity.tag, identity.sign, secret)
        case ReturnSecretDec => ReturnSecretDec.createUnlockingSignature(tx.txId, identity.tag, identity.sign)
      }
    }
    SignedTxEntry(tx.toBytes, sigs)
  }

  def createTx(amountToSpend: Int): Tx = {
    val unSpent = findUnSpent(currentBlockHeight())

    def fund(acc: Seq[TxInput], outs: Seq[UnSpent], fundedTo: Int, target: Int): (Seq[TxInput], Seq[TxOutput]) = {
      if(fundedTo == target) (acc, Seq())
      else {
        outs.headOption match {
          case None => throw new IllegalArgumentException("Not enough credit")
          case Some(unspent) =>
            if(target - fundedTo >= unspent.out.amount) {
              val txIn = toInput(unspent)
              fund(acc :+ txIn, outs.tail, fundedTo + unspent.out.amount, target)
            } else {
              val txIn = toInput(unspent)
              val change = unspent.out.amount - (target - fundedTo)
              (acc :+ txIn, Seq(TxOutput(change, encumberToIdentity())))
            }
        }
      }
    }

    val (newIns, change) = fund(Seq(), unSpent, 0, amountToSpend)
    StandardTx(newIns, change)

  }


  private[wallet] def toInput(unSpent : UnSpent): TxInput = {
    TxInput(unSpent.txIndex, unSpent.out.amount, createDecumbrance(unSpent.out.encumbrance))
  }

  private[wallet] def createDecumbrance(enc:Encumbrance): Decumbrance = {
    enc match {
      case SinglePrivateKey(pKey, minBlockHeight) => PrivateKeySig
      case SaleOrReturnSecretEnc(returnIdentity,
                        claimant,
                        hashOfSecret,
                        returnBlockHeight) => {
        if(returnIdentity == identity.id) ReturnSecretDec
        else if(claimant == identity.id) SaleSecretDec
        else throw new IllegalArgumentException("This encumbrance is nothing to do with our identity.")
      }

      case SingleIdentityEnc(id, blockHeight) => SingleIdentityDec

      case NullEncumbrance => NullDecumbrance

    }
  }

  def encumberToIdentity(atBlockHeight: Long = 0,
                                         someIdentity: String = identity.id): Encumbrance =
    SingleIdentityEnc(someIdentity, atBlockHeight)


  private[wallet] def unSpentOpt(lodgement: Lodgement, atBlockHeight: Long): Option[UnSpent] = {

    //log.info(s"Is ${lodgement.txIndex.txId.toBase64Str} unspent?")
    balanceLedger.entry(lodgement.txIndex) match {
      case Some(txOut) => txOut.encumbrance match {
        case SinglePrivateKey(pKey, minBlockHeight) =>
          //log.info(s"${lodgement.txIndex.txId.toBase64Str} is a SinglePrivateKey with minBlkH $minBlockHeight")
          //if(identityServiceQuery.identify(pKey).isDefined) log.info("Identitified ")
          if(nodeControlsPublicKey(pKey)) {
            Option(UnSpent(lodgement.txIndex, txOut))
          } else {
            identityServiceQuery.identify(pKey).flatMap { acc =>
              if (acc.identity == identity.id && minBlockHeight <= atBlockHeight) Option(UnSpent(lodgement.txIndex, txOut))
              else None
            }
          }


        case SingleIdentityEnc(id, blockHeight) =>
          if (id == identity.id && blockHeight <= atBlockHeight) Option(UnSpent(lodgement.txIndex, txOut))
          else None

        case SaleOrReturnSecretEnc(returnIdentity,
        claimant,
        hashOfSecret,
        returnBlockHeight) if returnIdentity == identity.id =>
          if(returnBlockHeight <= atBlockHeight) Option(UnSpent(lodgement.txIndex, txOut))
          else None

        case SaleOrReturnSecretEnc(returnIdentity,
        claimant,
        hashOfSecret,
        returnBlockHeight) if returnIdentity != identity.id =>
          throw new Error(s"Why is there a Sale Enc with $returnIdentity? for is ${identity.id}")

        case NullEncumbrance => Option(UnSpent(lodgement.txIndex, txOut))
      }

      case None =>
        if (lodgement.inBlock == atBlockHeight || lodgement.inBlock == atBlockHeight + 1) {
          // the lodgement is in the current block or next block, but as this block has not been closed
          // it will not be in the balanceledger.
          Option(UnSpent(lodgement.txIndex, lodgement.txOutput))
        } else None
    }
  }



  def balance(atBlockHeight: Long = currentBlockHeight()): Int = findUnSpent(atBlockHeight).foldLeft(0)((acc, e) => acc + e.out.amount)

  private[wallet] def findUnSpent(atBlockHeight: Long): Seq[UnSpent] =  {
    val allWalletEntries = walletPersistence.listUnSpent
//    log.info(s"There are ${allWalletEntries.size} unspent wallet entries. Printing last 10")
//    allWalletEntries.reverse.take(10).foreach { we =>
//      log.info(s"Blk ${we.inBlock}, Amnt ${we.txOutput.amount}, Type ${we.txOutput.encumbrance.getClass.toGenericString}")
//    }
    allWalletEntries.foldLeft(Seq[UnSpent]()) ((acc: Seq[UnSpent], lodgement: Lodgement) =>
      unSpentOpt(lodgement, atBlockHeight) match {
        case Some(u) => acc :+ u
        case None => acc
      }
    )

  }

}
