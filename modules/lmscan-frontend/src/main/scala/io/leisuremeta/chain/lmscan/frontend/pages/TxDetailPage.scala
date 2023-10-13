package io.leisuremeta.chain.lmscan
package frontend

import tyrian.*
import cats.effect.IO
import tyrian.Html.*
import common.model.TxDetail

case class TxDetailPage(hash: String) extends Page:
  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = _ =>
    (model, Cmd.Emit(UpdateTxDetailPage(hash)))

  def view(model: Model): Html[Msg] =
    DefaultLayout.view(
      model,
      div(`class` := "pb-32px")(
        div(
          `class` := "font-40px pt-16px font-block-detail pb-16px color-white",
        )(
          "Transaction details",
        ) :: TxDetailTableMain.view(model.txDetail) :: TxDetailTableCommon.view(
          model.txDetail,
        ),
      ),
    )

  def url = s"/tx/$hash"
