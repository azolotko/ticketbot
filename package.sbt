enablePlugins(JavaServerAppPackaging, DebianPlugin, SystemdPlugin)

maintainer := "Alex Zolotko <alex.zolotko@observatory.one>"

packageSummary := "ticketbot"

defaultLinuxInstallLocation := "/srv"

mappings in Universal ++= {
  val res = (resourceDirectory in Compile).value
  Seq(res / "application.conf" -> "conf/application.conf",
      res / "logger.xml" -> "conf/logger.xml")
}

debianPackageDependencies in Debian ++= Seq("java10-sdk-headless",
                                            "chromium-browser")
