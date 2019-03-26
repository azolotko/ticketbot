package me.zolotko.ticketbot

import me.zolotko.ticketbot.TicketBot.{OnSale, SoldOut, TicketingProductStatus}
import slick.jdbc.H2Profile.api._

package object database {
  implicit val ticketingProductStatusColumnType
  : ColumnType[TicketingProductStatus] =
    MappedColumnType.base[TicketingProductStatus, String]({
      case OnSale  => "on_sale"
      case SoldOut => "sold_out"
    }, {
      case "on_sale"  => OnSale
      case "sold_out" => SoldOut
    })

}
