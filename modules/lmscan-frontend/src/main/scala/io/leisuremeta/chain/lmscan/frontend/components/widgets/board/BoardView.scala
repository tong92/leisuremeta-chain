package io.leisuremeta.chain.lmscan.frontend
import tyrian.Html.*
import tyrian.*
import V.*
import io.leisuremeta.chain.lmscan.common.model.SummaryModel
import io.leisuremeta.chain.lmscan.frontend.ModelPipe.*
import io.leisuremeta.chain.lmscan.frontend.Log.log
import Dom.*
import scala.util.chaining.*
import org.scalajs.dom
import org.scalajs.dom.HTMLElement
import io.leisuremeta.chain.lmscan.common.model.BlockInfo
import io.leisuremeta.chain.lmscan.frontend.Log.log2

object Board:
  val LM_Price = "LM PRICE"
  // val Block_Number = "BLOCK NUMBER"
  val Total_TxCount = "TOTAL TRANSACTIONS"
  val Transactions  = "TOTAL BALANCE"
  // val Transactions = "TOTAL DATA SIZE"
  val Accounts = "TOTAL ACCOUNTS"

object BoardView:
  def txValue(data: Option[String]) =
    val res = String
      .format(
        "%.2f",
        (getOptionValue(data, "0.0")
          .asInstanceOf[String]
          .toDouble / Math.pow(10, 18).toDouble),
      )
    val sosu         = res.takeRight(5)
    val decimal      = res.replace(sosu, "")
    val commaDecimal = String.format("%,d", decimal.toDouble)

    res == "0.0000" match
      case true =>
        "-"
      case false => commaDecimal + sosu

  def parseToNumber(strNum: String) =
    strNum.length() > 18 match
      case true =>
        f"${BigDecimal(strNum) / Math.pow(10, 18)}%,.3f"
      case false => String.format("%.0f", strNum.toDouble)

  def addComma(numberString: String) =
    numberString match
      case "-" => "-"
      case _   => f"${BigInt(numberString)}%,d"

  def view(model: Model): Html[Msg] =
    val data = get_PageResponseViewCase(model).board

    div(`class` := "board-area")(
      div(`class` := "board-list x ")(
        div(`class` := "board-container xy-center position-relative  ")(
          div(
            `class` := "board-text y-center gap-10px",
          )(
            div(`class` := "font-16px color-white font-bold")(
              Board.LM_Price,
            ),
            div(`class` := "color-white font-bold")(
              plainStr(data.lmPrice).take(6) + " USDT",
            ),
          ), {
            data != new SummaryModel match
              case false =>
                LoaderView.view(model)

              case _ => div()
          },
        ),
        div(`class` := "board-container xy-center position-relative  ")(
          div(
            `class` := "board-text y-center gap-10px",
          )(
            div(`class` := "font-16px color-white font-bold")(
              Board.Total_TxCount,
            ),
            div(`class` := "color-white font-bold")(
              // plainStr(
              //   current_ViewCase(model).blockInfo(0).number,
              // ).pipe(addComma),
              plainLong(data.totalTxCount).pipe(addComma),
            ),
          ), {
            current_ViewCase(model).blockInfo(0) != new BlockInfo match
              case false =>
                LoaderView.view(model)
              case _ => div()
          },
        ),
      ),
      div(`class` := "board-list x")(
        div(`class` := "board-container xy-center position-relative  ")(
          div(
            `class` := "board-text y-center gap-10px",
          )(
            div(`class` := "font-16px color-white font-bold")(
              Board.Transactions,
            ),
            div(`class` := "color-white font-bold")(
              parseToNumber(
                data.total_balance
                  .map(_.toString)
                  .getOrElse("0"),
              ),
            ),
          ), {
            data != new SummaryModel match
              case false =>
                LoaderView.view(model)
              case _ => div()
          },
        ),
        div(`class` := "board-container xy-center position-relative  ")(
          div(
            `class` := "board-text y-center gap-10px",
          )(
            div(`class` := "font-16px color-white font-bold")(
              Board.Accounts,
            ),
            div(`class` := "color-white font-bold")(
              plainStr(data.totalAccounts)
                .pipe(addComma),
            ),
          ), {
            data != new SummaryModel match
              case false =>
                LoaderView.view(model)
              case _ => div()
          },
        ),
      ),
    )
