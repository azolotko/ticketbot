package me.zolotko.ticketbot.config

case class NotificationConfig(
    to: Seq[String],
    from: String,
    smsTopicArn: String
)
