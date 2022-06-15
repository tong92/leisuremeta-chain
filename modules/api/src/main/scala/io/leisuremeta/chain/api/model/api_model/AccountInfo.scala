package io.leisuremeta.chain
package api.model
package api_model

import lib.datatype.Utf8

final case class AccountInfo(
    ethAddress: Option[Utf8],
    guardian: Option[Account],
    publicKeySummaries: Map[PublicKeySummary, PublicKeySummary.Info],
)
