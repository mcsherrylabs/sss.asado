package sss.openstar.console

import java.net.InetSocketAddress

import sss.openstar.{MessageKeys, UniqueNodeIdentifier}
import sss.openstar.balanceledger.{TxIndex, TxOutput}
import sss.openstar.block.Block
import sss.openstar.chains.TxWriterActor.InternalCommit
import sss.openstar.contract.SingleIdentityEnc
import sss.openstar.eventbus.EventPublish
import sss.openstar.identityledger.{Claim, IdentityService}
import sss.openstar.network.{NetworkRef, NodeId}
import sss.openstar.util.ByteArrayEncodedStrOps._
import sss.openstar.wallet.WalletPersistence.Lodgement
import sss.openstar.wallet.WalletPersistence
import sss.db._
import sss.openstar.account.NodeIdentity
import sss.openstar.util.FutureOps._
import sss.openstar.ledger._
import sss.openstar.quorumledger.{AddNodeId, RemoveNodeId}
import sss.openstar.tools.SendTxSupport.SendTx
import sss.ui.console.util.{Cmd, ConsoleServlet => BaseConsoleServlet}


/**
  * Copyright Stepping Stone Software Ltd. 2016, all rights reserved.
  * mcsherrylabs on 3/9/16.
  */
class ConsoleServlet(
                      ncRef: NetworkRef,
                      publisher: EventPublish,
                      nodeIdentity: NodeIdentity,
                      quorumQuery: () => Set[UniqueNodeIdentifier],
                      identityService: IdentityService,
                      sendTx: SendTx
                      )
                    (implicit val db: Db)
    extends BaseConsoleServlet {

  lazy val utxosTable = db.table("utxo")
  lazy val blocks = db.table("blockchain")

  val cmds: Map[String, Cmd] = Map(
    "peers" -> new Cmd {
      override def apply(params: Seq[String]): Seq[String] =
        Seq("Not implememvted")
    },
    /*"signatures" -> new Cmd {
      override def help: String = s"signatures <blockheight> <num_sigs>"
      override def apply(params: Seq[String]): Seq[String] = {
        val sigs = BlockSignatures(params.head.toLong)
          .signatures(params(1).toInt)
          .map(_.toString)
        Seq(s"Num sigs is ${sigs.size}") ++ sigs
      }
    },*/
    "listunspent" -> new Cmd {
      override def help = s"listunspent <identity> "
      override def apply(params: Seq[String]): Seq[String] = {
        val identity = params(0)
        val walletPersistence = new WalletPersistence(identity, db)
        walletPersistence.listUnSpent.map { us =>
          s"${us.txIndex}, block ${us.inBlock}"
        }
      }
    },
    "addtowallet" -> new Cmd {
      override def help =
        s"addtowallet <identity> <txId> <index> <amount> <blockheight>"
      override def apply(params: Seq[String]): Seq[String] = {
        val identity = params(0)
        val walletPersistence = new WalletPersistence(identity, db)
        val txId = params(1).asTxId
        val index = params(2).toInt
        val amount = params(3).toInt
        val inBlock = params(4).toLong

        val txIndx = TxIndex(txId, index)
        val txOutput = TxOutput(amount, SingleIdentityEnc(identity, 0))
        walletPersistence.track(Lodgement(txIndx, txOutput, inBlock))
        Seq(s"use listunspent to see the change ")
      }
    },
    /*"balance" -> new Cmd {
      override def help =
        s"the balance of the node wallet at a given block height"
      override def apply(params: Seq[String]): Seq[String] = {
        Seq(s"Balance: ${wallet.balance(params.head.toLong)}")
      }
    },*/
    "block" -> new Cmd {
      override def help = s"block <chain id> <block height> <start index> <end index>"
      override def apply(params: Seq[String]): Seq[String] = {
        implicit val chainId = params(0).toByte
        Block(params(1).toLong).entries
          .map(_.toString)
          .slice(params(2).toInt, params(3).toInt) :+ "...End"
      }
    },
    "claim" -> new Cmd {
      override def help: String = s"Claim an identity with public key "
      override def apply(params: Seq[String]): Seq[String] = {
        val claim = params(0)
        val pKey = params(1).toByteArray
        val tx = Claim(claim, pKey)
        //val sig = nodeIdentity.sign(tx.txId)
        val ste = SignedTxEntry(tx.toBytes, Seq())
        val le = LedgerItem(MessageKeys.IdentityLedger, tx.txId, ste.toBytes)
        Seq(sendTx(le).await() match {
          case _: InternalCommit => "ok"
          case _ => ""
        })
      }
    },
    "connectpeer" -> new Cmd {
      override def help: String = s"nodeId ip port"
      override def apply(params: Seq[String]): Seq[String] = {
        val socketAddr = new InetSocketAddress(params(1), params(2).toInt)
        val n = NodeId(params(0), socketAddr)
        ncRef.connect(n)
        Seq(s"$n")
      }
    },
    "id" -> new Cmd {
      override def apply(params: Seq[String]): Seq[String] = {
        identityService.accounts(params.head).map(_.toString)
      }
    },
    "blockheader" -> new Cmd {
      override def apply(params: Seq[String]): Seq[String] = {
        blocks.filter(where("height" -> params.head.toLong)).map(_.toString)
      }
    },
    "utxo" -> new Cmd {
      override def apply(params: Seq[String]): Seq[String] = {
        val startPage = params.head.toLong
        val pageSize = params.tail.head.toInt
        val result = utxosTable.page(startPage, pageSize, Seq()).map(_.toString)
        if (result.isEmpty) Seq("No utxos found")
        else result
      }
    },
    "removequorum" -> new Cmd {
      override def apply(params: Seq[String]): Seq[String] = {
        val chainId = params.tail.head.toByte
        val tx = RemoveNodeId(params.head)
        val sig = nodeIdentity.sign(tx.txId)
        val sigs = Seq(nodeIdentity.idBytes, nodeIdentity.tagBytes, sig)
        val ste = SignedTxEntry(tx.toBytes, Seq(sigs))
        val le = LedgerItem(MessageKeys.QuorumLedger, tx.txId, ste.toBytes)
        Seq(sendTx(le).await().toString)
        //Seq("LedgerItem Message published")
      }
    },
    "addquorum" -> new Cmd {
      override def apply(params: Seq[String]): Seq[String] = {
        val chainId = params.tail.head.toByte
        val tx = AddNodeId(params.head)
        val sig = nodeIdentity.sign(tx.txId)
        val sigs = Seq(nodeIdentity.idBytes, nodeIdentity.tagBytes, sig)
        val ste = SignedTxEntry(tx.toBytes, Seq(sigs))
        val le = LedgerItem(MessageKeys.QuorumLedger, tx.txId, ste.toBytes)
        Seq(sendTx(le).await().toString)
        //Seq("LedgerItem Message published")
      }
    },
    "showquorum" -> new Cmd {
    override def apply(params: Seq[String]): Seq[String] = {
      quorumQuery().toSeq
    }
  }
  )

}
