package io.leisuremeta.chain.lmscan
package frontend
package chart

import tyrian.Html.*
import tyrian.*
import common.model.SummaryModel

object BalChart {
  def view(model: Model): Html[Msg] =
    renderDataChart(model.chartData)
    canvas(
  
      height := "600px",
      id := "chart",
    )("")

  def renderDataChart(data: SummaryChart): Unit=
    import typings.chartJs.mod.*
    data.list match
      case List() => ()
      case list =>
        val gData = list.map(_.total_balance.getOrElse(BigDecimal(0))).map(a => a / BigDecimal("1e+18")).map(_.toDouble)
        val label = list.map(_.createdAt.getOrElse(0)).map(_.toString)
        val chart = Chart.apply.newInstance2("chart", ChartConfig.config(label, gData, "balance"))
}
