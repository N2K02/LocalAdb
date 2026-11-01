package dev.n2k02.localadb

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Observe a completed horizontal gesture without stealing scrolling or text selection.
 * A nested tab strip may consume only a recognized swipe, preventing its parent
 * navigation from also responding to the same gesture.
 */
internal fun Modifier.swipeTabNavigation(
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    canSwipeLeft: Boolean = true,
    canSwipeRight: Boolean = true,
    consumeOnNavigation: Boolean = false,
    distance: Dp = 84.dp,
): Modifier = this.pointerInput(
    canSwipeLeft, canSwipeRight, consumeOnNavigation, distance,
    onSwipeLeft, onSwipeRight,
) {
    val minimum = distance.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var dx = 0f
        var dy = 0f
        var childHandled = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            dx += change.position.x - change.previousPosition.x
            dy += change.position.y - change.previousPosition.y
            if (change.isConsumed) childHandled = true
            if (!change.pressed) {
                if (!childHandled && abs(dx) >= minimum && abs(dx) > 1.7f * abs(dy)) {
                    if (dx < 0f && canSwipeLeft) {
                        if (consumeOnNavigation) change.consume()
                        onSwipeLeft()
                    } else if (dx > 0f && canSwipeRight) {
                        if (consumeOnNavigation) change.consume()
                        onSwipeRight()
                    }
                }
                break
            }
        }
    }
}

/**
 * Bounded back-stack for tab visits. Re-selecting the same tab cannot add
 * duplicate entries; Back pops the trail without re-pushing the destination.
 */
internal object TabVisitHistory {
    fun <T> select(history: List<T>, tab: T): List<T> =
        if (history.lastOrNull() == tab) history
        else (history + tab).takeLast(16)

    fun <T> pop(history: List<T>): Pair<List<T>, T?> {
        if (history.size < 2) return history to null
        val remaining = history.dropLast(1)
        return remaining to remaining.last()
    }
}
