package sss.openstar.peers

import java.net.{InetAddress, InetSocketAddress}

import akka.actor.{Actor, ActorLogging}
import sss.ancillary.Logging
import sss.db._
import sss.openstar.chains.Chains.GlobalChainIdMask
import sss.openstar.network.MessageEventBus.IncomingMessage
import sss.openstar.network.{MessageEventBus, NodeId}
import sss.openstar.{MessageKeys, Send, UniqueNodeIdentifier}

import scala.util.{Failure, Success, Try}



trait Discovery {
  def find(notIncluding: Set[UniqueNodeIdentifier], numConns: Int, caps: GlobalChainIdMask): Seq[NodeId]
  def lookup(names: Set[UniqueNodeIdentifier]): Seq[NodeId]
  def insert(nodeId:NodeId, supportedChains: GlobalChainIdMask): Try[Long]
  def discover(seedConns: Set[UniqueNodeIdentifier])
}

class DiscoveryImpl(implicit db: Db) extends Discovery {

  private val discoveryTableName = "discovery"
  private val nIdCol = "nid_col"
  private val addrCol = "addr_col"
  private val portCol = "port_col"
  private val capCol = "cap_col"

  private val createTableSql =
    s"""CREATE TABLE IF NOT EXISTS $discoveryTableName
       |(id BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 1),
       |$nIdCol VARCHAR(100),
       |$addrCol BINARY(16),
       |$portCol INT NOT NULL,
       |$capCol BINARY(1) NOT NULL,
       |PRIMARY KEY(id), UNIQUE($addrCol));
       |""".stripMargin

  private val indx = s"CREATE INDEX IF NOT EXISTS ${discoveryTableName}_indx ON $discoveryTableName ($addrCol, $capCol);"

  lazy val discoveryTable = {
    db.executeSqls(Seq(createTableSql, indx))
    db.table(discoveryTableName)
  }

  override def find(notIncluding: Set[UniqueNodeIdentifier], numConns: Int, caps: GlobalChainIdMask): Seq[NodeId] = {

    val whereClause = if(notIncluding.nonEmpty) {
      val params = Seq.fill(notIncluding.size)("?") mkString (",")
      val sql = s" $nIdCol NOT IN ($params) AND $capCol = ?"
      where(sql, (notIncluding.toSeq :+ caps):_*)
    } else {
      where(s"$capCol = ?", caps)
    }

    discoveryTable.map ({ r =>
      val inet = r[Array[Byte]](addrCol).toInetAddress
      val sock = new InetSocketAddress(inet, r[Int](portCol))
      NodeId(r[String](nIdCol), sock )
    }, whereClause limit numConns)

  }

  override def lookup(names: Set[UniqueNodeIdentifier]): Seq[NodeId] = {

    val params = Seq.fill(names.size)("?") mkString (",")
    discoveryTable.map ({ r =>
      val inet = r[Array[Byte]](addrCol).toInetAddress
      val sock = new InetSocketAddress(inet, r[Int](portCol))
      NodeId(r[String](nIdCol), sock )
    }, where(s" $nIdCol IN ($params)", names.toSeq:_*))

  }

  override def insert(nodeId:NodeId, supportedChains: GlobalChainIdMask): Try[Long] = {

    Try(discoveryTable.insert(
      Map(
        nIdCol -> nodeId.id,
        addrCol -> nodeId.inetSocketAddress.getAddress.toBytes,
        portCol -> nodeId.inetSocketAddress.getPort,
        capCol -> supportedChains)
    )) map (_.id)
  }

  override def discover(seedConns: Set[UniqueNodeIdentifier]): Unit = ???
}

class DiscoveryActor(seedNodes: Set[UniqueNodeIdentifier],
                     discoveryImpl: DiscoveryImpl)
                    (implicit messageEventBus: MessageEventBus,
                     send: Send) extends Actor with ActorLogging {

  messageEventBus subscribe MessageKeys.PeerPageResponse
  messageEventBus subscribe MessageKeys.SeqPeerPageResponse

    override def receive: Receive = {
      case IncomingMessage(_, MessageKeys.SeqPeerPageResponse, fromNode, seq: Seq[PeerPageResponse]) =>
        seq foreach (self ! _)

      case IncomingMessage(_, MessageKeys.PeerPageResponse, fromNode, peerResp: PeerPageResponse) =>
        discoveryImpl
          .insert(
            NodeId(peerResp.nodeId, peerResp.sockAddr),
            peerResp.capabilities.supportedChains
          ) recover {
          case e =>
            log.debug("Couldn't process {} from {}, possible dup", peerResp, fromNode)
            log.debug(e.toString)

        }

    }
}
