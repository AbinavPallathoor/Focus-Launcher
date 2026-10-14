package com.focuslauncher.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focuslauncher.app.ui.FocusLauncherTheme
import com.focuslauncher.app.ui.HomeScreen
import com.focuslauncher.app.ui.SearchScreen
import com.focuslauncher.app.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideStatusBar()
        setContent {
            FocusLauncherTheme {
                LauncherRoot()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        // Belt-and-suspenders alongside the no-animation window style in themes.xml —
        // returning to the home screen should feel like the OS surfacing again, not like
        // an app opening, even on OEM skins that don't fully honor the theme attribute.
        overridePendingTransition(0, 0)
    }

    private fun hideStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

private sealed class Screen {
    data object Home : Screen()
    data object Settings : Screen()
    data class Search(
        val onPicked: ((AppInfo) -> Unit)? = null,
        val returnTo: Screen = Home,
    ) : Screen()
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun LauncherRoot() {
    val context = LocalContext.current
    // allApps is unfiltered (label overrides applied) so dial/gesture assignments keep
    // resolving even if the app gets hidden later; visibleApps/hiddenAppsList derive from it.
    // Querying + label-loading every installed app is the slowest thing the launcher does, so
    // it never runs on the main thread — the home screen draws immediately and the list fills in.
    var allApps by remember { mutableStateOf(emptyList<AppInfo>()) }
    var hiddenKeys by remember { mutableStateOf(HiddenAppsStore.getHiddenKeys(context)) }
    val visibleApps = remember(allApps, hiddenKeys) { allApps.filter { it.key !in hiddenKeys } }
    val hiddenAppsList = remember(allApps, hiddenKeys) { allApps.filter { it.key in hiddenKeys } }
    var radialGroups by remember {
        mutableStateOf(RADIAL_GROUPS.associateWith { group -> RadialMenuStore.getSlotKeys(context, group) })
    }
    var dialVerticalOffsetDp by remember { mutableStateOf(RadialMenuStore.getVerticalOffsetDp(context)) }
    var gestureApps by remember {
        mutableStateOf(
            mapOf(
                SwipeGesture.LEFT to GestureAppStore.getAppKey(context, SwipeGesture.LEFT),
                SwipeGesture.RIGHT to GestureAppStore.getAppKey(context, SwipeGesture.RIGHT),
            )
        )
    }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    // Set when the launcher resets itself back to Home while hidden, so pressing Home lands
    // on the finished home screen instead of watching a crossfade play out.
    // Plain (non-snapshot) flag: it's consumed inside transitionSpec, which mustn't trigger recomposition.
    val skipNextTransition = remember { AtomicBoolean(false) }
    val coroutineScope = rememberCoroutineScope()

    fun refreshApps() {
        coroutineScope.launch {
            allApps = withContext(Dispatchers.Default) { AppRepository.getInstalledApps(context) }
        }
    }

    fun setDialVerticalOffset(offsetDp: Float) {
        RadialMenuStore.setVerticalOffsetDp(context, offsetDp)
        dialVerticalOffsetDp = RadialMenuStore.getVerticalOffsetDp(context)
    }

    fun refreshHidden() {
        hiddenKeys = HiddenAppsStore.getHiddenKeys(context)
    }

    fun refreshRadialGroups() {
        radialGroups = RADIAL_GROUPS.associateWith { group -> RadialMenuStore.getSlotKeys(context, group) }
    }

    fun refreshGestureApps() {
        gestureApps = mapOf(
            SwipeGesture.LEFT to GestureAppStore.getAppKey(context, SwipeGesture.LEFT),
            SwipeGesture.RIGHT to GestureAppStore.getAppKey(context, SwipeGesture.RIGHT),
        )
    }

    fun resolveGroupApps(group: Int): List<AppInfo> =
        radialGroups[group].orEmpty().mapNotNull { key -> key?.let { AppRepository.findByKey(allApps, it) } }

    fun resolveGestureApp(gesture: SwipeGesture): AppInfo? =
        gestureApps[gesture]?.let { key -> AppRepository.findByKey(allApps, key) }

    LaunchedEffect(Unit) { refreshApps() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Reset while we're off-screen rather than on resume, so the switch back to
                // Home has already happened by the time the launcher is visible again.
                Lifecycle.Event.ON_STOP -> if (screen != Screen.Home) {
                    skipNextTransition.set(true)
                    screen = Screen.Home
                }
                Lifecycle.Event.ON_RESUME -> {
                    refreshApps()
                    refreshRadialGroups()
                    refreshGestureApps()
                    if (screen != Screen.Home) {
                        skipNextTransition.set(true)
                        screen = Screen.Home
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        val receiver = PackageChangeReceiver.register(context) {
            refreshApps()
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    AnimatedContent(
        targetState = screen,
        // A quick crossfade rather than a sliding "sheet" — screens should read as the
        // same persistent OS surface changing state, not as separate apps opening on top.
        transitionSpec = {
            if (skipNextTransition.getAndSet(false)) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(tween(140)) togetherWith fadeOut(tween(140))
            }
        },
        label = "screenTransition"
    ) { currentScreen ->
        when (currentScreen) {
            is Screen.Home -> {
                // A launcher's home screen shouldn't be dismissible with back.
                BackHandler(enabled = true) {}
                HomeScreen(
                    bottomDialApps = resolveGroupApps(RADIAL_GROUP_BOTTOM),
                    upperDialApps = resolveGroupApps(RADIAL_GROUP_UPPER),
                    onLaunch = { AppRepository.launchApp(context, it) },
                    onOpenSearch = { screen = Screen.Search(returnTo = Screen.Home) },
                    onOpenSettings = { screen = Screen.Settings },
                    onSwipeLeft = {
                        val override = resolveGestureApp(SwipeGesture.LEFT)
                        if (override != null) AppRepository.launchApp(context, override) else AppRepository.launchCamera(context)
                    },
                    onSwipeRight = {
                        val override = resolveGestureApp(SwipeGesture.RIGHT)
                        if (override != null) AppRepository.launchApp(context, override) else AppRepository.launchContacts(context)
                    },
                    onSwipeDown = { AppRepository.expandNotifications(context) },
                    dialVerticalOffset = dialVerticalOffsetDp.dp,
                )
            }
            is Screen.Settings -> {
                SettingsScreen(
                    resolveSlot = { group, index ->
                        radialGroups[group]?.getOrNull(index)?.let { key -> AppRepository.findByKey(allApps, key) }
                    },
                    onPickSlot = { group, index ->
                        screen = Screen.Search(
                            onPicked = { app ->
                                RadialMenuStore.setSlot(context, group, index, app.key)
                                refreshRadialGroups()
                            },
                            returnTo = Screen.Settings,
                        )
                    },
                    onClearSlot = { group, index ->
                        RadialMenuStore.setSlot(context, group, index, null)
                        refreshRadialGroups()
                    },
                    resolveGesture = { gesture -> resolveGestureApp(gesture) },
                    onPickGesture = { gesture ->
                        screen = Screen.Search(
                            onPicked = { app ->
                                GestureAppStore.setAppKey(context, gesture, app.key)
                                refreshGestureApps()
                            },
                            returnTo = Screen.Settings,
                        )
                    },
                    onClearGesture = { gesture ->
                        GestureAppStore.setAppKey(context, gesture, null)
                        refreshGestureApps()
                    },
                    hiddenApps = hiddenAppsList,
                    dialVerticalOffsetDp = dialVerticalOffsetDp,
                    onDialVerticalOffsetChange = { offsetDp -> setDialVerticalOffset(offsetDp) },
                    onUnhideApp = { app ->
                        HiddenAppsStore.setHidden(context, app.key, false)
                        refreshHidden()
                    },
                    onClose = { screen = Screen.Home },
                )
            }
            is Screen.Search -> {
                SearchScreen(
                    apps = visibleApps,
                    onLaunch = { AppRepository.launchApp(context, it) },
                    onClose = { screen = currentScreen.returnTo },
                    onPick = currentScreen.onPicked,
                    onUninstallApp = { app -> AppRepository.requestUninstall(context, app) },
                    onRenameApp = { app, newLabel ->
                        AppLabelStore.setCustomLabel(context, app.key, newLabel)
                        refreshApps()
                    },
                    onHideApp = { app ->
                        HiddenAppsStore.setHidden(context, app.key, true)
                        refreshHidden()
                    },
                    onCloseApp = { app -> AppRepository.closeApp(context, app) },
                )
            }
        }
    }
}
