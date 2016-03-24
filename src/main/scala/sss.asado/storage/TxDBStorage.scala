package sss.asado.storage

import ledger._
import sss.ancillary.Logging
import sss.db.{Db, OrderAsc, Where}

import scala.util.{Failure, Success, Try}


object TxDBStorage extends Logging {
  private val blockTableNamePrefix = "block_"

  def tableName(height: Long) = s"$blockTableNamePrefix$height"

  def confirm(height: Long, id: Long)(implicit db:Db): Unit = {
    Try {
      val blcokTable = db.table(tableName(height))
      blcokTable.update(Map("confirm" -> "confirm + 1", "id" -> id))
    } match {
      case Failure(e) => log.error(s"FAILED to add confirmation!", e)
      case Success(r) => log.info(s"Tx confirmed. $r")
    }
  }
  def apply(height: Long)(implicit db:Db): TxDBStorage = new TxDBStorage(tableName(height))
}

class TxDBStorage(tableName: String)(implicit db:Db) extends Storage[TxId, SignedTx] {

  db.executeSql (s"CREATE TABLE IF NOT EXISTS $tableName (id BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 1), txid BLOB,  entry BLOB, confirms INT DEFAULT 0)")

  private val blockTxTable = db.table(tableName)

  private[storage] override def entries: Set[SignedTx] = {
    blockTxTable.map ({ row =>
      row[Array[Byte]]("entry").toSignedTx
    }, OrderAsc("id")).toSet
  }

  override def inTransaction[T](f: => T): T = blockTxTable.inTransaction[T](f)

  override def get(id: TxId): Option[SignedTx] = blockTxTable.find(Where("txid = ?", id)).map(r => r[Array[Byte]]("entry").toSignedTx)

  override def delete(id: TxId): Boolean = blockTxTable.delete(Where("txid = ?", id)) == 1

  override def write(k: TxId, le: SignedTx): Long = {
    val bs = le.toBytes
    blockTxTable.insert(Map("txid" -> k, "entry" -> bs))[Long]("id")
  }


}
