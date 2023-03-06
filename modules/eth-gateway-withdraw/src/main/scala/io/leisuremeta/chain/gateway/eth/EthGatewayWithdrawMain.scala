package io.leisuremeta.chain
package gateway.eth

import java.math.{BigInteger, MathContext}
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.time.Instant
import java.util.{ArrayList, Arrays, Collections}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try

import cats.data.OptionT
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.eq.*
import cats.syntax.traverse.*

import com.typesafe.config.{Config, ConfigFactory}
import io.circe.Encoder
import io.circe.generic.auto.*
import io.circe.parser.decode
import io.circe.syntax.given
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.web3j.abi.{FunctionEncoder, TypeReference}
import org.web3j.abi.datatypes.{Address, Function, Type}
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.{Credentials, RawTransaction, TransactionEncoder}
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.{
  DefaultBlockParameter,
  DefaultBlockParameterName,
  Request as Web3jRequest,
  Response as Web3jResponse,
}
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import sttp.client3.*
import sttp.model.{MediaType, StatusCode}

import lib.crypto.{CryptoOps, KeyPair}
import lib.crypto.Hash.ops.*
import lib.crypto.Sign.ops.*
import lib.datatype.*
import api.model.*
import api.model.TransactionWithResult.ops.*
import api.model.api_model.{AccountInfo, BalanceInfo, NftBalanceInfo}
import api.model.token.*
import org.web3j.protocol.core.methods.response.EthFeeHistory.FeeHistory
import java.util.concurrent.CompletableFuture

object EthGatewayWithdrawMain extends IOApp:

  given bodyJsonSerializer[A: Encoder]: BodySerializer[A] =
    (a: A) =>
      val serialized = a.asJson.noSpaces
      StringBody(serialized, "UTF-8", MediaType.ApplicationJson)

  val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()

  val getConfig: IO[Config] = IO.blocking(ConfigFactory.load)

  case class GatewayConf(
      ethAddress: String,
      ethChainId: Int,
      ethContract: String,
      ethNftContract: String,
      ethPrivate: String,
      lmPrivate: String,
      lmAddress: String,
      gatewayEthAddress: String,
  )

  object GatewayConf:
    def fromConfig(config: Config): GatewayConf =
      GatewayConf(
        ethAddress = config.getString("eth-address"),
        ethChainId = config.getInt("eth-chain-id"),
        ethContract = config.getString("eth-contract"),
        ethNftContract = config.getString("eth-nft-contract"),
        ethPrivate = config.getString("eth-private"),
        lmPrivate = config.getString("lm-private"),
        lmAddress = config.getString("lm-address"),
        gatewayEthAddress = config.getString("gateway-eth-address"),
      )

  def web3Resource(url: String): Resource[IO, Web3j] = Resource.make {

    val interceptor = HttpLoggingInterceptor()
    interceptor.setLevel(HttpLoggingInterceptor.Level.BASIC)

    val client = OkHttpClient
      .Builder()
      .addInterceptor(interceptor)
      .build()

    IO(Web3j.build(new HttpService(url, client)))
  }(web3j => IO(web3j.shutdown()))

  def submitTx(
      lmAddress: String,
      account: Account,
      keyPair: KeyPair,
      tx: Transaction,
  ): IO[Unit] =
    IO.blocking {
      val Right(sig) = keyPair.sign(tx): @unchecked
      val signedTxs  = Seq(Signed(AccountSignature(sig, account), tx))

      scribe.info(s"Sending signed transactions: $signedTxs")

      val response = basicRequest
        .response(asStringAlways)
        .post(uri"http://$lmAddress/tx")
        .body(signedTxs)
        .send(backend)

      scribe.info(s"Response: $response")
    }

  def checkLoop(
      web3j: Web3j,
      ethChainId: Int,
      lmAddress: String,
      ethContract: String,
      gatewayEthAddress: String,
      ethPrivate: String,
      keyPair: KeyPair,
  ): IO[Unit] =
    def run: IO[Unit] = for
      _ <- IO.delay(scribe.info(s"Withdrawal check started"))
      _ <- checkLmWithdrawal(
        web3j,
        ethChainId,
        lmAddress,
        ethContract,
        gatewayEthAddress,
        ethPrivate,
        keyPair,
      )
      _ <- checkNftWithdrawal(
        web3j,
        ethChainId,
        lmAddress,
        ethContract,
        gatewayEthAddress,
        ethPrivate,
        keyPair,
      )
      _ <- IO.delay(scribe.info(s"Withdrawal check finished"))
    yield ()

    def loop: IO[Unit] = for
      _ <- run.orElse(IO.unit)
      _ <- IO.sleep(10000.millis)
      _ <- loop
    yield ()

    loop

  def getFungibleBalance(
      lmAddress: String,
  ): IO[Map[TokenDefinitionId, BalanceInfo]] = IO {
    val response = basicRequest
      .response(asStringAlways)
      .get(uri"http://$lmAddress/balance/eth-gateway?movable=free")
      .send(backend)

    if response.code.isSuccess then
      decode[Map[TokenDefinitionId, BalanceInfo]](response.body) match
        case Right(balanceInfoMap) => balanceInfoMap
        case Left(error) =>
          scribe.error(s"Error decoding balance info: $error")
          scribe.error(s"response: ${response.body}")
          Map.empty
    else if response.code.code === StatusCode.NotFound.code then
      scribe.info(
        s"balance of account eth-gateway not found: ${response.body}",
      )
      Map.empty
    else
      scribe.error(s"Error getting balance: ${response.body}")
      Map.empty
  }

  def getNftBalance(
      lmAddress: String,
  ): IO[Map[TokenId, NftBalanceInfo]] = IO {
    val response = basicRequest
      .response(asStringAlways)
      .get(uri"http://$lmAddress/nft-balance/eth-gateway?movable=free")
      .send(backend)

    if response.code.isSuccess then
      decode[Map[TokenId, NftBalanceInfo]](response.body) match
        case Right(balanceInfoMap) => balanceInfoMap
        case Left(error) =>
          scribe.error(s"Error decoding nft-balance info: $error")
          scribe.error(s"response: ${response.body}")
          Map.empty
    else if response.code.code === StatusCode.NotFound.code then
      scribe.info(
        s"nft-balance of account eth-gateway not found: ${response.body}",
      )
      Map.empty
    else
      scribe.error(s"Error getting nft-balance: ${response.body}")
      Map.empty
  }

  def getAccountInfo(
      lmAddress: String,
      account: Account,
  ): IO[Option[AccountInfo]] = IO {
    val response = basicRequest
      .response(asStringAlways)
      .get(uri"http://$lmAddress/account/${account.utf8.value}")
      .send(backend)

    if response.code.isSuccess then
      decode[AccountInfo](response.body) match
        case Right(accountInfo) => Some(accountInfo)
        case Left(error) =>
          scribe.error(s"Error decoding account info: $error")
          None
    else if response.code.code === StatusCode.NotFound.code then
      scribe.info(s"account info not found: ${response.body}")
      None
    else
      scribe.error(s"Error getting account info: ${response.body}")
      None
  }

  def checkLmWithdrawal(
      web3j: Web3j,
      ethChainId: Int,
      lmAddress: String,
      ethLmContract: String,
      gatewayEthAddress: String,
      ethPrivate: String,
      keyPair: KeyPair,
  ): IO[Unit] = getFungibleBalance(lmAddress)
    .flatMap { (balanceMap: Map[TokenDefinitionId, BalanceInfo]) =>

      val gatewayAccount = Account(Utf8.unsafeFrom("eth-gateway"))
      val LM             = TokenDefinitionId(Utf8.unsafeFrom("LM"))

      balanceMap
        .get(LM)
        .toSeq
        .flatMap(_.unused.toSeq)
        .filterNot(_._2.signedTx.sig.account === gatewayAccount)
        .traverse { case (txHash, txWithResult) =>
          txWithResult.signedTx.value match
            case tx: Transaction.TokenTx.TransferFungibleToken =>
              {
                for
                  amount <- OptionT.fromOption[IO](
                    tx.outputs.get(gatewayAccount),
                  )
                  accountInfo <- OptionT(
                    getAccountInfo(lmAddress, txWithResult.signedTx.sig.account),
                  )
                  ethAddress <- OptionT.fromOption[IO](accountInfo.ethAddress)
                  _ <- OptionT.liftF {
                    transferEthLM(
                      web3j = web3j,
                      ethChainId = ethChainId,
                      ethLmContract = ethLmContract,
                      gatewayEthAddress = gatewayEthAddress,
                      ethPrivate = ethPrivate,
                      keyPair = keyPair,
                      receiverEthAddress = ethAddress.utf8.value,
                      amount = amount,
                    )
                  }
                  tx1 = Transaction.TokenTx.TransferFungibleToken(
                    networkId = NetworkId(BigNat.unsafeFromLong(1000L)),
                    createdAt = Instant.now(),
                    tokenDefinitionId = LM,
                    inputs = Set(txHash.toSignedTxHash),
                    outputs = Map(gatewayAccount -> amount),
                    memo = Some(Utf8.unsafeFrom {
                      s"After withdrawing of ${txWithResult.signedTx.sig.account}'s $amount"
                    }),
                  )
                  _ <- OptionT.liftF(
                    submitTx(lmAddress, gatewayAccount, keyPair, tx1),
                  )
                yield ()
              }.value
            case _ => IO.unit
        }
    }
    .as(())

  def checkNftWithdrawal(
      web3j: Web3j,
      ethChainId: Int,
      lmAddress: String,
      ethNftContract: String,
      gatewayEthAddress: String,
      ethPrivate: String,
      keyPair: KeyPair,
  ): IO[Unit] = getNftBalance(lmAddress)
    .flatMap { (balanceMap: Map[TokenId, NftBalanceInfo]) =>

      val gatewayAccount = Account(Utf8.unsafeFrom("eth-gateway"))

      balanceMap.toSeq.traverse { case (tokenId, balanceInfo) =>
        balanceInfo.tx.signedTx.value match
          case tx: Transaction.TokenTx.TransferNFT
              if balanceInfo.tx.signedTx.sig.account != gatewayAccount =>
            {
              for
                info <- OptionT(
                  getAccountInfo(lmAddress, balanceInfo.tx.signedTx.sig.account),
                )
                ethAddress <- OptionT.fromOption[IO](info.ethAddress)
                _ <- OptionT.liftF {
                  mintEthNft(
                    web3j = web3j,
                    ethChainId = ethChainId,
                    ethNftContract = ethNftContract,
                    gatewayEthAddress = gatewayEthAddress,
                    ethPrivate = ethPrivate,
                    keyPair = keyPair,
                    receiverEthAddress = ethAddress.utf8.value,
                    tokenId = tokenId,
                  )
                }
                tx1 = tx.copy(
                  createdAt = Instant.now,
                  input = balanceInfo.tx.signedTx.toHash,
                  output = gatewayAccount,
                  memo =
                    Some(Utf8.unsafeFrom("gateway balance after withdrawal")),
                )
                _ <- OptionT.liftF(
                  submitTx(lmAddress, gatewayAccount, keyPair, tx1),
                )
              yield ()
            }.value
          case _ => IO.unit
      }
    }
    .as(())

  def transferEthLM(
      web3j: Web3j,
      ethChainId: Int,
      ethLmContract: String,
      gatewayEthAddress: String,
      ethPrivate: String,
      keyPair: KeyPair,
      receiverEthAddress: String,
      amount: BigNat,
  ): IO[Unit] =

    val mintParams = new ArrayList[Type[?]]()
    mintParams.add(new Address(receiverEthAddress))
    mintParams.add(new Uint256(amount.toBigInt.bigInteger))

    val returnTypes = Collections.emptyList[TypeReference[?]]()

    val transferTxData = FunctionEncoder.encode {
      new Function("transfer", mintParams, returnTypes)
    }

    sendEthTransaction(
      web3j = web3j,
      ethChainId = ethChainId,
      contractAddress = ethLmContract,
      txData = transferTxData,
      gatewayEthAddress = gatewayEthAddress,
      ethPrivate = ethPrivate,
    ).as(())

  def mintEthNft(
      web3j: Web3j,
      ethChainId: Int,
      ethNftContract: String,
      gatewayEthAddress: String,
      ethPrivate: String,
      keyPair: KeyPair,
      receiverEthAddress: String,
      tokenId: TokenId,
  ): IO[Unit] =

    val tokenIdBigInt = BigInt(tokenId.utf8.value)

    val mintParams = new ArrayList[Type[?]]()
    mintParams.add(new Address(receiverEthAddress))
    mintParams.add(new Uint256(tokenIdBigInt.bigInteger))

    val returnTypes = Collections.emptyList[TypeReference[?]]()

    val mintTxData = FunctionEncoder.encode {
      new Function("safeMint", mintParams, returnTypes)
    }

    sendEthTransaction(
      web3j = web3j,
      ethChainId = ethChainId,
      contractAddress = ethNftContract,
      txData = mintTxData,
      gatewayEthAddress = gatewayEthAddress,
      ethPrivate = ethPrivate,
    ).as(())

  def requestToIO[A, B, C <: Web3jResponse[B], D](
      request: Web3jRequest[A, C],
  )(map: C => D): IO[D] =
    IO.fromCompletableFuture(IO.delay(request.sendAsync()))
      .map(map)
      .recoverWith { case t: Throwable =>
        scribe.error(t)
        IO.sleep(10.seconds) *> requestToIO(request)(map)
      }

  def sendEthTransaction(
      web3j: Web3j,
      ethChainId: Int,
      contractAddress: String,
      txData: String,
      gatewayEthAddress: String,
      ethPrivate: String,
  ): IO[BigInt] =
    val credential = Credentials.create(ethPrivate)
    assert(
      credential.getAddress() === gatewayEthAddress.toLowerCase(),
      s"invalid gateway eth address: ${credential.getAddress} vs $gatewayEthAddress",
    )
    val TX_END_CHECK_DURATION = 20000
    val TX_END_CHECK_RETRY    = 9
    val receiptProcessor = new PollingTransactionReceiptProcessor(
      web3j,
      TX_END_CHECK_DURATION,
      TX_END_CHECK_RETRY,
    )
    val manager =
      new RawTransactionManager(
        web3j,
        credential,
        ethChainId,
        receiptProcessor,
      )

    val GAS_LIMIT = 65_000

    def loop(lastTrial: Option[(BigInteger, String)]): IO[BigInt] =

      def getMaxPriorityFeePerGas(): IO[BigInteger] =
        requestToIO(web3j.ethMaxPriorityFeePerGas())(
          _.getMaxPriorityFeePerGas(),
        )

      def getBaseFee(): IO[BigInteger] =
        val blockCount: String = BigInt(9).toString(16)
        val newestBlock: DefaultBlockParameter =
          DefaultBlockParameterName.LATEST
        val rewardPercentiles: java.util.List[java.lang.Double] =
          ArrayBuffer[java.lang.Double](0, 0.5, 1, 1.5, 3, 80).asJava

        requestToIO {
          web3j.ethFeeHistory(blockCount, newestBlock, rewardPercentiles)
        } { response =>
          val history = response.getFeeHistory()

          val baseFees = history.getBaseFeePerGas().asScala

          val mean = BigDecimal(baseFees.map(BigInt(_)).sum) / 10
          val std = baseFees
            .map(x =>
              BigDecimal(
                (BigDecimal(x) - mean)
                  .pow(2)
                  .bigDecimal
                  .sqrt(MathContext.DECIMAL32),
              ),
            )
            .sum / 10
          val targetBaseFees = (mean + std + 0.5).toBigInt

          targetBaseFees.bigInteger
        }

      def getNonce(): IO[BigInteger] = requestToIO {
        web3j.ethGetTransactionCount(
          gatewayEthAddress,
          DefaultBlockParameterName.LATEST,
        )
      }(_.getTransactionCount())

      def sendNewTx(baseFee: BigInteger): IO[Option[String]] = for
        maxPriorityFeePerGas <- getMaxPriorityFeePerGas()
        _ <- IO.delay {
          scribe.info(s"Max Priority Fee Per Gas: $maxPriorityFeePerGas")
        }
        nonce <- getNonce()
        _     <- IO.delay { scribe.info(s"Nonce: $nonce") }
        tx = RawTransaction.createTransaction(
          ethChainId,
          nonce,
          BigInteger.valueOf(GAS_LIMIT),
          contractAddress,
          BigInteger.ZERO,
          txData,
          maxPriorityFeePerGas,
          baseFee,
        )
        txResponseOption <- IO
          .blocking {
            manager.signAndSend(tx)
          }
          .map{ resp =>
            if resp.hasError() then
              val e = resp.getError()
              scribe.info(s"Error in sending tx: #(${e.getCode()}) ${e.getMessage()}")
            else
              scribe.info(s"Sending Eth Tx: ${resp.getResult()}")
            Option(resp.getResult)
          }
      yield
        txResponseOption

      def getReceipt(
          txResponse: String,
      ): IO[Either[Throwable, TransactionReceipt]] =
        IO.blocking {
          Try(receiptProcessor.waitForTransactionReceipt(txResponse)).toEither
        }

      for
        _ <- IO.delay{ scribe.info(s"Last trial: ${lastTrial}")}
        baseFee <- getBaseFee()
        _ <- IO.delay{ scribe.info(s"New base fee: ${baseFee}")}
        txIdOption <- lastTrial match
          case Some((oldBaseFee, txId)) if oldBaseFee.compareTo(baseFee) >= 0 =>
            scribe.info(s"New base fee is less than old one")
            IO.pure(Some(txId))
          case _ =>
            sendNewTx(baseFee).map{
              _.orElse(lastTrial.map(_._2))
            }
        blockNumber <- txIdOption match
          case Some(txId) =>
            for
              receiptEither <- getReceipt(txId)
              blockNumber <- receiptEither match
                case Left(e) =>
                  e match
                    case te: TransactionException =>
                      scribe.info(s"Timeout: ${te.getMessage()}")
                      loop(Some(baseFee, txId))
                    case _ =>
                      scribe.error(s"Fail to send transaction: ${e.getMessage()}")
                      loop(Some(baseFee, txId))
                case Right(receipt) =>
                  IO.delay {
                    scribe.info(
                      s"transaction ${receipt.getTransactionHash()} saved to block #${receipt.getBlockNumber()}",
                    )
                    BigInt(receipt.getBlockNumber())
                  }
            yield blockNumber
          case None =>
            IO.sleep(1.minute) *> loop(None)
      yield blockNumber

    loop(None)

  def run(args: List[String]): IO[ExitCode] =
    for
      conf <- getConfig
      gatewayConf = GatewayConf.fromConfig(conf)
      keyPair = CryptoOps.fromPrivate(
        BigInt(gatewayConf.lmPrivate, 16),
      )
      _ <- web3Resource(gatewayConf.ethAddress).use { web3j =>
//        requestToIO {
//          web3j.ethGetTransactionCount(
//            gatewayConf.gatewayEthAddress,
//            DefaultBlockParameterName.PENDING,
//          )
//        }(_.getTransactionCount()).map{ nonce =>
//          println(s"Nonce: ${nonce}")
//        }

//        transferEthLM(
//            web3j = web3j,
//            ethChainId = gatewayConf.ethChainId,
//            ethLmContract = gatewayConf.ethContract,
//            gatewayEthAddress = gatewayConf.gatewayEthAddress,
//            ethPrivate = gatewayConf.ethPrivate,
//            keyPair = keyPair,
//            receiverEthAddress = "0xd84A65512fDc8d3bB98E76a7B8f27Fe411D44E71".toLowerCase(),
//            amount = BigNat.unsafeFromBigInt(BigInt(10).pow(18)),
//        )

        checkLoop(
          web3j = web3j,
          ethChainId = gatewayConf.ethChainId,
          lmAddress = gatewayConf.lmAddress,
          ethContract = gatewayConf.ethContract,
          gatewayEthAddress = gatewayConf.gatewayEthAddress,
          ethPrivate = gatewayConf.ethPrivate,
          keyPair = keyPair,
        )
      }
    yield ExitCode.Success
