package com.focuslauncher.app.ui

import android.Manifest
import android.graphics.Rect
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focuslauncher.app.AppInfo
import com.focuslauncher.app.CalendarRepository
import com.focuslauncher.app.DeviceUsage
import com.focuslauncher.app.ExpenseCategory
import com.focuslauncher.app.LastPaymentStore
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

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
    expenseTrackerEnabled: Boolean,
    todayExpenseTotal: Double,
    monthlyExpenseTotal: Double,
    categoryTotalsMonth: Map<ExpenseCategory, Double>,
    onOpenExpenseTracker: () -> Unit,
    lastPayment: LastPaymentStore.LastPayment?,
    isAddingTransaction: Boolean,
    onAddTransaction: (description: String) -> Unit,
    dialVerticalOffset: Dp,
) {
    val context = LocalContext.current
    val time = rememberCurrentTime()
    val dateText = remember { SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date()) }
    var accumulatedDx by remember { mutableStateOf(0f) }
    var accumulatedDy by remember { mutableStateOf(0f) }
    var gestureResolved by remember { mutableStateOf(false) }
    val view = LocalView.current

    var hasUsageAccess by remember { mutableStateOf(DeviceUsage.hasUsageAccess(context)) }
    var screenTimeMillis by remember { mutableStateOf(DeviceUsage.todayScreenTimeMillis(context)) }

    var hasCalendarPermission by remember { mutableStateOf(CalendarRepository.hasPermission(context)) }
    var nextEvent by remember { mutableStateOf<CalendarRepository.UpcomingEvent?>(null) }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCalendarPermission = granted }

    LaunchedEffect(Unit) {
        while (true) {
            hasUsageAccess = DeviceUsage.hasUsageAccess(context)
            if (hasUsageAccess) screenTimeMillis = DeviceUsage.todayScreenTimeMillis(context)
            hasCalendarPermission = CalendarRepository.hasPermission(context)
            if (hasCalendarPermission) nextEvent = CalendarRepository.getNextEvent(context)
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
                        text = if (hasUsageAccess) {
                            DeviceUsage.formatDuration(screenTimeMillis)
                        } else {
                            "Enable screen time"
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

                if (expenseTrackerEnabled) {
                    ExpenseSummary(
                        todayTotal = todayExpenseTotal,
                        monthTotal = monthlyExpenseTotal,
                        categoryTotals = categoryTotalsMonth,
                        onOpenDashboard = onOpenExpenseTracker,
                        modifier = Modifier
                            .width(clockWidth)
                            .padding(top = 18.dp),
                    )
                }
            }
        }

        // Composed before the dials (and the dials before the modal overlay) so z-order comes
        // out right: dials draw on top of the prompt — they're the thing you're actively
        // dragging, the prompt shouldn't be able to cover them — while the full-screen quick-add
        // overlay still ends up on top of everything, dials included, since it's modal.
        var quickAddFocused by remember { mutableStateOf(false) }
        if (expenseTrackerEnabled) {
            QuickAddPrompt(
                lastPayment = lastPayment,
                isAdding = isAddingTransaction,
                onRequestFocus = { quickAddFocused = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp),
            )
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

        if (expenseTrackerEnabled) {
            BackHandler(enabled = quickAddFocused) { quickAddFocused = false }
            QuickAddOverlay(
                visible = quickAddFocused,
                onSubmit = { description ->
                    quickAddFocused = false
                    onAddTransaction(description)
                },
                onDismiss = { quickAddFocused = false },
            )
        }
    }
}

private fun formatExpenseAmount(amount: Double): String = "₹${"%.0f".format(amount)}"

private val BAR_GRAPH_HEIGHT = 56.dp

/**
 * This month's spend at a glance: total, a bar per category (shortest to tallest, left to
 * right) and a `>`-prefixed breakdown line per category — matching the calendar block's own
 * `>` convention above. Categories with nothing spent yet are omitted to reduce clutter.
 */
@Composable
private fun ExpenseSummary(
    todayTotal: Double,
    monthTotal: Double,
    categoryTotals: Map<ExpenseCategory, Double>,
    onOpenDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nonZero = remember(categoryTotals) { categoryTotals.filter { it.value > 0.0 } }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenDashboard),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "TODAY", style = MaterialTheme.typography.bodySmall, color = SubtextGrey, letterSpacing = 1.sp)
                // Rolls like an odometer when a background re-sync finds new spend.
                AnimatedContent(
                    targetState = todayTotal,
                    transitionSpec = {
                        (slideInVertically(tween(260)) { height -> height } + fadeIn(tween(260))) togetherWith
                            (slideOutVertically(tween(260)) { height -> -height } + fadeOut(tween(260)))
                    },
                    label = "todayTotal",
                ) { total ->
                    Text(text = formatExpenseAmount(total), style = MaterialTheme.typography.headlineMedium, color = PureWhite)
                }
                AnimatedContent(
                    targetState = monthTotal,
                    transitionSpec = {
                        (slideInVertically(tween(260)) { height -> height } + fadeIn(tween(260))) togetherWith
                            (slideOutVertically(tween(260)) { height -> -height } + fadeOut(tween(260)))
                    },
                    label = "monthTotal",
                    modifier = Modifier.padding(top = 2.dp),
                ) { total ->
                    Text(
                        text = "This month  ${formatExpenseAmount(total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SubtextGrey,
                    )
                }
            }
            DashboardButton()
        }

        if (nonZero.isNotEmpty()) {
            MonthlyBarGraph(
                categoryTotals = nonZero,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BAR_GRAPH_HEIGHT)
                    .padding(top = 12.dp),
            )
            Column(modifier = Modifier.padding(top = 8.dp)) {
                nonZero.entries.sortedByDescending { it.value }.forEach { (category, amount) ->
                    Text(
                        text = "> ${category.label}  ${formatExpenseAmount(amount)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Bars sorted shortest to tallest, left to right, each growing up from the baseline. */
@Composable
private fun MonthlyBarGraph(categoryTotals: Map<ExpenseCategory, Double>, modifier: Modifier = Modifier) {
    val ascending = remember(categoryTotals) { categoryTotals.entries.sortedBy { it.value } }
    val maxValue = max(ascending.maxOfOrNull { it.value } ?: 0.0, 1.0)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        ascending.forEach { (_, amount) ->
            val targetFraction = (amount / maxValue).toFloat().coerceIn(0.06f, 1f)
            val animatedFraction by animateFloatAsState(
                targetValue = targetFraction,
                animationSpec = tween(500, easing = FastOutSlowInEasing),
                label = "barFraction",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(animatedFraction)
                    .background(PureWhite)
            )
        }
    }
}

/** Opens the expense dashboard — same bordered ">" box used throughout the app. */
@Composable
private fun DashboardButton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .border(BorderStroke(1.dp, SubtextGrey.copy(alpha = 0.5f)))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text = ">", style = MaterialTheme.typography.bodyLarge, color = PureWhite)
    }
}

private val QUICK_ADD_WIDTH = 230.dp

/**
 * Replaces the old auto-classified card stack: a single persistent prompt where the user
 * directly describes a purchase to log. Below it, a subtle grey line reminds them of the last
 * bank/UPI debit SMS seen — purely informational, never written to the tracker on its own.
 */
@Composable
private fun QuickAddPrompt(
    lastPayment: LastPaymentStore.LastPayment?,
    isAdding: Boolean,
    onRequestFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(QUICK_ADD_WIDTH)
            .border(BorderStroke(1.dp, SubtextGrey.copy(alpha = 0.5f)))
            .background(PureBlack)
            .clickable(onClick = onRequestFocus)
            .padding(12.dp),
    ) {
        Text(
            text = "> log a purchase…",
            style = MaterialTheme.typography.bodySmall,
            color = PureWhite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AnimatedContent(
            targetState = when {
                isAdding -> "adding…"
                lastPayment != null -> "Last paid ${formatExpenseAmount(lastPayment.amount)} · ${lastPayment.counterpartName}"
                else -> null
            },
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            label = "lastPaymentHint",
            modifier = Modifier.padding(top = 4.dp),
        ) { label ->
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtextGrey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Tapping the quick-add prompt lands here: the rest of the screen dims (the same treatment the
 * radial dial uses while open) and this card grows up into the center from where the prompt
 * sits, auto-focused and keyboard up immediately — centered so the keyboard never covers it.
 * Enter submits and the card shrinks back out the same way it came; tapping outside dismisses
 * without submitting.
 */
@Composable
private fun QuickAddOverlay(
    visible: Boolean,
    onSubmit: (description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun closeKeyboardAndDismiss() {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(200)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { closeKeyboardAndDismiss() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Separate from the scrim's own fade so the card gets its own motion — growing up
            // from roughly where the prompt sits rather than just popping into place.
            AnimatedVisibility(
                visible = visible,
                enter = scaleIn(initialScale = 0.8f, animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                    slideInVertically(animationSpec = tween(260, easing = FastOutSlowInEasing)) { height -> height / 6 },
                exit = scaleOut(targetScale = 0.8f, animationSpec = tween(200)) +
                    slideOutVertically(animationSpec = tween(200)) { height -> height / 6 },
            ) {
                QuickAddCard(
                    visible = visible,
                    onSubmit = { description ->
                        closeKeyboardAndDismiss()
                        onSubmit(description)
                    },
                    onClose = ::closeKeyboardAndDismiss,
                )
            }
        }
    }
}

@Composable
private fun QuickAddCard(
    visible: Boolean,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .width(QUICK_ADD_WIDTH)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}, // swallow taps so they don't fall through to the scrim above
            )
            .border(BorderStroke(1.dp, PureWhite.copy(alpha = 0.6f)))
            .background(PureBlack)
            .padding(16.dp),
    ) {
        Text(
            text = "> what did you spend on?",
            style = MaterialTheme.typography.bodySmall,
            color = SubtextGrey,
        )
        Box(modifier = Modifier.padding(top = 10.dp)) {
            if (input.isEmpty()) {
                Text(
                    text = "> e.g. 200 on lunch",
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtextGrey.copy(alpha = 0.5f),
                )
            }
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(color = PureWhite),
                cursorBrush = SolidColor(PureWhite),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val description = input.trim()
                    if (description.isNotEmpty()) onSubmit(description) else onClose()
                }),
                decorationBox = { inner ->
                    Row {
                        Text(text = "> ", style = MaterialTheme.typography.bodySmall, color = SubtextGrey)
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
    }

    // Only grabs focus on the way in — the exit animation re-renders this same composable with
    // visible=false while it plays, and re-requesting focus then would reopen the keyboard right
    // as everything is trying to close.
    LaunchedEffect(visible) {
        if (visible) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }
}
