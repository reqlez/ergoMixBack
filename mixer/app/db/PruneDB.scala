package db

import app.Configs
import cli.MixUtils
import cli.MixUtils
import db.Columns._
import db.ScalaDB._
import helpers.ErgoMixerUtils._
import helpers.Util
import mixer.BlockExplorer
import mixer.Models.GroupMixStatus._
import mixer.Models.MixWithdrawStatus.Withdrawn
import mixer.Models.{DistributeTx, MixCovertRequest, MixGroupRequest, MixRequest, Withdraw}
import org.ergoplatform.appkit.BlockchainContext
import play.api.Logger

class PruneDB(tables: Tables) {
  private val logger: Logger = Logger(this.getClass)

  import tables._

  /**
   * prunes group mixes and covert mixes
   */
  def processPrune(): Unit = {
    try {
      if (Configs.dbPrune) {
        MixUtils.usingClient { implicit ctx =>
          pruneGroupMixes()
          pruneCovertMixes()
        }
      }
    } catch {
      case a: Throwable =>
        logger.error(s"  prune DB: An error occurred. Stacktrace below")
        logger.error(getStackTraceStr(a))
    }
  }

  /**
   * Will prune group mixes if possible one by one
   */
  def pruneGroupMixes()(implicit ctx: BlockchainContext): Unit = {
    val explorer = new BlockExplorer()
    val currentTime = Util.now
    val requests = mixRequestsGroupTable.selectStar.where(mixStatusCol === Complete.value).as(arr => MixGroupRequest(arr))
    requests.foreach(req => {
      val mixes = mixRequestsTable.selectStar.where(mixGroupIdCol === req.id).as(MixRequest(_))
      var shouldPrune = mixes.forall(mix => mix.withdrawStatus == Withdrawn.value)
      mixes.foreach(mix => {
        val withdrawTime: Long = withdrawTable.select(createdTimeCol).where(mixIdCol === mix.id).firstAsT[Long].head
        shouldPrune &= (currentTime - withdrawTime) >= Configs.dbPruneAfter * 120L
      })
      if (shouldPrune) {
        mixes.foreach(mix => {
          val txId: String = withdrawTable.select(txIdCol).where(mixIdCol === mix.id).firstAsT[String].head
          val numConf = explorer.getTxNumConfirmations(txId)
          shouldPrune &= numConf >= Configs.dbPruneAfter
        })
      }
      if (shouldPrune) {
        logger.info(s"  will prune group mix ${req.id}")
        deleteGroupMix(req.id)
      }
    })
  }

  /**
   * will prune covert mix boxes one by one if possible
   */
  def pruneCovertMixes()(implicit ctx: BlockchainContext): Unit = {
    val explorer = new BlockExplorer()
    val currentTime = Util.now
    val requests = mixCovertTable.selectStar.as(arr => MixCovertRequest(arr))
    requests.foreach(req => {
      val mixes = mixRequestsTable.selectStar.where(mixGroupIdCol === req.id, mixWithdrawStatusCol === Withdrawn.value).as(MixRequest(_))
      mixes.foreach(mix => {
        val withdrawTime: Long = withdrawTable.select(createdTimeCol).where(mixIdCol === mix.id).firstAsT[Long].head
        if ((currentTime - withdrawTime) >= Configs.dbPruneAfter * 120L) {
          val txId: String = withdrawTable.select(txIdCol).where(mixIdCol === mix.id).firstAsT[String].head
          val numConf = explorer.getTxNumConfirmations(txId)
          if (numConf >= Configs.dbPruneAfter) {
            logger.info(s"  will prune covert mix box ${mix.id}")
            deleteMixBox(mix)
          }
        }
      })
      // pruning distributed transactions that are mined and have been confirmed at least dbPruneAfter
      val distributedTxs = distributeTxsTable.selectStar.where(mixGroupIdCol === req.id, chainOrderCol === 0,
        createdTimeCol <= currentTime - Configs.dbPruneAfter * 120L).as(DistributeTx(_))
      distributedTxs.foreach(tx => {
        val numConf = explorer.getTxNumConfirmations(tx.txId)
        if (numConf >= Configs.dbPruneAfter) {
          logger.info(s"  will prune distributed tx of covert ${req.id}, txId: $txIdCol, confNum: $numConf")
          distributeTxsTable.deleteWhere(txIdCol === tx.txId)
        }
      })
    })
  }
}
