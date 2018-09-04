package sss.asado.message.serialize

import org.joda.time.LocalDateTime
import sss.asado.Identity
import sss.asado.message._
import sss.asado.util.Serialize._

/**
  * Created by alan on 6/8/16.
  */
object MsgSerializer extends Serializer[Message] {

  def toBytes(o: Message): Array[Byte] =
    (StringSerializer(o.from.value) ++
      ByteArraySerializer(o.msgPayload.toBytes) ++
      ByteArraySerializer(o.tx) ++
      LongSerializer(o.index) ++
      LongSerializer(o.createdAt.toDate.getTime)).toBytes

  def fromBytes(bs: Array[Byte]): Message = {
    Message.tupled(
      bs.extract(StringDeSerialize(Identity),
        ByteArrayDeSerialize(_.toMessagePayload),
        ByteArrayDeSerialize,
        LongDeSerialize,
        LongDeSerialize(l => new LocalDateTime(l))
      )
    )

  }

}
