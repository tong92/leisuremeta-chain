package io.leisuremeta.chain.lmscan.frontend

import tyrian.Html.*
import tyrian.*
import io.circe.*, io.circe.parser.*, io.circe.generic.semiauto.*
import io.circe.syntax.*
import Dom.{_hidden, timeAgo, yyyy_mm_dd_time}
import ValidOutputData.*

import Log.*

object DataProcess:
  def block(model: Model) =
    val data: BlockList =
      BlockParser.decodeParser(model.blockListData.get).getOrElse(new BlockList)
    val payload =
      getOptionValue(data.payload, List()).asInstanceOf[List[Block]]
    payload

  def nft(model: Model) =
    val data: NftDetail = NftDetailParser
      .decodeParser(model.nftDetailData.get)
      .getOrElse(new NftDetail)
    val payload = getOptionValue(data.activities, List())
      .asInstanceOf[List[NftActivities]]
    payload
