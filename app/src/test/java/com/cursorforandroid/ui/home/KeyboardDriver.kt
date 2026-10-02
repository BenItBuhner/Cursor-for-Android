package com.cursorforandroid.ui.home

import android.graphics.Insets
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.animation.PathInterpolator
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import kotlin.math.roundToInt

/**
 * The on-screen keyboard over a Compose host, moved the way the platform moves it: a `WindowInsetsAnimation` of the
 * IME, prepared, its end state applied, started, a progress dispatched each frame along the curve and duration
 * `InsetsController` gives an IME whose window has animation callbacks ([Interpolator], [DurationMillis]), and ended.
 * Between frames the paused compose clock moves on by [frameMillis], so each progress is laid out and drawn as the
 * frame it arrived in, as on a device. [onFrame] is called after each frame with the inset it laid out at and the
 * keyboard's fraction risen.
 *
 * The test's `mainClock.autoAdvance` must be off while the keyboard moves.
 */
internal class KeyboardDriver(
    private val compose: AndroidComposeTestRule<*, *>,
    private val navigationBar: Int,
    private val frameMillis: Long = 16L,
) {
    /** The keyboard's inset now, in px. */
    var inset = 0
        private set

    /** The keyboard standing at [height] px, or gone at 0, without an animation: a window's insets applied. */
    fun stand(height: Int) {
        inset = height
        compose.runOnUiThread { composeView().dispatchApplyWindowInsets(insets(height)) }
        frame()
    }

    fun show(height: Int, onFrame: (inset: Int, risen: Float) -> Unit = { _, _ -> }) = move(from = inset, to = height, onFrame)

    fun hide(onFrame: (inset: Int, risen: Float) -> Unit = { _, _ -> }) = move(from = inset, to = 0, onFrame)

    /** The keyboard dragged down by predictive back to [peek] of its height over [frames] frames, then let go back up. */
    fun scrubAndCancel(peek: Float, frames: Int, onFrame: (inset: Int, risen: Float) -> Unit = { _, _ -> }) {
        val full = inset
        val animation = WindowInsetsAnimation(WindowInsets.Type.ime(), null, 0)
        val view = composeView()
        compose.runOnUiThread {
            view.dispatchWindowInsetsAnimationPrepare(animation)
            view.dispatchApplyWindowInsets(insets(0))
            view.dispatchWindowInsetsAnimationStart(animation, WindowInsetsAnimation.Bounds(Insets.NONE, Insets.of(0, 0, 0, full)))
        }
        val down = (1..frames).map { full - (full * peek * it / frames).roundToInt() }
        for (now in down + down.asReversed().drop(1) + full) {
            compose.runOnUiThread { view.dispatchWindowInsetsAnimationProgress(insets(now), listOf(animation)) }
            inset = now
            frame()
            onFrame(now, now.toFloat() / full)
        }
        // Cancelled: the animation ends and the window's insets are the keyboard's, standing, again.
        compose.runOnUiThread {
            view.dispatchWindowInsetsAnimationEnd(animation)
            view.dispatchApplyWindowInsets(insets(full))
        }
        inset = full
        frame()
    }

    private fun move(from: Int, to: Int, onFrame: (Int, Float) -> Unit) {
        val animation = WindowInsetsAnimation(WindowInsets.Type.ime(), Interpolator, DurationMillis)
        val view = composeView()
        val full = maxOf(from, to)
        compose.runOnUiThread {
            view.dispatchWindowInsetsAnimationPrepare(animation)
            view.dispatchApplyWindowInsets(insets(to))
            view.dispatchWindowInsetsAnimationStart(animation, WindowInsetsAnimation.Bounds(Insets.NONE, Insets.of(0, 0, 0, full)))
        }
        val frames = (DurationMillis / frameMillis).toInt().coerceAtLeast(1)
        for (step in 1..frames) {
            val fraction = step.toFloat() / frames
            val now = (from + (to - from) * Interpolator.getInterpolation(fraction)).roundToInt()
            compose.runOnUiThread {
                animation.fraction = fraction
                view.dispatchWindowInsetsAnimationProgress(insets(now), listOf(animation))
            }
            inset = now
            frame()
            onFrame(now, if (full == 0) 0f else now.toFloat() / full)
        }
        compose.runOnUiThread { view.dispatchWindowInsetsAnimationEnd(animation) }
        inset = to
        frame()
    }

    private fun frame() {
        compose.mainClock.advanceTimeBy(frameMillis)
        compose.waitForIdle()
    }

    private fun insets(ime: Int): WindowInsets = WindowInsets.Builder()
        .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, navigationBar))
        .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, ime))
        .setVisible(WindowInsets.Type.navigationBars(), true)
        .setVisible(WindowInsets.Type.ime(), ime > 0)
        .build()

    /** The Compose host view inside the activity: the one Compose installed its insets listener and animation callback on. */
    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content))) { "No AndroidComposeView in the activity" }
    }

    companion object {
        /** `InsetsController.SYNC_IME_INTERPOLATOR`, for both the show and the hide. */
        val Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

        /** `InsetsController.ANIMATION_DURATION_SYNC_IME_MS`. */
        const val DurationMillis = 285L
    }
}
