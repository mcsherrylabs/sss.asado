package sss.analysis

import org.joda.time.LocalDateTime
import sss.analysis.TransactionHistory.ExpandedTx
import sss.openstar.util.ByteArrayEncodedStrOps._
import sss.db._


/**
  * Created by alan on 12/15/16.
  */

trait TransactionHistoryQuery {

  def filter(identity: String): Seq[ExpandedTx]
  def list: Seq[ExpandedTx]
}

class TransactionHistoryPersistence(implicit db:Db) extends TransactionHistoryQuery {

  private val idCol = "id"
  private val amountCol = "amount"
  private val whoCol = "who"
  private val txIdCol = "txid"
  private val inCol = "in_flag"
  private val blockTimeCol = "block_time"
  private val blockHeightCol = "block_height"
  private val tableName = "tx_history"


  import sss.analysis.TransactionHistory._

  private val dropTableSql = s"DROP TABLE IF EXISTS ${tableName}"

  private val createTableSql =
    s"""CREATE TABLE IF NOT EXISTS ${tableName}
       |($idCol BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 1),
       |$txIdCol VARCHAR(64),
       |$whoCol VARCHAR(120),
       |$amountCol BIGINT,
       |$inCol BOOLEAN,
       |$blockTimeCol BIGINT,
       |$blockHeightCol BIGINT);
       |""".stripMargin

  db.executeSql(createTableSql)

  private val txTable = db.table(tableName)

  private [analysis] def recreateTable = db.executeSqls(Seq(dropTableSql, createTableSql))

  def delete(blockHeight: Long): Unit = {
    txTable.delete(where(s"$blockHeightCol = ?") using blockHeight)
  }


  def write(expanded: ExpandedTx): Unit = {
    db.tx {

        expanded.ins.foreach { expandedElement =>
          txTable.insert(Map(txIdCol -> expandedElement.txId.toBase64Str,
            whoCol -> expandedElement.identity,
            amountCol -> expandedElement.amount,
            inCol -> true,
            blockTimeCol -> expanded.when.toDate.getTime,
            blockHeightCol -> expanded.blockHeight))

        }
        expanded.outs.foreach { expandedElement =>
          txTable.insert(Map(txIdCol -> expandedElement.txId.toBase64Str,
            whoCol -> expandedElement.identity,
            amountCol -> expandedElement.amount,
            inCol -> false,
            blockTimeCol -> expanded.when.toDate.getTime,
            blockHeightCol -> expanded.blockHeight))
        }
      }
  }

  private def toExpandedElement(row: Row): (ExpandedTxElement, Boolean, LocalDateTime, Long) = {
    (ExpandedTxElement(row[String](txIdCol).toByteArray,
      row[String](whoCol),
      row[Long](amountCol)),
      row[Boolean](inCol),
      new LocalDateTime(row[Long](blockTimeCol)),
      row[Long](blockHeightCol))
  }

  private def toExpandedTx(txElements: Seq[(ExpandedTxElement, Boolean, LocalDateTime, Long)]): ExpandedTx = {
    val inOuts = txElements.groupBy(_._2)
    ExpandedTx(inOuts(true).map(_._1), inOuts(false).map(_._1), inOuts(true).head._3, inOuts(true).head._4)
  }

  private def toExpandedTxs(rows: Rows): Seq[ExpandedTx] = {
    rows.map(toExpandedElement).groupBy(_._1.txIdBase64Str).values.map(toExpandedTx).toSeq
  }

  override def filter(identity: String): Seq[ExpandedTx] = {
    val rowsInvolvingIdentity  = txTable.filter(
      where(whoCol -> identity)
        .orderBy(OrderDesc(blockTimeCol)))

    val allTxs = rowsInvolvingIdentity.map(_.apply[String](txIdCol))
    if(allTxs.nonEmpty) {
      val replaceableParams = (0 until allTxs.size).map(_ => "?").mkString(",")
      val rows = txTable.filter(
        where(s"$txIdCol IN ($replaceableParams)", allTxs: _*)
          orderBy OrderDesc(blockTimeCol))

      toExpandedTxs(rows)
    } else Seq()
  }

  override def list: Seq[ExpandedTx] = {
    val rows = txTable.filter(
      where(s"$idCol > ?", 0)
        orderBy OrderDesc(blockTimeCol))

    toExpandedTxs(rows)
  }
}
