package sss.asado.ledger

/**
  * Copyright Stepping Stone Software Ltd. 2016, all rights reserved. 
  * mcsherrylabs on 3/3/16.
  */
package object serialize {

  trait Serializer[T] {

    def toBytes(t: T): Array[Byte]
    def fromBytes(b: Array[Byte]): T

  }

  trait ToBytes[T] {
    def toBytes: Array[Byte]
  }

}
