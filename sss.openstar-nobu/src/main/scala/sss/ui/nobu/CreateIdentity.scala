package sss.ui.nobu


import akka.actor.{ActorRef, ActorSystem}
import akka.actor.Actor.Receive
import com.typesafe.config.Config
import com.vaadin.server.VaadinSession
import com.vaadin.ui.{Notification, UI}
import sss.ancillary.Logging
import sss.openstar.{Send, UniqueNodeIdentifier}
import sss.openstar.chains.Chains.GlobalChainIdMask
import sss.openstar.identityledger.IdentityService
import sss.openstar.message.MessageDownloadActor
import sss.openstar.message.MessageDownloadActor.CheckForMessages
import sss.openstar.state.HomeDomain
import sss.db.Db
import sss.openstar.account.{NodeIdentity, NodeIdentityManager}
import sss.ui.nobu.CreateIdentity.{ClaimIdentity, Fund, Funded}
import us.monoid.web.Resty

import scala.util.{Failure, Success, Try}
import sss.openstar.network.MessageEventBus
import sss.openstar.util.ByteArrayEncodedStrOps.ByteArrayToBase64UrlStr
import sss.openstar.wallet.UtxoTracker.NewWallet
import sss.openstar.wallet.Wallet
import sss.ui.Servlet
import sss.ui.nobu.BlockingWorkers.BlockingTask
import sss.ui.nobu.UIActor.TrackSessionRef


object CreateIdentity {

  case class Fund(nodeIdentity: NodeIdentity)(implicit val ui: UI)
  case class Funded(uiId: Option[String], nodeIdentity: NodeIdentity, amount: Long)
  case class ClaimIdentity(claim: String, claimTag: String, phrase: String)(implicit val ui: UI)

}

class CreateIdentity(userDir: UserDirectory,
                     buildWallet: NodeIdentity => Wallet)(
                     implicit actorSystem:ActorSystem,
                     currentBlockHeight: () => Long,
                     conf: Config,
                     send: Send,
                     chainId: GlobalChainIdMask,
                     nodeIdentityManager: NodeIdentityManager,
                     homeDomain: HomeDomain,
                     identityService: IdentityService,
                     messageEventBus: MessageEventBus,
                     db:Db) extends Logging with BlockingWorkerUIHelper {


  def createIdentity: Receive = {

    case c@ClaimIdentity(claimStr: String, claimTag: String, phrase: String) =>
      import c.ui
      claim(claimStr, claimTag, phrase)

    case f@Fund(nodeIdentity: NodeIdentity) =>
      import f.ui
      fund(nodeIdentity)
  }

  def fund(nodeIdentity: NodeIdentity)(implicit ui: UI) = {
    //localhost:8070/claim/debit?to=cavan1&amount=100

    val amount = 100
    Try(new Resty().text(s"${homeDomain.http}/claim/debit?to=${nodeIdentity.id}&amount=$amount")) match {
      case Success(s) =>
        val userWallet = buildWallet(nodeIdentity)
        Option(ui.getSession()) match {
          case Some(sess) =>

            val mainView: NobuMainLayout = setUpMainLayout(nodeIdentity, userWallet, sess)
            push( ui.getNavigator.navigateTo(mainView.name))

          case None =>
            log.error("Couldn't get ui session?")
        }

      case Failure(e) =>
        log.warn("Failed to fund {}", nodeIdentity.id)
        log.warn("Failed to fund exception {}", e)
        show(s"Failed to fund ${nodeIdentity.id}", Notification.Type.WARNING_MESSAGE)
        navigateTo(UnlockClaimView.name)
    }

  }

  def setUpMainLayout(nodeIdentity: NodeIdentity, userWallet: Wallet, sess: VaadinSession)(implicit ui: UI): NobuMainLayout = {
    val msgDownRef = MessageDownloadActor(ValidateBounty.validateBounty, nodeIdentity, userWallet, homeDomain)
    msgDownRef ! CheckForMessages

    messageEventBus publish TrackSessionRef(sessId, msgDownRef)
    UserSession.note(nodeIdentity, userWallet)
    sess.setAttribute(NobuUI.SessionAttr, nodeIdentity.id)
    val mainView = new NobuMainLayout(userDir, userWallet, nodeIdentity)
    ui.getNavigator.addView(mainView.name, mainView)
    mainView
  }


  def claim(
            claim: String,
            claimTag: String,
            phrase: String)(implicit ui: UI) = {

    if (!IdentityService.validateIdentity(claim)) {
      show(s"Identity $claim is invalid, simple lowercase characters only!",Notification.Type.WARNING_MESSAGE)
    } else if (identityService.accounts(claim).nonEmpty) {
      show(s"Identity $claim already claimed!",Notification.Type.WARNING_MESSAGE)
      navigateTo(UnlockClaimView.name)

    } else Try(nodeIdentityManager(claim, claimTag, phrase)) match {

      case Failure(e) =>
        show(s"Identity claim $claim failed!",Notification.Type.WARNING_MESSAGE)
        navigateTo(UnlockClaimView.name)

      case Success(nId) =>
        val publicKey = nId.publicKey.toBase64Str

        Try(new Resty().text(
          s"${homeDomain.http}/console/command?1=claim&2=${claim}&3=${publicKey}")) match {

          case Success(tr) if (tr.toString.contains("ok")) =>
            messageEventBus publish NewWallet(buildWallet(nId).walletTracker)
            messageEventBus publish BlockingTask(Fund(nId))

          case Success(s) =>
            log.info(s.toString)
            show(s"There was a problem with your claim $claim, try again.", Notification.Type.WARNING_MESSAGE)
            navigateTo(UnlockClaimView.name)

          case Failure(e) =>
            log.info(e.toString)
            show(s"There was a problem with your claim $claim, try again.", Notification.Type.WARNING_MESSAGE)
            navigateTo(UnlockClaimView.name)

        }
    }
  }

}