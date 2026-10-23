package app.pocketshell.widget.serverhub

import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable

/**
 * Is the card actually ON SCREEN?
 *
 * Home keeps several applications composed side by side (a pager of cards,
 * a scrolling page below them). "Home is resumed" is therefore NOT "Git is
 * showing": the probe — dozens of guest `git` processes — used to keep firing
 * while the user was flipping between Notes and Todo, and the whole Home
 * stuttered. The probe now runs only while a meaningful part of the card is
 * inside the window; the moment it scrolls or pages away, the ticks stop, and
 * when it comes back the usual idle gate decides whether a rescan is due.
 *
 * Pure geometry here (JVM-tested); the thin Compose wrapper reads the layout.
 */
internal object Visibility {

    /** A card with less than this share of its area inside the window counts as off screen. */
    const val MIN_VISIBLE_FRACTION = 0.25f

    /** The share (0..1) of the rectangle that lies inside a window of [windowW] × [windowH]. */
    fun visibleFraction(
        left: Float, top: Float, right: Float, bottom: Float,
        windowW: Float, windowH: Float,
    ): Float {
        val width = right - left
        val height = bottom - top
        if (width <= 0f || height <= 0f || windowW <= 0f || windowH <= 0f) return 0f
        val ix = (minOf(right, windowW) - maxOf(left, 0f)).coerceAtLeast(0f)
        val iy = (minOf(bottom, windowH) - maxOf(top, 0f)).coerceAtLeast(0f)
        return (ix * iy) / (width * height)
    }

    fun isOnScreen(
        left: Float, top: Float, right: Float, bottom: Float,
        windowW: Float, windowH: Float,
    ): Boolean = visibleFraction(left, top, right, bottom, windowW, windowH) >= MIN_VISIBLE_FRACTION
}

/** Writes whether this node is on screen into [onScreen] after every layout pass. */
@Composable
internal fun Modifier.trackOnScreen(onScreen: MutableState<Boolean>): Modifier {
    val metrics = LocalContext.current.resources.displayMetrics
    return onGloballyPositioned { coords ->
        onScreen.value = if (coords.isAttached) {
            val b = coords.boundsInWindow()
            Visibility.isOnScreen(b.left, b.top, b.right, b.bottom, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
        } else {
            false
        }
    }
}
