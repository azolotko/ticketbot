package me.zolotko.ticketbot.config

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import pureconfig._
import pureconfig.generic.auto._

case class ApplicationConfig(chrome: ChromeConfig,
                             notification: NotificationConfig)

object ApplicationConfig extends StrictLogging {

  def loadOrExit(): ApplicationConfig = {
    val rawConfig = ConfigFactory.load()

    loadConfig[ApplicationConfig](rawConfig) match {
      case Left(errors) =>
        println("unable to load application configuration")
        println(errors)
        System.exit(-1)
        null

      case Right(appConfig) =>
        logger.info("loaded application configuration")
        appConfig
    }
  }
}
