package cli

import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoClient, ErgoId, InputBox}
import mixer.Models.OutBox
import mixer.{BlockExplorer, Models}
import app.{AliceImpl, BobImpl, Configs, ErgoMix, TokenErgoMix, Util => EUtil}

import scala.jdk.CollectionConverters._

object ErgoMixCLIUtil {

  var optClient:Option[ErgoClient] = None
  var tokenErgoMix: Option[TokenErgoMix] = None

  implicit def stringToBigInteger(s:String) = BigInt(s).bigInteger

  val emptyArr = Array[String]()

  def usingClient[T](f: BlockchainContext => T): T= {
    optClient.fold(throw new Exception("Set client first")){client =>
      client.execute{ctx =>
        f(ctx)
      }
    }
  }

  def handleMixObject(ctx: BlockchainContext) = {
    if (tokenErgoMix.isEmpty) {
      tokenErgoMix = Some(new TokenErgoMix(ctx))
    }
  }

  def getUnspentBoxes(address:String) = {
    usingClient{implicit ctx =>
      val explorer = new BlockExplorer()
      explorer.getUnspentBoxes(address)
    }
  }

  def getConfirmationsForBoxId(boxId:String) = {
    usingClient{implicit ctx =>
      val explorer = new BlockExplorer()
      explorer.getConfirmationsForBoxId(boxId)
    }
  }

  def getHalfMixBoxes(poolAmount: Long = -1, considerPool: Boolean = false): List[InputBox] = {
    usingClient{implicit ctx =>
      handleMixObject(ctx)
      val explorer = new BlockExplorer()
      var txPool = ""
      if (considerPool) txPool = explorer.getPoolTransactionsStr
      val halfAddress = Address.create(tokenErgoMix.get.halfMixAddress.toString)
      val unspentBoxes = ctx.getUnspentBoxesFor(halfAddress)
      if (poolAmount != -1)
        unspentBoxes.asScala.filter(box => box.getValue == poolAmount &&
          (!considerPool || !txPool.contains(s""""${box.getId.toString}","txId""""))).toList
      else
        unspentBoxes.asScala.toList
    }
  }

  def getTokenEmissionBoxes(numToken: Int, considerPool: Boolean = false): List[InputBox] = {
    usingClient{implicit ctx =>
      val explorer = new BlockExplorer()
      var txPool = ""
      if (considerPool) txPool = explorer.getPoolTransactionsStr
      handleMixObject(ctx)
      val tokenAddress = Address.create(tokenErgoMix.get.tokenEmissionAddress.toString)
      ctx.getUnspentBoxesFor(tokenAddress).asScala.filter(box => {
        box.getTokens.size() > 0 && box.getTokens.get(0).getId == ErgoId.create(TokenErgoMix.tokenId) &&
          box.getTokens.get(0).getValue >= numToken && (!considerPool || !txPool.contains(s""""${box.getId.toString}","txId""""))
      }).toList
    }
  }

  def getFullMixBoxes(poolAmount: Long = -1): Seq[Models.OutBox] = {
    usingClient{implicit ctx =>
      val unspent = getUnspentBoxes(tokenErgoMix.get.fullMixAddress.toString)
      if (poolAmount != -1)
        unspent.filter(_.amount == poolAmount)
      else
        unspent
    }
  }

  def getUnspentBoxById(boxId:String) = {
    usingClient{implicit ctx =>
      ctx.getBoxesById(boxId).headOption.getOrElse(throw new Exception("No box found"))
    }
  }

  def getSpendingTxId(boxId:String) = {
    usingClient{implicit ctx =>
      val explorer = new BlockExplorer()
      explorer.getSpendingTxId(boxId)
    }
  }

  def isTxInPool(txId: String): Boolean = {
    usingClient { implicit ctx =>
      try {
        val explorer = new BlockExplorer()
        explorer.doesTxExistInPool(txId)
      } catch {
        case _: Throwable => false
      }
    }
  }

  def getFeeEmissionBoxes(considerPool: Boolean = false): Seq[OutBox] = {
    usingClient{implicit ctx =>
      val explorer = new BlockExplorer()
      var txPool = ""
      if (considerPool) txPool = explorer.getPoolTransactionsStr
      handleMixObject(ctx)
      getUnspentBoxes(tokenErgoMix.get.feeEmissionAddress.toString).filter(box => box.spendingTxId.isEmpty
        && box.amount >= 2 * TokenErgoMix.feeAmount && (!considerPool || !txPool.contains(s""""${box.id}","txId""""))) // not an input
    }
  }

  case class Arg(key:String, value:String)

  def parseArgsNoSecret(args:Array[String]) = {
    implicit val l: Seq[Arg] = args.sliding(2, 2).toList.collect {
      case Array(key, value) => Arg(key, value)
    }
    val url = try getArg("url") catch {case a:Throwable => defaultUrl}
    val mainNet: Boolean = (try getArg("mainNet") catch {case a:Throwable => "true"}).toBoolean
    Client.setClient(url, mainNet, None)
    l
  }

  def parseArgs(args:Array[String]): (Seq[Arg], BigInt) = {
    implicit val l: Seq[Arg] = args.sliding(2, 2).toList.collect {
      case Array(key, value) => Arg(key, value)
    }
    val url = try getArg("url") catch {case a:Throwable => defaultUrl}
    val mainNet: Boolean = (try getArg("mainNet") catch {case a:Throwable => "true"}).toBoolean
    val secret = BigInt(getArg("secret"), 10)
    Client.setClient(url, mainNet, None)
    (l, secret)
  }

  def getArgs(key:String)(implicit args:Seq[Arg]):Seq[String] = args.filter(_.key == "--"+key).map(_.value) match {
    case Nil => throw new Exception(s"Argument $key missing")
    case any => any
  }
  def getArg(key:String)(implicit args:Seq[Arg]):String = getArgs(key) match {
    case List(arg) => arg
    case _ => throw new Exception(s"Multiple $key arguments")
  }

  val defaultUrl = Configs.nodeUrl
  def getProver(secret:BigInt, isAlice:Boolean)(implicit ctx:BlockchainContext) = if (isAlice) new AliceImpl(secret.bigInteger) else new BobImpl(secret.bigInteger)
  def isAlice(implicit args:Seq[Arg]) = getArg("mode") match{
    case "alice" => true
    case "bob" => false
    case any => throw new Exception(s"Invalid mode $any")
  }
}
