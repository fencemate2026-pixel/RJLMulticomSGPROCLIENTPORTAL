package com.example.rjlmulticomsg_proclientportal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.rjlmulticomsg_proclientportal.ClientPortalApp
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.security.AppLockState
import com.example.rjlmulticomsg_proclientportal.ui.help.HelpAssistantScreen
import com.example.rjlmulticomsg_proclientportal.ui.help.FloatingAssistantHost
import com.example.rjlmulticomsg_proclientportal.ui.home.HomeScreen
import com.example.rjlmulticomsg_proclientportal.ui.intro.VideoIntroScreen
import com.example.rjlmulticomsg_proclientportal.ui.login.LoginScreen
import com.example.rjlmulticomsg_proclientportal.ui.login.PinGateScreen
import com.example.rjlmulticomsg_proclientportal.ui.logs.ActionLogScreen
import com.example.rjlmulticomsg_proclientportal.ui.location.DeviceLocationScreen
import com.example.rjlmulticomsg_proclientportal.ui.messages.MessagesScreen
import com.example.rjlmulticomsg_proclientportal.ui.magickey.MagicKeyScreen
import com.example.rjlmulticomsg_proclientportal.ui.modules.GsmModuleScreen
import com.example.rjlmulticomsg_proclientportal.ui.modules.LprModuleScreen
import com.example.rjlmulticomsg_proclientportal.ui.modules.RfidModuleScreen
import com.example.rjlmulticomsg_proclientportal.ui.modules.WifiModuleScreen
import com.example.rjlmulticomsg_proclientportal.ui.navigation.AppRoute
import com.example.rjlmulticomsg_proclientportal.ui.navigation.MainTab
import com.example.rjlmulticomsg_proclientportal.ui.onboarding.ClientOnboardingScreen
import com.example.rjlmulticomsg_proclientportal.ui.people.PeopleScreen
import com.example.rjlmulticomsg_proclientportal.ui.schedules.SchedulesScreen
import com.example.rjlmulticomsg_proclientportal.ui.settings.SettingsScreen
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun AppRoot(repository: PortalRepository) {
    val session by repository.session.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val lockManager = remember(context.applicationContext) {
        (context.applicationContext as ClientPortalApp).appLockManager
    }
    var bootstrapped by remember { mutableStateOf(false) }
    var showIntro by remember { mutableStateOf(true) }
    // Stack so phone back returns to the real previous page (not exit).
    var backStack by remember { mutableStateOf(listOf<AppRoute>(AppRoute.Login)) }
    var tab by remember { mutableStateOf(MainTab.Home) }

    val route = backStack.last()

    fun syncTab(destination: AppRoute) {
        tab = when (destination) {
            AppRoute.Logs -> MainTab.Logs
            AppRoute.Settings -> MainTab.Settings
            else -> MainTab.Home
        }
    }

    /** Push a page onto the stack (detail screens, modules, people, etc.). */
    fun navigateTo(destination: AppRoute) {
        if (destination == route) return
        // Keep a single contiguous trail; drop if re-opening the same destination mid-stack.
        val trimmed = backStack.toMutableList()
        val existing = trimmed.indexOfLast { it == destination }
        if (existing >= 0) {
            while (trimmed.lastIndex > existing) trimmed.removeAt(trimmed.lastIndex)
            backStack = trimmed
        } else {
            backStack = trimmed + destination
        }
        syncTab(destination)
    }

    /**
     * Bottom-bar tabs: keep Home under Logs/Settings so system back returns to Home
     * instead of leaving the app.
     */
    fun selectTab(destination: AppRoute) {
        backStack = when (destination) {
            AppRoute.Home -> listOf(AppRoute.Home)
            AppRoute.Logs, AppRoute.Settings -> listOf(AppRoute.Home, destination)
            else -> listOf(destination)
        }
        syncTab(destination)
    }

    fun goBack(): Boolean {
        if (backStack.size <= 1) return false
        val next = backStack.dropLast(1)
        backStack = next
        syncTab(next.last())
        return true
    }

    fun resetTo(destination: AppRoute) {
        backStack = listOf(destination)
        syncTab(destination)
    }

    fun popOrHome() {
        if (!goBack()) resetTo(AppRoute.Home)
    }

    /**
     * True whenever the phone back key should stay inside the app.
     * Only Login / bare Home / first onboarding step allow the system to leave.
     */
    val interceptSystemBack = when {
        showIntro -> true
        backStack.size > 1 -> true
        route == AppRoute.Login -> false
        route == AppRoute.Home -> false
        route == AppRoute.ModuleOnboarding -> false
        // Any other single-page destination (shouldn't happen often) → go Home, don't exit
        else -> true
    }

    // Register BEFORE early returns so loading / intro still consume back correctly.
    BackHandler(enabled = interceptSystemBack) {
        when {
            showIntro -> showIntro = false
            goBack() -> Unit
            else -> resetTo(AppRoute.Home)
        }
    }

    LaunchedEffect(Unit) {
        repository.bootstrap()
        bootstrapped = true
    }

    DisposableEffect(lifecycleOwner, lockManager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> lockManager.onForegrounded()
                Lifecycle.Event.ON_STOP -> lockManager.onBackgrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(session.user?.id, session.isLoading, bootstrapped) {
        if (!bootstrapped || session.isLoading) return@LaunchedEffect
        val userId = session.user?.id
        if (session.isLoggedIn && userId != null) {
            lockManager.onSessionAvailable(userId)
        } else {
            lockManager.onSignedOut(clearPin = true)
        }
    }

    LaunchedEffect(session.isLoggedIn, session.needsOnboarding, session.isLoading, bootstrapped) {
        if (!bootstrapped || session.isLoading) return@LaunchedEffect
        when {
            !session.isLoggedIn -> {
                if (route != AppRoute.Login) resetTo(AppRoute.Login)
            }
            session.needsOnboarding -> {
                if (route != AppRoute.ModuleOnboarding) resetTo(AppRoute.ModuleOnboarding)
            }
            route == AppRoute.Login || route == AppRoute.ModuleOnboarding -> {
                resetTo(AppRoute.Home)
            }
        }
        if (!session.isLoggedIn) tab = MainTab.Home
    }

    // Keep the Room mirror and controller acknowledgement state fresh while
    // the portal is open. Device-side application remains independently
    // bounded by the firmware's 60-second whitelist poll.
    LaunchedEffect(session.isLoggedIn, session.account?.id) {
        if (!session.isLoggedIn) return@LaunchedEffect
        while (true) {
            repository.syncGsmFromCloud(session.account?.id)
            delay(15_000L)
        }
    }

    if (showIntro) {
        VideoIntroScreen(onFinished = { showIntro = false })
        return
    }

    if (!bootstrapped || session.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MulticomRed)
        }
        return
    }

    if (session.isLoggedIn && lockManager.state.value != AppLockState.UNLOCKED) {
        val publicSession = session.copy(
            user = null,
            account = null,
            modules = emptyList(),
            sites = emptyList()
        )
        FloatingAssistantHost(
            routeTitle = "PIN",
            session = publicSession,
            bottomBarVisible = false
        ) {
            PinGateScreen(
                lockManager = lockManager,
                displayName = session.user?.displayName.orEmpty(),
                onRequireLogin = {
                    scope.launch {
                        lockManager.onSignedOut(clearPin = true)
                        repository.logout()
                        resetTo(AppRoute.Login)
                    }
                }
            )
        }
        return
    }

    when (val r = route) {
        AppRoute.Login -> FloatingAssistantHost(
            routeTitle = r.title,
            session = session,
            bottomBarVisible = false
        ) {
            LoginScreen(repository) {
                // Real sign-in succeeded — session LaunchedEffect moves to Home / onboarding.
            }
        }
        AppRoute.ModuleOnboarding -> FloatingAssistantHost(
            routeTitle = r.title,
            session = session,
            bottomBarVisible = false
        ) {
            ClientOnboardingScreen(
                repository = repository,
                session = session
            ) {
                resetTo(AppRoute.Home)
            }
        }
        else -> {
            val showBottomBar = r == AppRoute.Home ||
                r == AppRoute.Logs ||
                r == AppRoute.Settings

            FloatingAssistantHost(
                routeTitle = r.title,
                session = session,
                bottomBarVisible = showBottomBar
            ) {
                Scaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            MainBottomBar(
                                selected = tab,
                                onSelect = { selected ->
                                    selectTab(
                                        when (selected) {
                                            MainTab.Home -> AppRoute.Home
                                            MainTab.Logs -> AppRoute.Logs
                                            MainTab.Settings -> AppRoute.Settings
                                        }
                                    )
                                }
                            )
                        }
                    }
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        when (r) {
                            AppRoute.Home -> HomeScreen(
                                repository = repository,
                                session = session,
                                onOpenSchedules = { navigateTo(AppRoute.Schedules) },
                                onOpenLogs = { selectTab(AppRoute.Logs) },
                                onOpenPeople = { navigateTo(AppRoute.People) },
                                onOpenWifi = { navigateTo(AppRoute.WifiModule) },
                                onOpenGsm = { navigateTo(AppRoute.GsmModule) },
                                onOpenRfid = { navigateTo(AppRoute.RfidModule) },
                                onOpenLpr = { navigateTo(AppRoute.LprModule) },
                                onOpenSettings = { selectTab(AppRoute.Settings) },
                                onOpenHelp = { navigateTo(AppRoute.Help) },
                                onOpenMagicKey = { navigateTo(AppRoute.MagicKey) },
                                onOpenDeviceLocation = {
                                    navigateTo(AppRoute.DeviceLocation)
                                },
                                onOpenMessages = { navigateTo(AppRoute.Messages) }
                            )
                            AppRoute.Logs -> ActionLogScreen(
                                repository = repository,
                                accountId = session.account?.id.orEmpty(),
                                onBack = { popOrHome() }
                            )
                            AppRoute.People -> PeopleScreen(
                                repository = repository,
                                session = session,
                                onBack = { popOrHome() }
                            )
                            AppRoute.Settings -> SettingsScreen(
                                repository = repository,
                                session = session,
                                onSignedOut = {
                                    lockManager.onSignedOut(clearPin = true)
                                    resetTo(AppRoute.Login)
                                },
                                onModulesSaved = {
                                    if (!session.hasModuleSelection &&
                                        session.enabledModules.isEmpty()
                                    ) {
                                        // wait for state refresh
                                    }
                                },
                                onOpenHelp = { navigateTo(AppRoute.Help) }
                            )
                            AppRoute.Help -> HelpAssistantScreen(
                                onBack = { popOrHome() }
                            )
                            AppRoute.MagicKey -> MagicKeyScreen(onBack = { popOrHome() })
                            AppRoute.DeviceLocation -> DeviceLocationScreen(
                                repository = repository,
                                session = session,
                                onBack = { popOrHome() }
                            )
                            AppRoute.Messages -> MessagesScreen(
                                repository = repository,
                                session = session,
                                onBack = { popOrHome() }
                            )
                            AppRoute.Schedules -> SchedulesScreen(
                                repository = repository,
                                accountId = session.account?.id.orEmpty(),
                                onBack = { popOrHome() }
                            )
                            AppRoute.WifiModule -> WifiModuleScreen(
                                repository = repository,
                                session = session,
                                onBack = { popOrHome() },
                                onSchedules = { navigateTo(AppRoute.Schedules) }
                            )
                            AppRoute.GsmModule -> GsmModuleScreen(
                                repository = repository,
                                session = session,
                                onBack = { popOrHome() },
                                onSchedules = { navigateTo(AppRoute.Schedules) }
                            )
                            AppRoute.RfidModule -> RfidModuleScreen(
                                repository = repository,
                                accountId = session.account?.id.orEmpty(),
                                onBack = { popOrHome() }
                            )
                            AppRoute.LprModule -> LprModuleScreen(
                                repository = repository,
                                accountId = session.account?.id.orEmpty(),
                                onBack = { popOrHome() }
                            )
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainBottomBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    data class Item(val tab: MainTab, val label: String, val icon: ImageVector)
    val items = listOf(
        Item(MainTab.Home, "Home", Icons.Default.Home),
        Item(MainTab.Logs, "Status", Icons.AutoMirrored.Filled.List),
        Item(MainTab.Settings, "Settings", Icons.Default.Settings)
    )
    NavigationBar(containerColor = androidx.compose.ui.graphics.Color.White) {
        items.forEach { item ->
            NavigationBarItem(
                selected = selected == item.tab,
                onClick = { onSelect(item.tab) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MulticomRed,
                    selectedTextColor = MulticomRed,
                    indicatorColor = MulticomRed.copy(alpha = 0.12f)
                )
            )
        }
    }
}
