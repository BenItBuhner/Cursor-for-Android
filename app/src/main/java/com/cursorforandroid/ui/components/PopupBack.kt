package com.cursorforandroid.ui.components

import android.os.Build
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A back dispatcher of a `Popup`'s own, the way a dialog has one. Back goes to the focused window, and a focusable
 * popup is one, so [content]'s `BackHandler`s and `PredictiveBackHandler`s would otherwise listen on the activity's
 * dispatcher and never hear it. From Android 13 the dispatcher registers with the popup window's
 * `OnBackInvokedDispatcher`, so it gets the gesture's progress; before that the Back key reaches it through
 * [backKeys], which the caller puts on the popup's focused node. It is also the popup view tree's
 * `OnBackPressedDispatcherOwner`, where a test finds it, as it would a dialog's.
 */
@Composable
internal fun PopupBackDispatcher(content: @Composable (backKeys: Modifier) -> Unit) {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val owner = remember(view) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        }
    }
    DisposableEffect(view, owner) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            view.findOnBackInvokedDispatcher()?.let(owner.onBackPressedDispatcher::setOnBackInvokedDispatcher)
        }
        view.setViewTreeOnBackPressedDispatcherOwner(owner)
        onDispose { }
    }
    val backKeys = Modifier.onKeyEvent { event ->
        if (event.key != Key.Back) return@onKeyEvent false
        if (event.type == KeyEventType.KeyUp) owner.onBackPressedDispatcher.onBackPressed()
        true
    }
    CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) { content(backKeys) }
}
