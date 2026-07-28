package com.example.rjlmulticomsg_proclientportal.ui.navigation

sealed class AppRoute(val title: String) {
    data object Login : AppRoute("Login")
    data object ModuleOnboarding : AppRoute("Modules")
    data object Home : AppRoute("Home")
    data object Logs : AppRoute("Logs")
    data object People : AppRoute("People")
    data object Settings : AppRoute("Settings")
    data object Schedules : AppRoute("Schedules")
    data object WifiModule : AppRoute("Wi‑Fi")
    data object GsmModule : AppRoute("GSM")
    data object RfidModule : AppRoute("RFID")
    data object LprModule : AppRoute("LPR")
    data object Help : AppRoute("Help")
    data object MagicKey : AppRoute("Magic Key Access")
    data object DeviceLocation : AppRoute("Device Location")
    data object Messages : AppRoute("Messages")
}

enum class MainTab {
    Home, Logs, Settings
}
