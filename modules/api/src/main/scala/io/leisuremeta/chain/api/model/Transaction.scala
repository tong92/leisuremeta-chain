package io.leisuremeta.chain
package api.model

import java.time.Instant

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import scodec.bits.ByteVector

import account.EthAddress
//import agenda.AgendaId
import reward.DaoActivity
import lib.crypto.{Hash, Recover, Sign}
import lib.codec.byte.{ByteDecoder, ByteEncoder}
import lib.codec.byte.ByteEncoder.ops.*
import lib.datatype.{BigNat, UInt256Bytes, Utf8}
import token.{Rarity, NftInfo, NftInfoWithPrecision, TokenDefinitionId, TokenId}

sealed trait TransactionResult
object TransactionResult:
  given txResultByteEncoder: ByteEncoder[TransactionResult] =
    (txr: TransactionResult) =>
      txr match
        case r: Transaction.AccountTx.AddPublicKeySummariesResult =>
          ByteVector.fromByte(0) ++ r.toBytes
        case r: Transaction.TokenTx.BurnFungibleTokenResult =>
          ByteVector.fromByte(1) ++ r.toBytes
        case r: Transaction.TokenTx.EntrustFungibleTokenResult =>
          ByteVector.fromByte(2) ++ r.toBytes
        case r: Transaction.RewardTx.ExecuteRewardResult =>
          ByteVector.fromByte(3) ++ r.toBytes
        case r: Transaction.RewardTx.ExecuteOwnershipRewardResult =>
          ByteVector.fromByte(4) ++ r.toBytes
        case r: Transaction.AgendaTx.VoteSimpleAgendaResult =>
          ByteVector.fromByte(5) ++ r.toBytes

  given txResultByteDecoder: ByteDecoder[TransactionResult] =
    ByteDecoder.byteDecoder.flatMap {
      case 0 =>
        ByteDecoder[Transaction.AccountTx.AddPublicKeySummariesResult].widen
      case 1 =>
        ByteDecoder[Transaction.TokenTx.BurnFungibleTokenResult].widen
      case 2 =>
        ByteDecoder[Transaction.TokenTx.EntrustFungibleTokenResult].widen
      case 3 =>
        ByteDecoder[Transaction.RewardTx.ExecuteRewardResult].widen
      case 4 =>
        ByteDecoder[Transaction.RewardTx.ExecuteOwnershipRewardResult].widen
      case 5 =>
        ByteDecoder[Transaction.AgendaTx.VoteSimpleAgendaResult].widen
    }

  given txResultCirceEncoder: Encoder[TransactionResult] =
    deriveEncoder[TransactionResult]

  given txResultCirceDecoder: Decoder[TransactionResult] =
    deriveDecoder[TransactionResult]

sealed trait Transaction:
  def networkId: NetworkId
  def createdAt: Instant
//  def memo: Option[Utf8]

object Transaction:
  sealed trait AccountTx extends Transaction:
    def account: Account
  object AccountTx:
    final case class CreateAccount(
        networkId: NetworkId,
        createdAt: Instant,
        account: Account,
        ethAddress: Option[EthAddress],
        guardian: Option[Account],
//        memo: Option[Utf8],
    ) extends AccountTx

    final case class UpdateAccount(
        networkId: NetworkId,
        createdAt: Instant,
        account: Account,
        ethAddress: Option[EthAddress],
        guardian: Option[Account],
//        memo: Option[Utf8],
    ) extends AccountTx

    final case class AddPublicKeySummaries(
        networkId: NetworkId,
        createdAt: Instant,
        account: Account,
        summaries: Map[PublicKeySummary, Utf8],
//        memo: Option[Utf8],
    ) extends AccountTx

    final case class AddPublicKeySummariesResult(
        removed: Map[PublicKeySummary, Utf8],
    ) extends TransactionResult

//    final case class RemovePublicKeySummaries(
//        networkId: NetworkId,
//        createdAt: Instant,
//        account: Account,
//        summaries: Set[PublicKeySummary],
//    ) extends AccountTx
//
//    final case class RemoveAccount(
//        networkId: NetworkId,
//        createdAt: Instant,
//        account: Account,
//    ) extends AccountTx

    given txByteDecoder: ByteDecoder[AccountTx] = ByteDecoder[BigNat].flatMap {
      bignat =>
        bignat.toBigInt.toInt match
          case 0 => ByteDecoder[CreateAccount].widen
          case 1 => ByteDecoder[UpdateAccount].widen
          case 2 => ByteDecoder[AddPublicKeySummaries].widen
//          case 3 => ByteDecoder[RemovePublicKeySummaries].widen
//          case 4 => ByteDecoder[RemoveAccount].widen
    }
    given txByteEncoder: ByteEncoder[AccountTx] = (atx: AccountTx) =>
      atx match
        case tx: CreateAccount         => build(0)(tx)
        case tx: UpdateAccount         => build(1)(tx)
        case tx: AddPublicKeySummaries => build(2)(tx)
//        case tx: RemovePublicKeySummaries => build(3)(tx)
//        case tx: RemoveAccount            => build(4)(tx)

    given txCirceDecoder: Decoder[AccountTx] = deriveDecoder
    given txCirceEncoder: Encoder[AccountTx] = deriveEncoder
  end AccountTx

  sealed trait GroupTx extends Transaction
  object GroupTx:
    final case class CreateGroup(
        networkId: NetworkId,
        createdAt: Instant,
        groupId: GroupId,
        name: Utf8,
        coordinator: Account,
//        memo: Option[Utf8],
    ) extends GroupTx

//    final case class DisbandGroup(
//        networkId: NetworkId,
//        createdAt: Instant,
//        groupId: GroupId,
//    ) extends GroupTx

    final case class AddAccounts(
        networkId: NetworkId,
        createdAt: Instant,
        groupId: GroupId,
        accounts: Set[Account],
//        memo: Option[Utf8],
    ) extends GroupTx

//    final case class RemoveAccounts(
//        networkId: NetworkId,
//        createdAt: Instant,
//        groupId: GroupId,
//        accounts: Set[Account],
//    ) extends GroupTx

//    final case class ReplaceCoordinator(
//        networkId: NetworkId,
//        createdAt: Instant,
//        groupId: GroupId,
//        newCoordinator: Account,
//    ) extends GroupTx

    given txByteDecoder: ByteDecoder[GroupTx] = ByteDecoder[BigNat].flatMap {
      bignat =>
        bignat.toBigInt.toInt match
          case 0 => ByteDecoder[CreateGroup].widen
          case 2 => ByteDecoder[AddAccounts].widen
    }
    given txByteEncoder: ByteEncoder[GroupTx] = (atx: GroupTx) =>
      atx match
        case tx: CreateGroup => build(0)(tx)
        case tx: AddAccounts => build(2)(tx)

    given txCirceDecoder: Decoder[GroupTx] = deriveDecoder
    given txCirceEncoder: Encoder[GroupTx] = deriveEncoder
  end GroupTx

  sealed trait TokenTx extends Transaction
  object TokenTx:
    final case class DefineToken(
        networkId: NetworkId,
        createdAt: Instant,
        definitionId: TokenDefinitionId,
        name: Utf8,
        symbol: Option[Utf8],
        minterGroup: Option[GroupId],
        nftInfo: Option[NftInfo],
//        memo: Option[Utf8],
    ) extends TokenTx

    final case class DefineTokenWithPrecision(
        networkId: NetworkId,
        createdAt: Instant,
        definitionId: TokenDefinitionId,
        name: Utf8,
        symbol: Option[Utf8],
        minterGroup: Option[GroupId],
        nftInfo: Option[NftInfoWithPrecision],
//        memo: Option[Utf8],
    ) extends TokenTx

    final case class MintFungibleToken(
        networkId: NetworkId,
        createdAt: Instant,
        definitionId: TokenDefinitionId,
        outputs: Map[Account, BigNat],
//        memo: Option[Utf8],
    ) extends TokenTx
        with FungibleBalance

    final case class MintNFT(
        networkId: NetworkId,
        createdAt: Instant,
        tokenDefinitionId: TokenDefinitionId,
        tokenId: TokenId,
        rarity: Rarity,
        dataUrl: Utf8,
        contentHash: UInt256Bytes,
        output: Account,
//        memo: Option[Utf8],
    ) extends TokenTx
        with NftBalance

    final case class MintNFTWithMemo(
        networkId: NetworkId,
        createdAt: Instant,
        tokenDefinitionId: TokenDefinitionId,
        tokenId: TokenId,
        rarity: Rarity,
        dataUrl: Utf8,
        contentHash: UInt256Bytes,
        output: Account,
        memo: Option[Utf8],
    ) extends TokenTx
        with NftBalance

    final case class BurnFungibleToken(
        networkId: NetworkId,
        createdAt: Instant,
        definitionId: TokenDefinitionId,
        amount: BigNat,
        inputs: Set[Signed.TxHash],
//        memo: Option[Utf8],
    ) extends TokenTx
        with FungibleBalance

    final case class BurnFungibleTokenResult(
        outputAmount: BigNat,
    ) extends TransactionResult

    final case class BurnNFT(
        networkId: NetworkId,
        createdAt: Instant,
        definitionId: TokenDefinitionId,
        input: Signed.TxHash,
//        memo: Option[Utf8],
    ) extends TokenTx

    final case class UpdateNFT(
        networkId: NetworkId,
        createdAt: Instant,
        tokenDefinitionId: TokenDefinitionId,
        tokenId: TokenId,
        rarity: Rarity,
        dataUrl: Utf8,
        contentHash: UInt256Bytes,
        output: Account,
        memo: Option[Utf8],
    ) extends TokenTx

    final case class TransferFungibleToken(
        networkId: NetworkId,
        createdAt: Instant,
        tokenDefinitionId: TokenDefinitionId,
        inputs: Set[Signed.TxHash],
        outputs: Map[Account, BigNat],
        memo: Option[Utf8],
    ) extends TokenTx
        with FungibleBalance

    final case class TransferNFT(
        networkId: NetworkId,
        createdAt: Instant,
        definitionId: TokenDefinitionId,
        tokenId: TokenId,
        input: Signed.TxHash,
        output: Account,
        memo: Option[Utf8],
    ) extends TokenTx
        with NftBalance

    final case class EntrustFungibleToken(
        networkId: NetworkId,
        createdAt: Instant,
        definitionId: TokenDefinitionId,
        amount: BigNat,
        inputs: Set[Signed.TxHash],
        to: Account,
//        memo: Option[Utf8],
    ) extends TokenTx
        with FungibleBalance

    final case class EntrustFungibleTokenResult(
        remainder: BigNat,
    ) extends TransactionResult

    final case class EntrustNFT(
        networkId: NetworkId,
        createdAt: Instant,
        definitionId: TokenDefinitionId,
        tokenId: TokenId,
        input: Signed.TxHash,
        to: Account,
//        memo: Option[Utf8],
    ) extends TokenTx

    final case class DisposeEntrustedFungibleToken(
        networkId: NetworkId,
        createdAt: Instant,
        definitionId: TokenDefinitionId,
        inputs: Set[Signed.TxHash],
        outputs: Map[Account, BigNat],
//        memo: Option[Utf8],
    ) extends TokenTx
        with FungibleBalance

    final case class DisposeEntrustedNFT(
        networkId: NetworkId,
        createdAt: Instant,
        definitionId: TokenDefinitionId,
        tokenId: TokenId,
        input: Signed.TxHash,
        output: Option[Account],
//        memo: Option[Utf8],
    ) extends TokenTx
        with NftBalance

    given txByteDecoder: ByteDecoder[TokenTx] = ByteDecoder[BigNat].flatMap {
      bignat =>
        bignat.toBigInt.toInt match
          case 0  => ByteDecoder[DefineToken].widen
          case 1  => ByteDecoder[MintFungibleToken].widen
          case 2  => ByteDecoder[MintNFT].widen
          case 4  => ByteDecoder[TransferFungibleToken].widen
          case 5  => ByteDecoder[TransferNFT].widen
          case 6  => ByteDecoder[BurnFungibleToken].widen
          case 7  => ByteDecoder[BurnNFT].widen
          case 8  => ByteDecoder[EntrustFungibleToken].widen
          case 9  => ByteDecoder[EntrustNFT].widen
          case 10 => ByteDecoder[DisposeEntrustedFungibleToken].widen
          case 11 => ByteDecoder[DisposeEntrustedNFT].widen
          case 12 => ByteDecoder[DefineTokenWithPrecision].widen
          case 13 => ByteDecoder[UpdateNFT].widen
          case 14 => ByteDecoder[MintNFTWithMemo].widen
    }

    given txByteEncoder: ByteEncoder[TokenTx] = (ttx: TokenTx) =>
      ttx match
        case tx: DefineToken                   => build(0)(tx)
        case tx: MintFungibleToken             => build(1)(tx)
        case tx: MintNFT                       => build(2)(tx)
        case tx: TransferFungibleToken         => build(4)(tx)
        case tx: TransferNFT                   => build(5)(tx)
        case tx: BurnFungibleToken             => build(6)(tx)
        case tx: BurnNFT                       => build(7)(tx)
        case tx: EntrustFungibleToken          => build(8)(tx)
        case tx: EntrustNFT                    => build(9)(tx)
        case tx: DisposeEntrustedFungibleToken => build(10)(tx)
        case tx: DisposeEntrustedNFT           => build(11)(tx)
        case tx: DefineTokenWithPrecision      => build(12)(tx)
        case tx: UpdateNFT                     => build(13)(tx)
        case tx: MintNFTWithMemo               => build(14)(tx)

    given txCirceDecoder: Decoder[TokenTx] = deriveDecoder
    given txCirceEncoder: Encoder[TokenTx] = deriveEncoder

  end TokenTx

  sealed trait RewardTx extends Transaction
  object RewardTx:
    final case class RegisterDao(
        networkId: NetworkId,
        createdAt: Instant,
        groupId: GroupId,
        daoAccountName: Account,
        moderators: Set[Account],
//        memo: Option[Utf8],
    ) extends RewardTx

    final case class UpdateDao(
        networkId: NetworkId,
        createdAt: Instant,
        groupId: GroupId,
        moderators: Set[Account],
        memo: Option[Utf8],
    ) extends RewardTx

    final case class RecordActivity(
        networkId: NetworkId,
        createdAt: Instant,
        timestamp: Instant,
        userActivity: Map[Account, Seq[DaoActivity]],
        tokenReceived: Map[TokenId, Seq[DaoActivity]],
        memo: Option[Utf8],
    ) extends RewardTx

    final case class OfferReward(
        networkId: NetworkId,
        createdAt: Instant,
        tokenDefinitionId: TokenDefinitionId,
        inputs: Set[Signed.TxHash],
        outputs: Map[Account, BigNat],
        memo: Option[Utf8],
    ) extends RewardTx
        with FungibleBalance

    final case class BuildSnapshot(
        networkId: NetworkId,
        createdAt: Instant,
        timestamp: Instant,
        accountAmount: BigNat,
        tokenAmount: BigNat,
        ownershipAmount: BigNat,
        memo: Option[Utf8],
    ) extends RewardTx

    final case class ExecuteReward(
        networkId: NetworkId,
        createdAt: Instant,
        daoAccount: Option[Account],
        memo: Option[Utf8],
    ) extends RewardTx
        with FungibleBalance

    final case class ExecuteRewardResult(
        outputs: Map[Account, BigNat],
    ) extends TransactionResult

    final case class ExecuteOwnershipReward(
        networkId: NetworkId,
        createdAt: Instant,
        definitionId: TokenDefinitionId,
        inputs: Set[Hash.Value[TransactionWithResult]],
        targets: Set[TokenId],
        memo: Option[Utf8],
    ) extends RewardTx
        with FungibleBalance

    final case class ExecuteOwnershipRewardResult(
        outputs: Map[Account, BigNat],
    ) extends TransactionResult

    given txByteDecoder: ByteDecoder[RewardTx] = ByteDecoder[BigNat].flatMap {
      bignat =>
        bignat.toBigInt.toInt match
          case 0 => ByteDecoder[RegisterDao].widen
          case 1 => ByteDecoder[UpdateDao].widen
          case 2 => ByteDecoder[RecordActivity].widen
          case 3 => ByteDecoder[OfferReward].widen
          case 4 => ByteDecoder[BuildSnapshot].widen
          case 6 => ByteDecoder[ExecuteReward].widen
          case 9 => ByteDecoder[ExecuteOwnershipReward].widen
    }

    given txByteEncoder: ByteEncoder[RewardTx] = (rtx: RewardTx) =>
      rtx match
        case tx: RegisterDao            => build(0)(tx)
        case tx: UpdateDao              => build(1)(tx)
        case tx: RecordActivity         => build(2)(tx)
        case tx: OfferReward            => build(3)(tx)
        case tx: BuildSnapshot          => build(4)(tx)
        case tx: ExecuteReward          => build(6)(tx)
        case tx: ExecuteOwnershipReward => build(9)(tx)

    given txCirceDecoder: Decoder[RewardTx] = deriveDecoder
    given txCirceEncoder: Encoder[RewardTx] = deriveEncoder

  end RewardTx

  sealed trait AgendaTx extends Transaction
  object AgendaTx:
    final case class SuggestSimpleAgenda(
        networkId: NetworkId,
        createdAt: Instant,
        title: Utf8,
        votingToken: TokenDefinitionId,
        voteStart: Instant,
        voteEnd: Instant,
        voteOptions: Map[Utf8, Utf8],
        memo: Option[Utf8],
    ) extends AgendaTx

    final case class VoteSimpleAgenda(
        networkId: NetworkId,
        createdAt: Instant,
        agendaTxHash: Hash.Value[TransactionWithResult],
        selectedOption: Utf8,
        memo: Option[Utf8],
    ) extends AgendaTx

    final case class VoteSimpleAgendaResult(
        votingAmount: BigNat,
    ) extends TransactionResult

    given txByteDecoder: ByteDecoder[AgendaTx] = ByteDecoder[BigNat].flatMap {
      bignat =>
        bignat.toBigInt.toInt match
          case 0 => ByteDecoder[SuggestSimpleAgenda].widen
          case 1 => ByteDecoder[VoteSimpleAgenda].widen
    }

    given txByteEncoder: ByteEncoder[AgendaTx] = (rtx: AgendaTx) =>
      rtx match
        case tx: SuggestSimpleAgenda => build(0)(tx)
        case tx: VoteSimpleAgenda    => build(1)(tx)

    given txCirceDecoder: Decoder[AgendaTx] = deriveDecoder
    given txCirceEncoder: Encoder[AgendaTx] = deriveEncoder

  end AgendaTx

  private def build[A: ByteEncoder](discriminator: Long)(tx: A): ByteVector =
    ByteEncoder[BigNat].encode(
      BigNat.unsafeFromLong(discriminator),
    ) ++ ByteEncoder[A].encode(tx)

  given txByteDecoder: ByteDecoder[Transaction] = ByteDecoder[BigNat].flatMap {
    bignat =>
      bignat.toBigInt.toInt match
        case 0 => ByteDecoder[AccountTx].widen
        case 1 => ByteDecoder[GroupTx].widen
        case 2 => ByteDecoder[TokenTx].widen
        case 3 => ByteDecoder[RewardTx].widen
        case 4 => ByteDecoder[AgendaTx].widen
  }
  
  given txByteEncoder: ByteEncoder[Transaction] = (tx: Transaction) =>
    tx match
      case tx: AccountTx => build(0)(tx)
      case tx: GroupTx   => build(1)(tx)
      case tx: TokenTx   => build(2)(tx)
      case tx: RewardTx  => build(3)(tx)
      case tx: AgendaTx  => build(4)(tx)

  given txHash: Hash[Transaction] = Hash.build

  given txSign: Sign[Transaction] = Sign.build

  given txRecover: Recover[Transaction] = Recover.build

  given txCirceDecoder: Decoder[Transaction] = deriveDecoder
  given txCirceEncoder: Encoder[Transaction] = deriveEncoder

  sealed trait FungibleBalance

  sealed trait NftBalance:
    def tokenId: TokenId

  sealed trait DealSuggestion:
    def originalSuggestion: Option[Signed.TxHash]
