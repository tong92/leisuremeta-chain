package io.leisuremeta.chain.lmscan.frontend
import io.circe.*, io.circe.generic.semiauto.*
import io.circe.syntax.*
import io.circe.parser.*

case class NftDetail(
    nftFile: Option[NftFile] = None,
    activities: Option[List[NftActivities]] = None,
)

case class NftFile(
    tokenId: Option[String] = None,
    tokenDefId: Option[String] = None,
    collectionName: Option[String] = None,
    nftName: Option[String] = None,
    nftUri: Option[String] = None,
    creatorDescription: Option[String] = None,
    dataUrl: Option[String] = None,
    rarity: Option[String] = None,
    creator: Option[String] = None,
    eventTime: Option[Int] = None,
    createdAt: Option[Int] = None,
    owner: Option[String] = None,
)

case class NftActivities(
    txHash: Option[String] = None,
    action: Option[String] = None,
    fromAddr: Option[String] = None,
    toAddr: Option[String] = None,
    createdAt: Option[Int] = None,
)

object NftDetailParser:
  given nftFileDecoder: Decoder[NftFile] = deriveDecoder[NftFile]
  given nftActivitiesDecoder: Decoder[NftActivities] =
    deriveDecoder[NftActivities]
  given txDetailDecoder: Decoder[NftDetail] = deriveDecoder[NftDetail]
  def decodeParser(body: String)            = decode[NftDetail](body)
