package com.focuslauncher.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PriceChange
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focuslauncher.app.ExpenseCategory
import com.focuslauncher.app.ExpenseRepository
import com.focuslauncher.app.transactionTag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private fun formatAmount(amount: Double): String = "₹${"%.0f".format(amount)}"

/** Shared category→icon mapping, reused by the home screen's own monthly summary. */
internal fun categoryIcon(category: ExpenseCategory): ImageVector = when (category) {
    ExpenseCategory.FOOD -> Icons.Filled.Restaurant
    ExpenseCategory.TRANSPORT -> Icons.Filled.DirectionsCar
    ExpenseCategory.ESSENTIALS -> Icons.Filled.ShoppingCart
    ExpenseCategory.EXTRAS -> Icons.Filled.Star
    ExpenseCategory.SUBSCRIPTION -> Icons.Filled.Subscriptions
}

/**
 * The manually-logged spend dashboard: today's total, a per-category breakdown, a line graph of
 * the last two weeks, and the transaction log. Tapping a transaction here opens an inline prompt
 * to retag it directly; holding one down reveals a Rename/Modify price/Delete action panel for
 * direct edits that don't need the model.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExpenseTrackerScreen(
    todayTotal: Double,
    categoryTotals: Map<ExpenseCategory, Double>,
    dailyTotals: List<ExpenseRepository.DayTotal>,
    recentTransactions: List<ExpenseRepository.Transaction>,
    isProcessingCommand: Boolean,
    onCorrectTransaction: (transactionId: Long, note: String) -> Unit,
    onRenameTransaction: (transactionId: Long, newName: String) -> Unit,
    onModifyTransactionAmount: (transactionId: Long, newAmount: Double) -> Unit,
    onDeleteTransaction: (transactionId: Long) -> Unit,
    onCommand: (String) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    var contextMenuTransactionId by remember { mutableStateOf<Long?>(null) }
    var renamingTransaction by remember { mutableStateOf<ExpenseRepository.Transaction?>(null) }
    var renameText by remember { mutableStateOf("") }
    var modifyingPriceTransaction by remember { mutableStateOf<ExpenseRepository.Transaction?>(null) }
    var priceText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(horizontal = 24.dp, vertical = 48.dp)
    ) {
        item {
            Text(text = "Expense Tracker", style = MaterialTheme.typography.bodyLarge, color = PureWhite)
        }

        item {
            DashboardCommandBar(
                isProcessing = isProcessingCommand,
                onCommand = onCommand,
                modifier = Modifier.padding(top = 20.dp),
            )
        }

        item {
            Column(modifier = Modifier.padding(top = 28.dp)) {
                Text(text = "TODAY", style = MaterialTheme.typography.bodySmall, color = SubtextGrey, letterSpacing = 1.sp)
                // New data syncing in rolls the total up/down like an odometer rather than
                // snapping, so a background re-sync finding fresh spend reads as "live".
                AnimatedContent(
                    targetState = todayTotal,
                    transitionSpec = {
                        (slideInVertically(tween(260)) { height -> height } + fadeIn(tween(260))) togetherWith
                            (slideOutVertically(tween(260)) { height -> -height } + fadeOut(tween(260)))
                    },
                    label = "todayTotal",
                    modifier = Modifier.padding(top = 4.dp),
                ) { total ->
                    Text(
                        text = formatAmount(total),
                        style = MaterialTheme.typography.headlineLarge,
                        color = PureWhite,
                    )
                }
            }
        }

        item { SectionHeader("By category") }
        item {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                val maxCategoryTotal = max(categoryTotals.values.maxOrNull() ?: 0.0, 1.0)
                ExpenseCategory.entries.forEach { category ->
                    CategoryRow(
                        category = category,
                        amount = categoryTotals[category] ?: 0.0,
                        fraction = ((categoryTotals[category] ?: 0.0) / maxCategoryTotal).toFloat(),
                    )
                }
            }
        }

        item { SectionHeader("Last 14 days") }
        item {
            SpendingLineGraph(
                dailyTotals = dailyTotals,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(top = 12.dp, bottom = 4.dp),
            )
        }

        if (recentTransactions.isNotEmpty()) {
            item { SectionHeader("Recent") }
            items(recentTransactions, key = { it.id }) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    isMenuOpen = contextMenuTransactionId == transaction.id,
                    onCorrect = { note -> onCorrectTransaction(transaction.id, note) },
                    onLongPress = { contextMenuTransactionId = transaction.id },
                    onDismissMenu = { contextMenuTransactionId = null },
                    onRename = {
                        contextMenuTransactionId = null
                        renameText = transaction.counterpartName
                        renamingTransaction = transaction
                    },
                    onModifyPrice = {
                        contextMenuTransactionId = null
                        priceText = "%.0f".format(transaction.amount)
                        modifyingPriceTransaction = transaction
                    },
                    onDelete = {
                        contextMenuTransactionId = null
                        onDeleteTransaction(transaction.id)
                    },
                    modifier = Modifier.animateItemPlacement(tween(220)),
                )
            }
        }
    }

    renamingTransaction?.let { transaction ->
        AlertDialog(
            onDismissRequest = { renamingTransaction = null },
            containerColor = PureBlack,
            tonalElevation = 0.dp,
            title = { Text("Rename", color = PureWhite) },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Unspecified),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = PureBlack,
                        unfocusedContainerColor = PureBlack,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        cursorColor = PureWhite,
                        focusedIndicatorColor = SubtextGrey,
                        unfocusedIndicatorColor = SubtextGrey.copy(alpha = 0.4f),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameTransaction(transaction.id, renameText)
                    renamingTransaction = null
                }) {
                    Text("Save", color = PureWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingTransaction = null }) {
                    Text("Cancel", color = SubtextGrey)
                }
            },
        )
    }

    modifyingPriceTransaction?.let { transaction ->
        AlertDialog(
            onDismissRequest = { modifyingPriceTransaction = null },
            containerColor = PureBlack,
            tonalElevation = 0.dp,
            title = { Text("Modify price", color = PureWhite) },
            text = {
                TextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Unspecified),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = PureBlack,
                        unfocusedContainerColor = PureBlack,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        cursorColor = PureWhite,
                        focusedIndicatorColor = SubtextGrey,
                        unfocusedIndicatorColor = SubtextGrey.copy(alpha = 0.4f),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    priceText.toDoubleOrNull()?.let { onModifyTransactionAmount(transaction.id, it) }
                    modifyingPriceTransaction = null
                }) {
                    Text("Save", color = PureWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { modifyingPriceTransaction = null }) {
                    Text("Cancel", color = SubtextGrey)
                }
            },
        )
    }
}

/**
 * One free-text instruction to the on-device model, which can add a transaction (cash, say),
 * delete one, or retag one — interpreted against the dashboard's own recent list. Clears
 * immediately on submit; "thinking…" stands in for the tag/total values briefly changing
 * underneath it rather than blocking the input itself.
 */
@Composable
private fun DashboardCommandBar(
    isProcessing: Boolean,
    onCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, SubtextGrey.copy(alpha = 0.4f)))
            .padding(12.dp),
    ) {
        Box {
            if (input.isEmpty()) {
                Text(
                    text = "> add, remove, or retag something…",
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
                    val command = input.trim()
                    if (command.isNotEmpty()) {
                        onCommand(command)
                        input = ""
                    }
                }),
                decorationBox = { inner ->
                    Row {
                        Text(text = "> ", style = MaterialTheme.typography.bodySmall, color = SubtextGrey)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (isProcessing) {
            Text(
                text = "thinking…",
                style = MaterialTheme.typography.bodySmall,
                color = SubtextGrey,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.bodySmall,
            color = SubtextGrey,
            letterSpacing = 1.sp,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(1.dp)
                .background(SubtextGrey.copy(alpha = 0.25f))
        )
    }
}

@Composable
private fun CategoryRow(category: ExpenseCategory, amount: Double, fraction: Float) {
    // Bars ease to their new width instead of jumping, so a re-sync that shifts the
    // breakdown (or a freshly-tagged merchant) reads as a smooth update, not a reset.
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "categoryFraction",
    )
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = categoryIcon(category),
                contentDescription = null,
                tint = PureWhite,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = category.label, style = MaterialTheme.typography.bodyMedium, color = PureWhite)
            Box(modifier = Modifier.weight(1f))
            Text(text = formatAmount(amount), style = MaterialTheme.typography.bodyMedium, color = SubtextGrey)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(3.dp)
                .background(SubtextGrey.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .height(3.dp)
                    .background(PureWhite)
            )
        }
    }
}

@Composable
private fun SpendingLineGraph(dailyTotals: List<ExpenseRepository.DayTotal>, modifier: Modifier = Modifier) {
    val maxValue = max(dailyTotals.maxOfOrNull { it.total } ?: 0.0, 1.0)

    // The line grows up from the baseline whenever the underlying data changes (first load,
    // or a re-sync finding new spend) rather than just appearing fully drawn.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(dailyTotals) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, animationSpec = tween(500, easing = FastOutSlowInEasing))
    }

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            drawLine(
                color = SubtextGrey.copy(alpha = 0.3f),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            if (dailyTotals.size < 2) return@Canvas
            val stepX = size.width / (dailyTotals.size - 1)
            val points = dailyTotals.mapIndexed { index, day ->
                val targetY = size.height - (day.total / maxValue).toFloat() * size.height
                val y = size.height - (size.height - targetY) * reveal.value
                Offset(index * stepX, y)
            }
            for (i in 0 until points.size - 1) {
                drawLine(PureWhite, points[i], points[i + 1], strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            points.forEach { point -> drawCircle(PureWhite, radius = 2.5.dp.toPx(), center = point) }
        }
        if (dailyTotals.isNotEmpty()) {
            val labelFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(
                    text = labelFormat.format(Date(dailyTotals.first().dayStartMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtextGrey,
                )
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = labelFormat.format(Date(dailyTotals.last().dayStartMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtextGrey,
                )
            }
        }
    }
}

private const val HOLD_BORDER_RISE_MS = 500
private const val HOLD_BORDER_FADE_MS = 180
private val ROW_ICON_PANEL_START_MARGIN = 22.dp // opaque buffer so text never cuts off right at the line
private val ROW_ICON_PANEL_END_PADDING = 12.dp
private val ROW_ICON_GAP = 18.dp
private val ROW_DIVIDER_GAP = 14.dp

/**
 * Tapping a row opens an inline "what was this actually?" prompt right below it, for fixing a
 * transaction after the fact via the model. Holding one down instead reveals an icon action
 * panel inline on the right — Rename / Modify price / Delete, direct edits with no model call —
 * matching the same hold-to-reveal affordance search results use for app actions.
 */
@Composable
private fun TransactionRow(
    transaction: ExpenseRepository.Transaction,
    isMenuOpen: Boolean,
    onCorrect: (String) -> Unit,
    onLongPress: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onModifyPrice: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    var expanded by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val holdProgress = remember { Animatable(0f) }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(PureBlack)
                .border(BorderStroke(1.5.dp, PureWhite.copy(alpha = holdProgress.value)))
                .pointerInput(transaction.id) {
                    detectTapGestures(
                        onPress = {
                            coroutineScope {
                                val riseJob = launch {
                                    holdProgress.snapTo(0f)
                                    holdProgress.animateTo(1f, tween(HOLD_BORDER_RISE_MS, easing = LinearEasing))
                                }
                                tryAwaitRelease()
                                riseJob.cancel()
                                launch { holdProgress.animateTo(0f, tween(HOLD_BORDER_FADE_MS)) }
                            }
                        },
                        onTap = { if (isMenuOpen) onDismissMenu() else expanded = !expanded },
                        onLongPress = { onLongPress() },
                    )
                }
                .padding(vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transaction.counterpartName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PureWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                    val tag = transactionTag(transaction.kind, transaction.category)
                    Text(
                        text = "$tag · ${dateFormat.format(Date(transaction.timestamp))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SubtextGrey,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = formatAmount(transaction.amount), style = MaterialTheme.typography.bodyMedium, color = PureWhite)
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = isMenuOpen,
                enter = fadeIn(tween(200)) + slideInHorizontally(tween(220)) { fullWidth -> fullWidth },
                exit = fadeOut(tween(150)) + slideOutHorizontally(tween(180)) { fullWidth -> fullWidth },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Row(
                    modifier = Modifier
                        .background(PureBlack)
                        .padding(vertical = 8.dp)
                        .padding(end = ROW_ICON_PANEL_END_PADDING),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.width(ROW_ICON_PANEL_START_MARGIN))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(22.dp)
                            .background(PureWhite.copy(alpha = 0.4f))
                    )
                    Spacer(modifier = Modifier.width(ROW_DIVIDER_GAP))
                    Row(horizontalArrangement = Arrangement.spacedBy(ROW_ICON_GAP), verticalAlignment = Alignment.CenterVertically) {
                        RowActionIcon(Icons.Filled.Edit, "Rename", onRename)
                        RowActionIcon(Icons.Filled.PriceChange, "Modify price", onModifyPrice)
                        RowActionIcon(Icons.Filled.Delete, "Delete", onDelete)
                    }
                }
            }
        }
        if (expanded) {
            Box(modifier = Modifier.padding(top = 8.dp)) {
                if (input.isEmpty()) {
                    Text(
                        text = "> what was this actually?",
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
                        val note = input.trim()
                        if (note.isNotEmpty()) {
                            onCorrect(note)
                            input = ""
                        }
                        expanded = false
                    }),
                    decorationBox = { inner ->
                        Row {
                            Text(text = "> ", style = MaterialTheme.typography.bodySmall, color = SubtextGrey)
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RowActionIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = PureWhite,
        modifier = Modifier
            .size(22.dp)
            .clickable(onClick = onClick)
    )
}
