package sss.asado.message.serialize

import sss.asado.message.MessagePayload
import sss.asado.util.Serialize._

/**
  * Created by alan on 6/8/16.
  */
object MsgPayloadSerializer extends Serializer[MessagePayload] {

  def toBytes(o: MessagePayload): Array[Byte] =
    (ByteSerializer(o.payloadType) ++
      ByteArraySerializer(o.payload)).toBytes

  def fromBytes(bs: Array[Byte]): MessagePayload = {

    MessagePayload.tupled(
      bs.extract(
        ByteDeSerialize,
        ByteArrayDeSerialize
      )
    )
  }

}
