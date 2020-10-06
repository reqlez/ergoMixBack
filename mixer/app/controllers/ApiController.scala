package controllers

import java.nio.file.Paths

import app.{Configs, Util => EUtil}
import cli.MixUtils
import db.Columns._
import db.ScalaDB._
import helpers.ErgoMixerUtils.getStackTraceStr
import helpers.{ErgoMixerUtils, Stats}
import info.BuildInfo
import io.circe.Json
import io.circe.syntax._
import javax.inject._
import mixer.Models.MixBoxList
import mixer.Models.MixWithdrawStatus.WithdrawRequested
import org.ergoplatform.appkit.RestApiErgoClient
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import services.ErgoMixingSystem
import ErgoMixerUtils.getStackTraceStr
import com.zaxxer.hikari.HikariDataSource
import org.apache.commons.lang3._
import play.api.db.Database
import helpers.TrayUtils.showNotification
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source

/**
 * A controller inside of Mixer controller.
 */
class ApiController @Inject()(val controllerComponents: ControllerComponents, db: Database)(implicit ec: ExecutionContext) extends BaseController {

  private lazy val ergoMixer = ErgoMixingSystem.ergoMixer
  private val logger: Logger = Logger(this.getClass)

  /**
   * A Get controller for redirect route /swagger
   *
   * @return route /swagger with query params {"url": "/swagger.conf"}
   */
  def redirectDocs: Action[AnyContent] = Action {
    Redirect(url = "/docs/index.html", queryStringParams = Map("url" -> Seq("/swagger.conf")))
  }

  /**
   * A Get controller for return doc-api from openapi.yaml and return OpenApi for swagger
   *
   * @return openapi.yaml with string format
   */
  def swagger: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(
      Source.fromResource("openapi.yaml").getLines.mkString("\n")
    ).as("application/json")
  }

  /**
   * A Post controller for generate wallet address in the amount of 'countAddress' for node 'nodeAddress' using
   * api '/wallet/deriveNextKey' from ergo node.
   */
  def generateAddress: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    val js = io.circe.parser.parse(request.body.asJson.get.toString()).getOrElse(Json.Null)
    val apiKey = js.hcursor.downField("apiKey").as[String].getOrElse(null)
    val nodeAddress = js.hcursor.downField("nodeAddress").as[String].getOrElse(null)
    val countAddress: Int = js.hcursor.downField("countAddress").as[Int].getOrElse(0)
    // Validate input
    if (nodeAddress == null || countAddress == 0 || apiKey == null) {
      BadRequest(
        s"""
           |{
           |  "success": false,
           |  "message": "nodeAddress, countAddress, apiKey is required."
           |}
           |""".stripMargin
      ).as("application/json")
    } else {
      var addresses: Array[String] = Array()
      try {
        for (_ <- 1 to countAddress) {
          val res = MixUtils.deriveNextAddress(nodeAddress, apiKey)
          val resJson: Json = io.circe.parser.parse(res).getOrElse(Json.Null)
          addresses :+= resJson.hcursor.downField("address").as[String].getOrElse(null)
        }
        // Return a list of address in the amount of countAddress
        Ok(
          s"""
             |${addresses.asJson}
             |""".stripMargin
        ).as("application/json")
      } catch {
        case ex: Throwable =>
          logger.error(s"error in controller ${getStackTraceStr(ex)}")
          BadRequest(
            s"""
               |{
               |  "success": false,
               |  "message": "${ex.getMessage}"
               |}
               |""".stripMargin
          ).as("application/json")
      }
    }
  }

  /**
   * A Get controller for calculate number of unSpent halfBox and number of spent halfBox in `periodTime`.
   * Note : Number of spent halfBox is approximate.
   * outPut: {
   * 2000000000: {
   * "spentHalf": 0,
   * "unspentHalf": 3
   * }
   * }
   */
  def rings: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(
      s"""
         |${Stats.ringStats.asJson}
         |""".stripMargin
    ).as("application/json")
  }

  /**
   * A post get endpoint to exit the app
   */
  def exit: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    showNotification("Shutdown", "Please wait, may take a few seconds for ErgoMixer to peacefully shutdown...")
    System.exit(0)
    Ok(
      s"""
         |{
         |  "success": true
         |}
         |""".stripMargin
    ).as("application/json")
  }

  /**
   * A GET endpoint to download the backup of database
   */
  def backup: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    try {
      if (SystemUtils.IS_OS_WINDOWS) db.shutdown()
      val res = ErgoMixerUtils.backup()
      Ok.sendFile(new java.io.File(res))

    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
    }
  }

  /**
   * A post endpoint to upload a backup and restore it
   */
  def restore = Action(parse.multipartFormData) { request =>
    try {
      val path = System.getProperty("user.home")
      request.body
        .file("myFile")
        .map { backup =>
          db.shutdown()
          backup.ref.copyTo(Paths.get(s"$path/ergoMixer/ergoMixerRestore.zip"), replace = true)
          ErgoMixerUtils.restore()
          System.exit(0)
          Ok("Backup restored")
        }
        .getOrElse {
          BadRequest(s"""{"success": false, "message": "No uploaded backup found."}""").as("application/json")
        }

    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
    }
  }

  /**
   * A Get controller for return information of Mixer
   *
   * @return {
   *         "versionMixer": ${info.version},
   *         "ergoExplorer": ${ErgoMixingSystem.explorerUrl},
   *         "ergoNode": ${ErgoMixingSystem.nodeUrl}
   *         }
   */
  def getInfo: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    val nodes = Configs.nodes.map(url =>
      s"""{
         |  "url": "$url",
         |  "canConnect": ${MixUtils.prunedClients.map(_.asInstanceOf[RestApiErgoClient].getNodeUrl).contains(url)}
         |}""".stripMargin)
    Ok(
      s"""
         |{
         |  "isWindows": ${SystemUtils.IS_OS_WINDOWS},
         |  "versionMixer": "${BuildInfo.version}",
         |  "ergoExplorer": "${Configs.explorerUrl}",
         |  "ergoExplorerFront": "${Configs.explorerFrontend}",
         |  "ergoNode": [${nodes.mkString(",")}]
         |}
         |""".stripMargin
    ).as("application/json")
  }

  /**
   * A post controller to create covert address.
   */
  def covertRequest: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    val js = io.circe.parser.parse(request.body.asJson.get.toString()).getOrElse(Json.Null)
    val numRounds: Int = js.hcursor.downField("numRounds").as[Int].getOrElse(-1)
    val addresses: Seq[String] = js.hcursor.downField("addresses").as[Seq[String]].getOrElse(Nil).map(_.trim)
    if (numRounds == -1) {
      BadRequest(
        s"""
           |{
           |  "success": false,
           |  "message": "all required fields must be present."
           |}
           |""".stripMargin
      ).as("application/json")
    } else {
      try {
        val addr = ergoMixer.newCovertRequest(numRounds, addresses)
        Ok(
          s"""{
             |  "success": true,
             |  "depositAddress": "$addr"
             |}""".stripMargin
        ).as("application/json")
      } catch {
        case e: Throwable =>
          logger.error(s"error in controller ${getStackTraceStr(e)}")
          BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
      }

    }
  }

  /**
   * A post endpoint to add or update a covert's assets
   * example input:
   * {
   * "tokenId": "",
   * "ring": 1000000000
   * }
   *
   * @param covertId covert id
   */
  def covertAddOrUpdate(covertId: String): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    val js = io.circe.parser.parse(request.body.asJson.get.toString()).getOrElse(Json.Null)
    val tokenId: String = js.hcursor.downField("tokenId").as[String].getOrElse(null)
    val ring: Long = js.hcursor.downField("ring").as[Long].getOrElse(-1)
    if (tokenId == null || ring == -1) {
      BadRequest(
        s"""
           |{
           |  "success": false,
           |  "message": "all required fields must be present."
           |}
           |""".stripMargin
      ).as("application/json")
    } else {
      try {
        ergoMixer.handleCovertSupport(covertId, tokenId, ring)
        Ok(
          s"""{
             |  "success": true
             |}""".stripMargin
        ).as("application/json")
      } catch {
        case e: Throwable =>
          logger.error(s"error in controller ${getStackTraceStr(e)}")
          BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
      }
    }
  }

  /**
   * adds a list of addresses to withdraw addresses of a covert request
   *
   * @param covertId covert id
   * @return whether the processs was successful or not
   */
  def setCovertAddresses(covertId: String): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    val js = io.circe.parser.parse(request.body.asJson.get.toString()).getOrElse(Json.Null)
    val addresses: Seq[String] = js.hcursor.downField("addresses").as[Seq[String]].getOrElse(Nil).map(_.trim)
    try {
      ergoMixer.addCovertWithdrawAddress(covertId, addresses)
      Ok(
        s"""{
           |  "success": true
           |}""".stripMargin).as("application/json")
    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
    }

  }

  /**
   * adds a list of addresses to withdraw addresses of a covert request
   *
   * @param covertId covert id
   * @return whether the processs was successful or not
   */
  def getCovertAddresses(covertId: String): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    try {
      val addresses = ergoMixer.getCovertAddresses(covertId).map(add => s""""$add"""")
      Ok(s"[${addresses.mkString(",")}]".stripMargin).as("application/json")
    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
    }

  }

  /**
   * A post controller to create a mix request with/without tokens.
   */
  def mixRequest = Action(parse.json) { request =>
    request.body.validate[MixBoxList] match {
      case JsSuccess(value, _) =>
        try {
          val id = ergoMixer.newMixGroupRequest(value.items)
          Ok(
            s"""{
               |  "success": true,
               |  "mixId": "$id"
               |}""".stripMargin).as("application/json")
        } catch {
          case e: Throwable =>
            logger.error(s"error in controller ${getStackTraceStr(e)}")
            BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
        }
      case _ => BadRequest("{\"status\": \"error\"}").as("application/json")
    }
  }

  /**
   * A get endpoint which returns list of a covert's assets
   */
  def covertAssetList(covertId: String) = Action {
    try {
      val assets = ergoMixer.getCovertAssets(covertId)
      val curMixing = ergoMixer.getCovertCurrentMixing(covertId)
      Ok(
        s"""
           |${ergoMixer.getCovertById(covertId).toJson(assets, currentMixing = curMixing)}
           |""".stripMargin
      ).as("application/json")
    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"status": "error", "message": "${e.getMessage}"}""").as("application/json")
    }
  }

  /**
   * A get endpoint which returns list covet info to be shown, includes covert's supported assets sorted based on latest activity
   */
  def covertList: Action[AnyContent] = Action {
    try {
      Ok(
        s"""
           |[${ergoMixer.getCovertList.map(covert => covert.toJson(ergoMixer.getCovertAssets(covert.id))).mkString(",")}]
           |""".stripMargin
      ).as("application/json")
    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
    }
  }

  /**
   * A get endpoint which returns list of group mixes
   */
  def mixGroupRequestList: Action[AnyContent] = Action {
    try {
      val res = "[" + (ergoMixer.getMixRequestGroups.map(_.toJson())).mkString(", ") + "]"
      Ok(res).as("application/json")
    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
    }
  }

  /**
   * A get endpoint which returns active group mixes. contains more info to be shown about deposits and ...
   */
  def mixGroupRequestActiveList = Action {
    try {
      val mixes = ergoMixer.getMixRequestGroupsActive
      val res = "[" + mixes.map(mix => {
        val doneMixes = ergoMixer.getFinishedForGroup(mix.id)
        val progress = ergoMixer.getProgressForGroup(mix.id)
        val stat =
          s"""{
             |    "numBoxes": ${doneMixes._1},
             |    "numComplete": ${doneMixes._2},
             |    "numWithdrawn": ${doneMixes._3},
             |    "totalMixRound": ${progress._1},
             |    "doneMixRound": ${progress._2}
             |  }""".stripMargin
        mix.toJson(stat)
      }).mkString(", ") + "]"
      Ok(res).as("application/json")
    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
    }
  }

  /**
   * A get endpoint which returns complete list of group mixes
   */
  def mixGroupRequestCompleteList: Action[AnyContent] = Action {
    try {
      val res = "[" + (ergoMixer.getMixRequestGroupsComplete.reverse.map(_.toJson())).mkString(", ") + "]"
      Ok(res).as("application/json")
    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
    }
  }

  /**
   * A get endpoint which returns mix boxes of a specific group or covert request
   */
  def mixRequestList(id: String, status: String): Action[AnyContent] = Action {
    try {
      val res = "[" + ergoMixer.getMixes(id, status).map(mix => {
        var withdrawTxId = ""
        if (mix.withdraw.isDefined) {
          withdrawTxId = mix.withdraw.get.txId
        }
        val lastMixTime = {
          if (mix.fullMix.isDefined) ErgoMixerUtils.prettyDate(mix.fullMix.get.createdTime)
          else if (mix.halfMix.isDefined) ErgoMixerUtils.prettyDate(mix.halfMix.get.createdTime)
          else "None"
        }

        s"""
           |{
           |  "id": "${mix.mixRequest.id}",
           |  "createdDate": "${mix.mixRequest.creationTimePrettyPrinted}",
           |  "amount": ${mix.mixRequest.amount},
           |  "rounds": ${mix.mixState.map(s => s.round).getOrElse(0)},
           |  "status": "${mix.mixRequest.mixStatus.value}",
           |  "deposit": "${mix.mixRequest.depositAddress}",
           |  "withdraw": "${mix.mixRequest.withdrawAddress}",
           |  "boxType": "${
          if (mix.fullMix.isDefined) "Full" else {
            if (mix.halfMix.isDefined) "Half" else "None"
          }
        }",
           |  "withdrawStatus": "${mix.mixRequest.withdrawStatus}",
           |  "withdrawTxId": "$withdrawTxId",
           |  "lastMixTime": "$lastMixTime",
           |  "mixingTokenId": "${mix.mixRequest.tokenId}",
           |  "mixingTokenAmount": ${mix.mixRequest.mixingTokenAmount}
           |}""".stripMargin
      }).mkString(",") + "]"
      Ok(res).as("application/json")
    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
    }
  }

  def supported(): Action[AnyContent] = Action {
    try {
      val params = Configs.params
      if (params.isEmpty) {
        BadRequest(
          s"""
             |{
             |  "success": false,
             |  "message": "params are not ready yet."
             |}
             |""".stripMargin
        ).as("application/json")
      } else {
        val supported = params.values.toList.sortBy(f => f.id)
        Ok(
          s"""
             |[${supported.map(_.toJson()).mkString(",")}]
             |""".stripMargin).as("application/json")
      }
    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
    }
  }

  /**
   * A get endpoint which returns info about current fee parameters
   */
  def mixingFee(): Action[AnyContent] = Action {
    try {
      var res =
        s"""
           |{
           |  "boxInTransaction": ${Configs.maxOuts},
           |  "distributeFee": ${Configs.distributeFee},
           |  "startFee": ${Configs.startFee},""".stripMargin
      val tokenPrices = Stats.tokenPrices.orNull
      if (tokenPrices == null) {
        BadRequest(
          s"""
             |{
             |  "success": false,
             |  "message": "token stats are not ready."
             |}
             |""".stripMargin
        ).as("application/json")
      } else {
        val rate = Stats.entranceFee.getOrElse(1000000)
        tokenPrices.foreach {
          element => res += s"""  "${element._1}": ${element._2},""".stripMargin
        }
        res +=
          s"""  "rate": $rate
             |}
             |""".stripMargin
        Ok(res).as("application/json")
      }
    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
    }
  }

  /**
   * A POST Api for Add withdraw address to database or change status withdraw
   * in route `/mix/withdraw/`
   * Input: {
   * "nonStayAtMix" : Bool
   * "withdrawAddress": String
   * "mixId": String
   * }
   * Output: {
   * "success": true or false,
   * "message": ""
   * }
   */
  def withdraw: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    val js = io.circe.parser.parse(request.body.asJson.get.toString()).getOrElse(Json.Null)
    val withdrawNow = js.hcursor.downField("nonStayAtMix").as[Boolean].getOrElse(false)
    val withdrawAddress = js.hcursor.downField("withdrawAddress").as[String].getOrElse("")
    val mixId = js.hcursor.downField("mixId").as[String].getOrElse("")

    try {
      if (withdrawAddress.nonEmpty) ergoMixer.updateMixWithdrawAddress(mixId, withdrawAddress)
      if (withdrawNow) ergoMixer.withdrawMixNow(mixId)
      Ok(s"""{"success": true}""".stripMargin).as("application/json")

    } catch {
      case e: Throwable =>
        logger.error(s"error in controller ${getStackTraceStr(e)}")
        BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
    }
  }
}

