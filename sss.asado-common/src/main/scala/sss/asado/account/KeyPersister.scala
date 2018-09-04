package sss.asado.account


import sss.ancillary.{Logging, Memento}
import sss.asado.{Identity, IdentityTag}
import sss.asado.crypto.ECBEncryption._
import sss.asado.util.ByteArrayEncodedStrOps._

/**
  * Persists the key pair, tag and identity...
  *
  */
private object KeyPersister extends Logging {


  def deleteKey(identity: String, tag: String) = memento(identity, tag).clear
  def keyExists(identity: String, tag: String): Boolean = memento(identity, tag).read.isDefined
  def apply(identity: Identity,
            tag: IdentityTag,
            phrase: String,
            keyGenerator: () => (Array[Byte], Array[Byte])): (Array[Byte], Array[Byte]) = {

    get(identity, tag, phrase).getOrElse {
      val pkPair = keyGenerator()
      val privKStr: String = pkPair._1.toBase64Str
      val pubKStr: String = pkPair._2.toBase64Str
      val encrypted = encrypt(phrase, privKStr)
      val hashedPhrase = PasswordStorage.createHash(phrase)
      val created = s"$pubKStr:::$hashedPhrase:::$encrypted"
      log.debug(s"CREATED - ${created}")
      memento(identity.value, tag.value).write(created)
      apply(identity, tag, phrase, keyGenerator)
    }

  }

  def get(identity: Identity,
          tag: IdentityTag,
          phrase: String
         ): Option[(Array[Byte], Array[Byte])] = {

    require(phrase.length > 7, "Password must be 8 characters or more." )
    require(tag.value.length > 0, "Tag cannot be an empty string" )

    def toKey(str: String): (Array[Byte], Array[Byte]) = {
        val aryOfSecuredKeys = str.split(":::")
        require(aryOfSecuredKeys.length == 3,
          s"File $identity.$tag is corrupt. Restore from backup or set up a new key.")
        val pubKStr = aryOfSecuredKeys(0)
        val hashedPhrase = aryOfSecuredKeys(1)
        val encryptedPrivateKey = aryOfSecuredKeys(2)
        log.debug(s"""OUT -  ${aryOfSecuredKeys.mkString(":::")}""")
        require(PasswordStorage.verifyPassword(phrase, hashedPhrase), "Incorrect password")

        val decryptedKey = decrypt(phrase, encryptedPrivateKey )
        (decryptedKey.toByteArray, pubKStr.toByteArray)
    }

    for {
      contents <- memento(identity.value, tag.value).read
    } yield toKey(contents)

  }

  private def memento(identity: String, tag: String): Memento = Memento(s"$identity.$tag")

}

