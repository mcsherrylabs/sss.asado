package sss.ui.nobu



import java.io.File

import akka.actor.{ActorRef, Props}
import com.vaadin.navigator.View
import com.vaadin.navigator.ViewChangeListener.ViewChangeEvent
import com.vaadin.ui.Button.ClickEvent
import com.vaadin.ui.{Button, Notification, UI}
import sss.ancillary.Logging
import sss.asado.account.NodeIdentity
import sss.asado.balanceledger.{TxIndex, TxOutput}
import sss.asado.contract.SingleIdentityEnc
import sss.asado.ledger._
import sss.asado.nodebuilder.{ClientNode, WalletBuilder}
import sss.asado.state.HomeDomain
import sss.asado.util.ByteArrayEncodedStrOps._
import sss.asado.wallet.{Wallet, WalletPersistence}
import sss.ui.design.CenteredAccordianDesign
import sss.ui.reactor.{ComponentEvent, Register, UIReactor}

import scala.util.{Failure, Success, Try}

/**
  * Created by alan on 6/10/16.
  */

private case class IdTagValue(str: String) {
  private val tuple = str.split(", ")
  val identity: String = tuple(0)
  val tag: String = tuple(1)
}

class UnlockClaimView(
                      uiReactor: UIReactor,
                      keyFolder: String,
                      clientNode: ClientNode,
                      clientEventActor: ActorRef
                      ) extends CenteredAccordianDesign with View with Logging {

  private val claimBtnVal = claimBtn
  private val unlockBtnVal = unlockBtn


  unlockTagText.setVisible(false)
  claimTagText.setVisible(false)
  //NB MUST BE DEFAULT TAG
  claimTagText.setValue("defaultTag")

  identityCombo.setNewItemsAllowed(false)
  identityCombo.setNullSelectionAllowed(true)
  unlockInfoTextArea.setRows(8)
  claimInfoTextArea.setRows(8)

  uiReactor.actorOf(Props(UnlockClaimViewActor), claimBtnVal, unlockBtnVal)

  private def showClaim = {
    rhsClaim.setVisible(true)
    rhsUnlock.setVisible(false)
  }

  private def showUnlock(keyNames: Array[String]) = {

    rhsClaim.setVisible(false)
    rhsUnlock.setVisible(true)


    val asTuples = keyNames.map { key =>
      val kv = key.split("\\.")
      kv(0) -> kv(1)
    }

    identityCombo.setData(asTuples)
    identityCombo.removeAllItems()
    if(!asTuples.isEmpty) {
      val items = asTuples.map(kv => s"${kv._1}, ${kv._2}").toSeq
      identityCombo.addItems(items: _*)
      identityCombo.select(identityCombo.getItemIds.iterator().next)
    }

  }

//  claimMnuBtn.addClickListener(new Button.ClickListener{
//    override def buttonClick(event: ClickEvent): Unit = showClaim
//  })

  unlockMnuBtn.addClickListener(new Button.ClickListener{
    override def buttonClick(event: ClickEvent): Unit = showUnlock(getKeyNames(keyFolder))
  })

  claimBtnVal.addClickListener(uiReactor)
  unlockBtnVal.addClickListener(uiReactor)

  private def getKeyNames(keyFolder: String): Array[String] = {
    val folder = new File(keyFolder)
    folder.listFiles().filter(_.isFile).map(_.getName)
  }

  override def enter(viewChangeEvent: ViewChangeEvent): Unit = {
    val keyNames = getKeyNames(keyFolder)
    if(keyNames.isEmpty) showClaim
    else showUnlock(keyNames)
  }

  object UnlockClaimViewActor extends sss.ui.reactor.UIEventActor {

    override def react(reactor: ActorRef, broadcaster: ActorRef, ui: UI) = {

      case ComponentEvent(`unlockBtnVal`, _) =>
        Option(identityCombo.getValue) map { idTag =>
          val claimAndTag = IdTagValue(idTag.toString)
          val tag = claimAndTag.tag
          val identity = claimAndTag.identity
          val phrase = unLockPhrase.getValue
          Try(NodeIdentity(identity, tag, phrase)) match {
            case Failure(e) =>
              log.error("Failed to unlock {} {}", identity, e)
              push(Notification.show(s"${e.getMessage}"))

            case Success(nId) => gotoMainView(nId)
          }
        }
    }

    def createWallet(nId: NodeIdentity) : Wallet = {
      new Wallet(nId,
        clientNode.balanceLedger,
        clientNode.identityService,
        new WalletPersistence(nId.id, clientNode.db),
        clientNode.currentBlockHeight _)
    }

    def gotoMainView(nId: NodeIdentity): Unit = {
      val userWallet = createWallet(nId)
      getSession().setAttribute(UnlockClaimView.identityAttr, nId.id)
      val mainView = new NobuMainLayout(uiReactor, userWallet, nId, clientNode, clientEventActor)
      push {
        getUI().getNavigator.addView(mainView.name, mainView)
        getUI().getNavigator.navigateTo(mainView.name)
      }
    }
  }
}


object UnlockClaimView {
  val name = "unlockClaimView"
  val identityAttr = "nodeIdentity"
}