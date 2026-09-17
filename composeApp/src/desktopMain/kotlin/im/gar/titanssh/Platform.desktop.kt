package im.gar.titanssh

actual fun platformName(): String =
    "Desktop (${System.getProperty("os.name")} ${System.getProperty("os.version")})"
