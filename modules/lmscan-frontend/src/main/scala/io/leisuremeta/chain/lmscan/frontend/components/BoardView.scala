package io.leisuremeta.chain.lmscan.frontend
import tyrian.Html.*
import tyrian.*
import V.*
import io.leisuremeta.chain.lmscan.common.model.SummaryModel
import org.scalajs.dom.HTMLElement

object BoardView:
  val LM_Price = "LM PRICE"
  val Total_TxCount = "TOTAL TRANSACTIONS"
  val Transactions  = "TOTAL BALANCE"
  val Accounts = "TOTAL ACCOUNTS"

  def parseToNumber(strNum: String) =
    strNum.length > 18 match
      case true =>
        f"${BigDecimal(strNum) / Math.pow(10, 18)}%,.3f"
      case false => String.format("%.0f", strNum.toDouble)

  def addComma(numberString: Long) = f"${BigInt(numberString)}%,d"

  def drawPrice(opt: Option[Double]): Html[Msg] = opt match
    case None => LoaderView.view
    case Some(v) => drawContent(v.toString.take(6) + " USDT")

  def drawTotal(opt: Option[Long]): Html[Msg] = opt match
    case None => LoaderView.view
    case Some(v) => drawContent(addComma(v))

  def drawBalance(opt: Option[String]): Html[Msg] = opt match
    case None => LoaderView.view
    case Some(v) => drawContent(parseToNumber(v))

  def drawAccounts(opt: Option[Long]): Html[Msg] = opt match
    case None => LoaderView.view
    case Some(v) => drawContent(addComma(v))

  def drawContent(s: String) = div(`class` := "color-white font-bold")(s)

  def drawBox(title: String, content: Html[Msg]): Html[Msg] =
    div(`class` := "board-container xy-center position-relative")(
      div(
        `class` := "board-text y-center gap-10px",
      )(
        div(`class` := "font-16px color-white font-bold")(title),
        content,
      ),
    )

  def view(model: Model): Html[Msg] =
    val summary = model.summary

    div(`class` := "board-area")(
      List(
        (LM_Price, drawPrice(summary.lmPrice)),
        (Total_TxCount, drawTotal(summary.totalTxCount)),
        (Transactions, drawBalance(summary.total_balance)),
        (Accounts, drawAccounts(summary.totalAccounts)),
      ).map(drawBox)
    )
