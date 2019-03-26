package me.zolotko.ticketbot

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.Message
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import me.zolotko.chrome.devtools.ChromeActionsInterpreter
import me.zolotko.chrome.devtools.protocol.DOM
import me.zolotko.chrome.{ChromeProcess, _}
import me.zolotko.ticketbot.config.{ApplicationConfig, NotificationConfig}
import me.zolotko.ticketbot.database.TicketingProductsTable
import slick.basic.DatabaseConfig
import slick.jdbc.{H2Profile, JdbcBackend}
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model._
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import slick.jdbc.H2Profile.api._

import scala.concurrent.duration._

object TicketBot extends StrictLogging {
  sealed trait TicketingProductStatus
  object OnSale extends TicketingProductStatus
  object SoldOut extends TicketingProductStatus

  val ticketsURL =
    "https://tickets.liverpoolfc.com/PagesPublic/ProductBrowse/productHome.aspx?ProductSubType=HOME"

  case class TicketingProduct(title: String, status: TicketingProductStatus)

  def main(args: Array[String]): Unit = {
    val config = ApplicationConfig.loadOrExit()

    implicit val system: ActorSystem = ActorSystem("ticketbot-actor-system")
    implicit val mat: ActorMaterializer = ActorMaterializer()
    import mat.executionContext

    val chromeMessagesFlow = Flow[Message]
    val interpreterMessagesFlow = Flow[Message]
    chromeMessagesFlow.join(interpreterMessagesFlow).run()

    val dbConfig = DatabaseConfig.forConfig[H2Profile]("database")
    val db = dbConfig.db
    db.run(TicketingProductsTable.table.schema.create) andThen {
      case _ =>
        mat.schedulePeriodically(5.seconds, 1.minute, () => check(config, db))
    }

    system.registerOnTermination {
      db.close()
      val _ = system.terminate()
    }
  }

  def check(config: ApplicationConfig, db: JdbcBackend#DatabaseDef)(
      implicit mat: ActorMaterializer): Future[Unit] = {
    implicit val system: ActorSystem = mat.system
    import mat.executionContext

    val ChromeProcess.Started(exitCode, destroyChromeProcess, queues) =
      ChromeProcess(
        config.chrome.binaryPath,
        config.chrome.userDataPath,
        None,
        InetSocketAddress.createUnresolved(config.chrome.debuggingAddress,
                                           config.chrome.debuggingPort)
      )

    exitCode andThen {
      case Success(0) =>
        logger.debug("chrome process exited with code 0")
      case Success(code) =>
        logger.warn("chrome process exited with code {}", code)
      case Failure(e) =>
        logger.error("could not start chrome process:", e)
    }

    type Screenshot = String

    val checkResult =
      queues.flatMap {
        case (messagesSinkQueue, messagesSourceQueue, messageIdGenerator) =>
          val actions = for {
            _ <- enable()

            frameId <- navigate(ticketsURL)

            _ <- waitForFrameStoppedLoading(frameId)

            rootNodeId <- getDocument(None, None)

            ticketingProductsNodeId <- querySelector(
              rootNodeId,
              DOM.Selector("#ticketing-products"))

            ticketingProductsNode <- describeNode(ticketingProductsNodeId.get,
                                                  Some(10))

            ticketingProducts = ticketingProductsFromNode(ticketingProductsNode)

            _ = println(ticketingProducts)

            screenshot <- captureScreenshot("png")
          } yield ticketingProducts -> screenshot

          val chromeActionsInterpreter =
            ChromeActionsInterpreter(messagesSinkQueue,
                                     messagesSourceQueue,
                                     messageIdGenerator)
          actions
            .foldMap(chromeActionsInterpreter)
      }

    checkResult andThen {
      case _ => destroyChromeProcess()
    }

    checkResult
      .flatMap {
        case (ticketingProducts, screenshot) =>
          logger.debug("tickets status check completed: {}", ticketingProducts)
          //          notify(status, screenshot)

          Future.sequence {
            ticketingProducts.map { product =>
              db.run(TicketingProductsTable.status(product.title).result)
                .map(result => product -> result.headOption.getOrElse(SoldOut))
            }
          } flatMap { productsWithStatuses =>
            Future.sequence {
              productsWithStatuses.collect {
                case (product, prevStatus) if product.status != prevStatus =>
                  product
              } map { product =>
                notify(product, screenshot, config.notification)
              }
            } flatMap { _ =>
              val updateProducts = DBIO.sequence {
                productsWithStatuses.map {
                  case (product, _) =>
                    TicketingProductsTable.updateStatus(product.title,
                                                        product.status)
                }
              }

              db.run(updateProducts.transactionally)
            }
          }
      } andThen {
      case Success(_) =>
        logger.info(s"tickets status notification succeeded")

      case Failure(e) =>
        logger.error(s"tickets status notification failed", e)
    } map { _ =>
      ()
    }
  }

  def ticketingProductsFromNode(
      ticketingProductsNode: Option[DOM.Node]): Seq[TicketingProduct] =
    ticketingProductsNode match {
      case Some(node) =>
        val productNodes =
          node.children.filter(hasCssClass(_, "ebiz-product-type-H"))

        productNodes.flatMap { productNode =>
          val state =
            if (hasCssClass(productNode, "ui-state-soldOut"))
              SoldOut
            else
              OnSale

          for {
            productANode <- productNode.children.find(_.nodeName == "A")

            productHeaderNode <- productANode.children.find(
              hasCssClass(_, "ebiz-product-header"))

            titleH2Node <- productHeaderNode.children.find(_.nodeName == "H2")

            titleTextNode <- titleH2Node.children.find(_.nodeName == "#text")
          } yield TicketingProduct(titleTextNode.nodeValue, state)
        }

      case None =>
        Seq.empty
    }

  def hasCssClass(node: DOM.Node, cssClass: String): Boolean =
    node.attributes.getOrElse("class", "").split(" ").contains(cssClass)

  def notify(product: TicketingProduct,
             screenshot: String,
             config: NotificationConfig)(
      implicit ec: ExecutionContext): Future[Unit] = {
    logger.debug("product status changed: {}", product)

    val subjectText = product.status match {
      case OnSale =>
        s"Status of ${product.title} tickets: ON SALE"

      case SoldOut =>
        s"Status of ${product.title} tickets: SOLD OUT"
    }

    val htmlContent = product.status match {
      case OnSale =>
        s"""
          |<h1>${product.title} tickets went ON SALE</h1>
          |<a href="$ticketsURL">
          |  Navigate to the page
          |</a>
        """.stripMargin

      case SoldOut =>
        s"""
           |<h1>${product.title} tickets have been SOLD OUT</h1>
           |<a href="$ticketsURL">
           |  Navigate to the page
           |</a>
        """.stripMargin
    }

    val subject = Content.builder().data(subjectText).charset("UTF-8").build()
    val body = Body
      .builder()
      .html(Content.builder().data(htmlContent).charset("UTF-8").build())
      .build()

    val destination = Destination.builder().toAddresses(config.to: _*).build()

    val sendEmailRequest = SendEmailRequest
      .builder()
      .source(config.from)
      .destination(destination)
      .message(
        Message
          .builder()
          .subject(subject)
          .body(body)
          .build())
      .build()

    val sesClient = SesClient.builder().build()
    val snsClient = SnsClient.builder().build()

    val smsMessageText = product.status match {
      case OnSale =>
        s"${product.title} tickets went ON SALE: $ticketsURL"

      case SoldOut =>
        s"${product.title} tickets have been SOLD OUT: $ticketsURL"
    }

    val publishRequest = PublishRequest
      .builder()
      .message(smsMessageText)
      .topicArn(config.smsTopicArn)
      .build()

    Future
      .sequence(
        Seq(
          Future {
            val _ = sesClient.sendEmail(sendEmailRequest)
          } andThen {
            case _ => sesClient.close()
          },
          Future {
            val _ = snsClient.publish(publishRequest)
          } andThen {
            case _ => snsClient.close()
          }
        ))
      .map(_ => ())
  }
}
