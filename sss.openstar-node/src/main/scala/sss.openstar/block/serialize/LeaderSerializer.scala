package sss.openstar.block.serialize

import java.nio.charset.StandardCharsets

import sss.openstar.block.Leader
import sss.openstar.util.Serialize.Serializer

/**
  * Copyright Stepping Stone Software Ltd. 2016, all rights reserved. 
  * mcsherrylabs on 3/3/16.
  */
object LeaderSerializer extends Serializer[Leader]{

  override def toBytes(l: Leader): Array[Byte] = l.nodeId.getBytes(StandardCharsets.UTF_8)
  override def fromBytes(b: Array[Byte]): Leader = Leader(new String(b, StandardCharsets.UTF_8))

}
