package io.leisuremeta.chain
package node
package service

import cats.data.EitherT
import cats.effect.Concurrent
import cats.syntax.bifunctor.*
import cats.syntax.eq.given
import cats.syntax.functor.*
import cats.syntax.flatMap.*
import cats.syntax.traverse.*

import GossipDomain.MerkleState
import api.{LeisureMetaChainApi as Api}
import api.model.{
  Account,
  AccountData,
  GroupId,
  GroupData,
  PublicKeySummary,
  Transaction,
  TransactionWithResult,
}
import api.model.TransactionWithResult.ops.*
import api.model.account.EthAddress
import api.model.api_model.{AccountInfo, BalanceInfo, GroupInfo, NftBalanceInfo, RandomOfferingInfo}
import api.model.token.{
  Rarity,
  NftState,
  TokenDefinition,
  TokenDefinitionId,
  TokenDetail,
  TokenId,
}
import lib.codec.byte.{ByteDecoder, DecodeResult}
import lib.codec.byte.ByteEncoder.ops.*
import lib.crypto.Hash
import lib.datatype.BigNat
import lib.merkle.{MerkleTrie, MerkleTrieState}
import repository.{BlockRepository, StateRepository, TransactionRepository}
import StateRepository.given

object StateReadService:
  def getAccountInfo[F[_]
    : Concurrent: BlockRepository: StateRepository.AccountState](
      account: Account,
  ): F[Option[AccountInfo]] = for
    bestHeaderEither <- BlockRepository[F].bestHeader.value
    bestHeader <- bestHeaderEither match
      case Left(err) => Concurrent[F].raiseError(err)
      case Right(None) =>
        Concurrent[F].raiseError(new Exception("No best header"))
      case Right(Some(bestHeader)) => Concurrent[F].pure(bestHeader)
    merkleState = MerkleState.from(bestHeader)
    accountStateEither <- MerkleTrie
      .get[F, Account, AccountData](account.toBytes.bits)
      .runA(merkleState.account.namesState)
      .value
    accountStateOption <- accountStateEither match
      case Left(err) => Concurrent[F].raiseError(new Exception(err))
      case Right(accountStateOption) => Concurrent[F].pure(accountStateOption)
    keyListEither <- MerkleTrie
      .from[F, (Account, PublicKeySummary), PublicKeySummary.Info](
        account.toBytes.bits,
      )
      .runA(merkleState.account.keyState)
      .flatMap(
        _.takeWhile(_._1.startsWith(account.toBytes.bits)).compile.toList
          .flatMap { (list) =>
            list.traverse { case (bits, v) =>
              EitherT.fromEither[F] {
                ByteDecoder[(Account, PublicKeySummary)]
                  .decode(bits.bytes) match
                  case Left(err) => Left(err.msg)
                  case Right(
                        DecodeResult((account, publicKeySummary), remainder),
                      ) =>
                    if remainder.isEmpty then Right((publicKeySummary, v))
                    else
                      Left(s"non-empty remainder in decoding $publicKeySummary")
              }
            }
          },
      )
      .value
    keyList <- keyListEither match
      case Left(err)      => Concurrent[F].raiseError(new Exception(err))
      case Right(keyList) => Concurrent[F].pure(keyList)
  yield accountStateOption.map(accountData => AccountInfo(
    accountData.ethAddress,
    accountData.guardian,
    keyList.toMap
  ))

  def getEthAccount[F[_]: Concurrent: BlockRepository: StateRepository.AccountState](
    ethAddress: EthAddress
  ): F[Option[Account]] = for
    bestHeaderEither <- BlockRepository[F].bestHeader.value
    bestHeader <- bestHeaderEither match
      case Left(err) => Concurrent[F].raiseError(err)
      case Right(None) =>
        Concurrent[F].raiseError(new Exception("No best header"))
      case Right(Some(bestHeader)) => Concurrent[F].pure(bestHeader)
    merkleState = MerkleState.from(bestHeader)
    ethStateEither <- MerkleTrie
      .get[F, EthAddress, Account](ethAddress.toBytes.bits)
      .runA(merkleState.account.ethState)
      .value
    ethStateOption <- ethStateEither match
      case Left(err) => Concurrent[F].raiseError(new Exception(err))
      case Right(ethStateOption) => Concurrent[F].pure(ethStateOption)
  yield ethStateOption

  def getGroupInfo[F[_]
    : Concurrent: BlockRepository: StateRepository.GroupState](
      groupId: GroupId,
  ): F[Option[GroupInfo]] = for
    bestHeaderEither <- BlockRepository[F].bestHeader.value
    bestHeader <- bestHeaderEither match
      case Left(err) => Concurrent[F].raiseError(err)
      case Right(None) =>
        Concurrent[F].raiseError(new Exception("No best header"))
      case Right(Some(bestHeader)) => Concurrent[F].pure(bestHeader)
    merkleState = MerkleState.from(bestHeader)
    groupDataEither <- MerkleTrie
      .get[F, GroupId, GroupData](groupId.toBytes.bits)
      .runA(merkleState.group.groupState)
      .value
    groupDataOption <- groupDataEither match
      case Left(err) => Concurrent[F].raiseError(new Exception(err))
      case Right(groupDataOption) => Concurrent[F].pure(groupDataOption)
    accountListEither <- MerkleTrie
      .from[F, (GroupId, Account), Unit](
        groupId.toBytes.bits,
      )
      .runA(merkleState.group.groupAccountState)
      .flatMap(
        _.takeWhile(_._1.startsWith(groupId.toBytes.bits)).compile.toList
          .flatMap { (list) =>
            list.traverse { case (bits, _) =>
              EitherT.fromEither[F] {
                ByteDecoder[(GroupId, Account)]
                  .decode(bits.bytes) match
                  case Left(err) => Left(err.msg)
                  case Right(
                        DecodeResult((groupId, account), remainder),
                      ) =>
                    if remainder.isEmpty then Right(account)
                    else Left(s"non-empty remainder in decoding $account")
              }
            }
          },
      )
      .value
    accountList <- accountListEither match
      case Left(err)          => Concurrent[F].raiseError(new Exception(err))
      case Right(accountList) => Concurrent[F].pure(accountList)
  yield groupDataOption.map(groupData =>
    GroupInfo(groupData, accountList.toSet),
  )

  def getTokenDef[F[_]
    : Concurrent: BlockRepository: StateRepository.TokenState](
      tokenDefinitionId: TokenDefinitionId,
  ): F[Option[TokenDefinition]] = for
    bestHeaderEither <- BlockRepository[F].bestHeader.value
    bestHeader <- bestHeaderEither match
      case Left(err) => Concurrent[F].raiseError(err)
      case Right(None) =>
        Concurrent[F].raiseError(new Exception("No best header"))
      case Right(Some(bestHeader)) => Concurrent[F].pure(bestHeader)
    merkleState = MerkleState.from(bestHeader)
    tokenDefEither <- MerkleTrie
      .get[F, TokenDefinitionId, TokenDefinition](
        tokenDefinitionId.toBytes.bits,
      )
      .runA(merkleState.token.tokenDefinitionState)
      .value
    tokenDefOption <- tokenDefEither match
      case Left(err)             => Concurrent[F].raiseError(new Exception(err))
      case Right(tokenDefOption) => Concurrent[F].pure(tokenDefOption)
  yield tokenDefOption

  def getBalance[F[_]
    : Concurrent: BlockRepository: TransactionRepository: StateRepository.TokenState](
      account: Account,
      movableOption: Option[Api.Movable],
  ): F[Map[TokenDefinitionId, BalanceInfo]] = getFreeBalance[F](account)

  def getFreeBalance[F[_]
    : Concurrent: BlockRepository: TransactionRepository: StateRepository.TokenState](
      account: Account,
  ): F[Map[TokenDefinitionId, BalanceInfo]] =
    for
      bestHeaderEither <- BlockRepository[F].bestHeader.value
      bestHeader <- bestHeaderEither match
        case Left(err) => Concurrent[F].raiseError(err)
        case Right(None) =>
          Concurrent[F].raiseError(new Exception("No best header"))
        case Right(Some(bestHeader)) => Concurrent[F].pure(bestHeader)
      merkleState = MerkleState.from(bestHeader)
      balanceListEither <- MerkleTrie
        .from[
          F,
          (Account, TokenDefinitionId, Hash.Value[TransactionWithResult]),
          Unit,
        ](
          account.toBytes.bits,
        )
        .runA(merkleState.token.fungibleBalanceState)
        .flatMap(
          _.takeWhile(_._1.startsWith(account.toBytes.bits)).compile.toList
            .flatMap { (list) =>
              list.traverse { case (bits, _) =>
                EitherT.fromEither[F] {
                  ByteDecoder[
                    (
                        Account,
                        TokenDefinitionId,
                        Hash.Value[TransactionWithResult],
                    ),
                  ]
                    .decode(bits.bytes) match
                    case Left(err) => Left(err.msg)
                    case Right(
                          DecodeResult(
                            (account, tokenDefinitionId, txHash),
                            remainder,
                          ),
                        ) =>
                      if remainder.isEmpty then
                        Right((tokenDefinitionId, txHash))
                      else Left(s"non-empty remainder in decoding $account")
                }
              }
            },
        )
        .value
      balanceList <- balanceListEither match
        case Left(err)          => Concurrent[F].raiseError(new Exception(err))
        case Right(balanceList) => Concurrent[F].pure(balanceList)
      balanceTxEither <- balanceList.traverse { (defId, txHash) =>
        TransactionRepository[F].get(txHash).map { txWithResultOption =>
          txWithResultOption.map(txWithResult => (defId, txHash, txWithResult))
        }
      }.value
      balanceTxList <- balanceTxEither match
        case Left(err) => Concurrent[F].raiseError(new Exception(err.msg))
        case Right(balanceTxList) => Concurrent[F].pure(balanceTxList.flatten)
    yield balanceTxList.groupMapReduce(_._1) { (defId, txHash, txWithResult) =>
      txWithResult.signedTx.value match
        case fb: Transaction.FungibleBalance =>
          fb match
            case mf: Transaction.TokenTx.MintFungibleToken =>
              BalanceInfo(
                totalAmount = mf.outputs.get(account).getOrElse(BigNat.Zero),
                unused = Map(txHash -> txWithResult),
              )
            case tf: Transaction.TokenTx.TransferFungibleToken =>
              BalanceInfo(
                totalAmount = tf.outputs.get(account).getOrElse(BigNat.Zero),
                unused = Map(txHash -> txWithResult),
              )
            case ad: Transaction.TokenTx.AcceptDeal =>
              txWithResult.result match
                case Some(Transaction.TokenTx.AcceptDealResult(outputs)) =>
                  BalanceInfo(
                    totalAmount = outputs
                      .get(account)
                      .flatMap(_.get(defId))
                      .fold(BigNat.Zero) {
                        case TokenDetail.FungibleDetail(amount) => amount
                        case TokenDetail.NftDetail(_)           => BigNat.Zero
                      },
                    unused = Map(txHash -> txWithResult),
                  )
                case _ =>
                  throw new Exception(
                    s"AcceptDeal result is not found for $txHash",
                  )
            case cs: Transaction.TokenTx.CancelSuggestion =>
              txWithResult.result match
                case Some(Transaction.TokenTx.CancelSuggestionResult(cancelDefId, detail)) =>
                  BalanceInfo(
                    totalAmount = {
                      detail match
                        case TokenDetail.FungibleDetail(amount) if cancelDefId === defId =>
                          amount
                        case _ =>
                          BigNat.Zero
                    },
                    unused = Map(txHash -> txWithResult),
                  )
                case _ =>
                  throw new Exception(
                    s"CancelSuggestion result is not found for $txHash",
                  )
            case jt: Transaction.RandomOfferingTx.JoinTokenOffering =>
              txWithResult.result match
                case Some(Transaction.RandomOfferingTx.JoinTokenOfferingResult(output))
                  if txWithResult.signedTx.sig.account === account =>

                  BalanceInfo(
                    totalAmount = output,
                    unused = Map(txHash -> txWithResult),
                  )
                case _ =>
                  throw new Exception(
                    s"JoinTokenOffering result is not found for $txHash",
                  )
            case it: Transaction.RandomOfferingTx.InitialTokenOffering =>
              txWithResult.result match
                case Some(Transaction.RandomOfferingTx.InitialTokenOfferingResult(outputs)) =>
                  BalanceInfo(
                    totalAmount = outputs
                      .get(account)
                      .flatMap(_.get(defId))
                      .getOrElse(BigNat.Zero),
                    unused = Map(txHash -> txWithResult),
                  )
                case _ =>
                  throw new Exception(
                    s"InitialTokenOffering result is not found for $txHash",
                  )

        case _ => BalanceInfo(totalAmount = BigNat.Zero, unused = Map.empty)
    }((a, b) =>
      BalanceInfo(
        totalAmount = BigNat.add(a.totalAmount, b.totalAmount),
        unused = a.unused ++ b.unused,
      ),
    )

  def getNftBalance[F[_]
    : Concurrent: BlockRepository: TransactionRepository: StateRepository.TokenState](
      account: Account,
      movableOption: Option[Api.Movable],
  ): F[Map[TokenId, NftBalanceInfo]] = for
    bestHeaderEither <- BlockRepository[F].bestHeader.value
    bestHeader <- bestHeaderEither match
      case Left(err) => Concurrent[F].raiseError(err)
      case Right(None) =>
        Concurrent[F].raiseError(new Exception("No best header"))
      case Right(Some(bestHeader)) => Concurrent[F].pure(bestHeader)
    merkleState = MerkleState.from(bestHeader)
    nftBalanceMap <- getNftBalanceFromNftBalanceState[F](account, movableOption, merkleState.token.nftBalanceState)
  yield nftBalanceMap

  def getNftBalanceFromNftBalanceState[F[_]
    : Concurrent: BlockRepository: TransactionRepository: StateRepository.TokenState](
      account: Account,
      movableOption: Option[Api.Movable],
      nftBalanceState: MerkleTrieState[(Account, TokenId, Hash.Value[TransactionWithResult]),Unit],
    ): F[Map[TokenId, NftBalanceInfo]] = for
    balanceListEither <- MerkleTrie
      .from[
        F,
        (Account, TokenId, Hash.Value[TransactionWithResult]),
        Unit,
      ](
        account.toBytes.bits,
      )
      .runA(nftBalanceState)
      .flatMap(
        _.takeWhile(_._1.startsWith(account.toBytes.bits)).compile.toList
          .flatMap { (list) =>
            list.traverse { case (bits, _) =>
              EitherT.fromEither[F] {
                ByteDecoder[
                  (
                      Account,
                      TokenId,
                      Hash.Value[TransactionWithResult],
                  ),
                ]
                  .decode(bits.bytes) match
                  case Left(err) => Left(err.msg)
                  case Right(
                        DecodeResult(
                          (account, tokenId, txHash),
                          remainder,
                        ),
                      ) =>
                    if remainder.isEmpty then Right((tokenId, txHash))
                    else Left(s"non-empty remainder in decoding $account")
              }
            }
          },
      )
      .value
    balanceList <- balanceListEither match
      case Left(err)          => Concurrent[F].raiseError(new Exception(err))
      case Right(balanceList) => Concurrent[F].pure(balanceList)
    balanceTxEither <- balanceList.traverse { (tokenId, txHash) =>
      TransactionRepository[F].get(txHash).map { txWithResultOption =>
        txWithResultOption.map(txWithResult =>
          txWithResult.signedTx.value match
            case nb: Transaction.NftBalance =>
              nb match
                case mf: Transaction.TokenTx.MintNFT =>
                  Map(
                    tokenId -> NftBalanceInfo(
                      mf.tokenDefinitionId,
                      txHash,
                      txWithResult,
                    ),
                  )
                case cs: Transaction.TokenTx.CancelSuggestion =>
                  txWithResult.result match
                    case Some(Transaction.TokenTx.CancelSuggestionResult(cancelDefId, detail)) =>
                      detail match
                        case TokenDetail.NftDetail(tokenId) =>
                          Map(
                            tokenId -> NftBalanceInfo(
                              cancelDefId,
                              txHash,
                              txWithResult,
                            ),
                          )
                        case _ => Map.empty
                    case _ =>  Map.empty
            case _ =>
              Map.empty,
        )
      }
    }.value
    balanceTxList <- balanceTxEither match
      case Left(err) => Concurrent[F].raiseError(new Exception(err.msg))
      case Right(balanceTxList) => Concurrent[F].pure(balanceTxList.flatten)
  yield balanceTxList.foldLeft(Map.empty)(_ ++ _)

  def getToken[F[_]: Concurrent: BlockRepository: StateRepository.TokenState](
      tokenId: TokenId,
  ): EitherT[F, String, Option[NftState]] = for
    bestHeaderOption <- BlockRepository[F].bestHeader.leftMap(_.msg)
    bestHeader <- EitherT.fromOption[F](bestHeaderOption, "No best header")
    merkleState = MerkleState.from(bestHeader)
    nftStateOption <- MerkleTrie
      .get[F, TokenId, NftState](tokenId.toBytes.bits)
      .runA(merkleState.token.nftState)
  yield nftStateOption

  def getOwners[F[_]: Concurrent: BlockRepository: StateRepository.TokenState](
      tokenDefinitionId: TokenDefinitionId,
  ): EitherT[F, String, Map[TokenId, Account]] = for
    bestHeaderOption <- BlockRepository[F].bestHeader.leftMap(_.msg)
    bestHeader <- EitherT.fromOption[F](bestHeaderOption, "No best header")
    merkleState = MerkleState.from(bestHeader)
    tokenIdList <- MerkleTrie
      .from[F, (TokenDefinitionId, Rarity, TokenId), Unit](
        tokenDefinitionId.toBytes.bits,
      )
      .runA(merkleState.token.rarityState)
      .flatMap(
        _.takeWhile(
          _._1.startsWith(tokenDefinitionId.toBytes.bits),
        ).compile.toList
          .flatMap { (list) =>
            list.traverse { case (bits, _) =>
              EitherT.fromEither[F] {
                ByteDecoder[(TokenDefinitionId, Rarity, TokenId)]
                  .decode(bits.bytes) match
                  case Left(err) => Left(err.msg)
                  case Right(DecodeResult((_, _, tokenId), remainder)) =>
                    if remainder.isEmpty then Right(tokenId)
                    else
                      Left(
                        s"non-empty remainder in decoding rarity state of $tokenDefinitionId",
                      )
              }
            }
          },
      )
    ownerOptionList <- tokenIdList.traverse { (tokenId: TokenId) =>
      MerkleTrie
        .get[F, TokenId, NftState](tokenId.toBytes.bits)
        .runA(merkleState.token.nftState)
        .map(nftStateOption =>
          nftStateOption.map(state => (tokenId, state.currentOwner)),
        )
    }
  yield ownerOptionList.flatten.toMap

  def getOffering[F[_]: Concurrent: BlockRepository: StateRepository.TokenState: StateRepository.RandomOfferingState: TransactionRepository](
      tokenDefinitionId: TokenDefinitionId,
  ): EitherT[F, String, Option[RandomOfferingInfo]] =
    summon[StateRepository.RandomOfferingState[F]].randomOffering
    for
      bestHeaderOption <- BlockRepository[F].bestHeader.leftMap(_.msg)
      bestHeader <- EitherT.fromOption[F](bestHeaderOption, "No best header")
      merkleState = MerkleState.from(bestHeader)
      noticeTxHashOption <- MerkleTrie.get[F, TokenDefinitionId, Hash.Value[TransactionWithResult]](
        tokenDefinitionId.toBytes.bits,
      ).runA(merkleState.offering.offeringState)
      infoOption <- noticeTxHashOption.traverse { noticeTxHash =>
        for
          noticeTxOption <- TransactionRepository[F].get(noticeTxHash).leftMap(_.msg)
          noticeTx <- EitherT.right(noticeTxOption.fold{
            Concurrent[F].raiseError(new Exception(s"No notice transaction $noticeTxHash"))
          }{ (noticeTx) =>
            noticeTx.signedTx.value match
              case tx: Transaction.RandomOfferingTx.NoticeTokenOffering =>
                for
                  balanceMap <- getNftBalanceFromNftBalanceState[F](tx.offeringAccount, None, merkleState.token.nftBalanceState)
                yield RandomOfferingInfo(
                  tokenDefinitionId = tokenDefinitionId,
                  noticeTxHash = noticeTxHash.toSignedTxHash,
                  noticeTx = tx,
                  currentBalance = balanceMap,
                )
              case tx => Concurrent[F].raiseError(new Exception(s"Not a notice transaction $tx"))
          })
        yield noticeTx
      }
    yield infoOption
    