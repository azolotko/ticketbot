package me.zolotko.ticketbot.database

import me.zolotko.ticketbot.TicketBot.TicketingProductStatus
import slick.dbio.Effect
import slick.jdbc.H2Profile.api._
import slick.lifted.ProvenShape
import slick.sql.FixedSqlAction

class TicketingProductsTable(tag: Tag)
    extends Table[(String, TicketingProductStatus)](tag, "TICKETING_PRODUCTS") {

  def title: Rep[String] =
    column[String]("TITLE", O.PrimaryKey)

  def status: Rep[TicketingProductStatus] =
    column[TicketingProductStatus]("STATUS")

  def * : ProvenShape[(String, TicketingProductStatus)] = (title, status)
}

object TicketingProductsTable {

  val table = TableQuery[TicketingProductsTable]

  def status(title: String)
    : Query[Rep[TicketingProductStatus], TicketingProductStatus, Seq] =
    for {
      product <- table if product.title === title
    } yield product.status

  def updateStatus(title: String, status: TicketingProductStatus)
    : FixedSqlAction[Int, NoStream, Effect.Write] =
    table.insertOrUpdate((title, status))
}
