package io.leisuremeta.chain.lmscan.frontend

import cats.effect.IO
import io.circe.parser.*
import tyrian.Html.*
import tyrian.*
import tyrian.http.*
import io.circe.syntax.*

object UnderHandleTxDetailMsg:
  private val onResponse: Response => Msg = response =>
    import io.circe.*, io.circe.generic.semiauto.*

    val parseResult: Either[ParsingFailure, Json] = parse(response.body)

    parseResult match
      case Left(parsingError) =>
        TxDetailMsg.GetErrorHandle(
          s"msg : Transaction hash 가 검색되지 않습니다. block hash 로 검색합니다.",
        )
      case Right(json) => {
        TxDetailMsg.Update(response.body)
      }

  private val onError: HttpError => Msg = e => ApiMsg.GetError(e.toString)

  def fromHttpResponse: Decoder[Msg] =
    Decoder[Msg](onResponse, onError)

object OnHandleTxDetailMsg:
  def getTxDetail(hash: String): Cmd[IO, Msg] =
    val url =
      s"http://localhost:8081/tx/${hash}/detail"
    Http.send(Request.get(url), UnderTxDetailMsg.fromHttpResponse)
