package me.zolotko.ticketbot.config

import java.nio.file.Path

case class ChromeConfig(
    binaryPath: Path,
    userDataPath: Path,
    debuggingAddress: String,
    debuggingPort: Int
)
