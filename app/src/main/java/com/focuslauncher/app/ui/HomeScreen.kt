package com.focuslauncher.app.ui

import android.Manifest
import android.graphics.Rect
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.focuslauncher.app.AppInfo
import com.focuslauncher.app.CalendarRepository
import com.focuslauncher.app.DeviceUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val SWIPE_UP_OPEN_THRESHOLD_PX = -60f
private const val SWIPE_DOWN_OPEN_THRESHOLD_PX = 60f
private const val SWIPE_HORIZONTAL_THRESHOLD_PX = 90f
private const val SCREEN_TIME_POLL_MS = 60_000L
// The upper dial sweeps a 180° arc, so a downward-pointing node sits a full radius
// *below* its own anchor — this needs to clear the bottom dial's anchor/reveal area
// below it, or the two visually collide and the upper dial reads as "cut off".
private val UPPER_DIAL_BOTTOM_PADDING = 230.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    bottomDialApps: List<AppInfo>,
    upperDialApps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeDown: () -> Unit,
    dialVerticalOffset: Dp,
) {
    val context = LocalContext.current
    val time = rememberCurrentTime()
    val dateText = remember { SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date()) }
    var accumulatedDx by remember { mutableStateOf(0f) }
    var accumulatedDy by remember { mutableStateOf(0f) }
    var gestureResolved by remember { mutableStateOf(false) }
    val view = LocalView.current

    // Usage stats and calendar queries are binder/content-provider round trips — they run off the
    // main thread so the home screen's first frame never waits on them.
    var hasUsageAccess by remember { mutableStateOf(true) }
    var screenTimeMillis by remember { mutableStateOf<Long?>(null) }

    var hasCalendarPermission by remember { mutableStateOf(CalendarRepository.hasPermission(context)) }
    var nextEvent by remember { mutableStateOf<CalendarRepository.UpcomingEvent?>(null) }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCalendarPermission = granted }

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                val usageAccess = DeviceUsage.hasUsageAccess(context)
                val millis = if (usageAccess) DeviceUsage.todayScreenTimeMillis(context) else null
                val calendarPermission = CalendarRepository.hasPermission(context)
                val event = if (calendarPermission) CalendarRepository.getNextEvent(context) else null
                withContext(Dispatchers.Main) {
                    hasUsageAccess = usageAccess
                    screenTimeMillis = millis
                    hasCalendarPermission = calendarPermission
                    nextEvent = event
                }
            }
            delay(SCREEN_TIME_POLL_MS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .onGloballyPositioned { coordinates ->
                // Claim the bottom strip from the system's edge gesture nav so a swipe
                // starting near the bottom still reaches our own drag detector below.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val bounds = coordinates.boundsInWindow()
                    val exclusionHeightPx = 48 * view.resources.displayMetrics.density
                    view.systemGestureExclusionRects = listOf(
                        Rect(
                            bounds.left.toInt(),
                            (bounds.bottom - exclusionHeightPx).toInt(),
                            bounds.right.toInt(),
                            bounds.bottom.toInt()
                        )
                    )
                }
            }
            // Swipe up anywhere on the home screen opens search; swipe left/right trigger
            // their own quick actions. A plain tap is unaffected since no drag occurs.
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        accumulatedDx = 0f
                        accumulatedDy = 0f
                        gestureResolved = false
                    },
                    onDrag = dragHandler@{ change, amount ->
                        if (gestureResolved) return@dragHandler
                        accumulatedDx += amount.x
                        accumulatedDy += amount.y
                        val absDx = abs(accumulatedDx)
                        val absDy = abs(accumulatedDy)
                        when {
                            accumulatedDy < SWIPE_UP_OPEN_THRESHOLD_PX && absDy > absDx -> {
                                change.consume()
                                gestureResolved = true
                                onOpenSearch()
                            }
                            accumulatedDy > SWIPE_DOWN_OPEN_THRESHOLD_PX && absDy > absDx -> {
                                change.consume()
                                gestureResolved = true
                                onSwipeDown()
                            }
                            absDx > SWIPE_HORIZONTAL_THRESHOLD_PX && absDx > absDy -> {
                                change.consume()
                                gestureResolved = true
                                if (accumulatedDx < 0) onSwipeLeft() else onSwipeRight()
                            }
                        }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Everything below the clock is pinned to the clock's own measured width, so a
            // long calendar title truncates with an ellipsis instead of stretching the whole
            // block wider than the clock itself.
            var clockWidthPx by remember { mutableStateOf(0) }
            val clockWidth = with(LocalDensity.current) { clockWidthPx.toDp() }

            Column(
                modifier = Modifier
                    .padding(top = 72.dp)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onOpenSettings,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DotMatrixClock(
                    time = time,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        clockWidthPx = coordinates.size.width
                    }
                )
                Row(
                    modifier = Modifier
                        .width(clockWidth)
                        .padding(top = 18.dp),
                ) {
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Box(modifier = Modifier.weight(1f))
                    Text(
                        text = when {
                            !hasUsageAccess -> "Enable screen time"
                            else -> screenTimeMillis?.let { DeviceUsage.formatDuration(it) } ?: ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = !hasUsageAccess) {
                            DeviceUsage.openUsageAccessSettings(context)
                        }
                    )
                }

                val event = nextEvent
                Column(
                    modifier = Modifier
                        .width(clockWidth)
                        .padding(top = 10.dp)
                        .border(BorderStroke(1.dp, SubtextGrey.copy(alpha = 0.4f)))
                        .padding(12.dp)
                        .clickable(enabled = !hasCalendarPermission) {
                            calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                        }
                ) {
                    when {
                        !hasCalendarPermission -> Text(
                            text = "Enable calendar",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        event != null -> {
                            Text(
                                text = event.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = PureWhite,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "> ${CalendarRepository.formatTimeRange(event)}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        else -> Text(
                            text = "No upcoming events",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        RadialAppMenu(
            apps = upperDialApps,
            onLaunch = onLaunch,
            modifier = Modifier.align(Alignment.BottomEnd),
            bottomPadding = UPPER_DIAL_BOTTOM_PADDING + dialVerticalOffset,
            startAngleDeg = -90f,
            sweepDeg = 180f,
        )
        RadialAppMenu(
            apps = bottomDialApps,
            onLaunch = onLaunch,
            modifier = Modifier.align(Alignment.BottomEnd),
            bottomPadding = dialVerticalOffset,
        )
    }
}
