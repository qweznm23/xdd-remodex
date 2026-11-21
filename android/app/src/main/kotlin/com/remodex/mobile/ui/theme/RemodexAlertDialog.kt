package com.remodex.mobile.ui.theme

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.annotation.StyleRes
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.remodex.mobile.R

/** Use the platform window transition so dismiss actions run immediately and an exiting IME keeps its lifecycle. */
@Composable
internal fun RemodexAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val colors = MaterialTheme.colorScheme
    val dimAmount = if (isAgentLightChrome()) 0.32f else 0.48f
    DialogBackdrop(dimAmount)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            WindowMotion(R.style.RemodexDialogMotion, useAppBackdrop = true)
            confirmButton()
        },
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = RemodexPopupChrome.PanelShape,
        containerColor = colors.conversationColors().dialog,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
        properties = properties,
    )
}


@Composable
internal fun RemodexPreviewDialog(onDismissRequest: () -> Unit, properties: DialogProperties, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        WindowMotion(R.style.RemodexPreviewMotion)
        content()
    }
}

@Composable
private fun WindowMotion(@StyleRes style: Int, useAppBackdrop: Boolean = false) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        if (window != null) {
            if (window.attributes.windowAnimations != style) window.setWindowAnimations(style)
            if (useAppBackdrop && window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
    }
}


/** Draw on the invoking window. A system DIM_BEHIND surface can outlive the dialog and intercept the next tap. */
@Composable
private fun DialogBackdrop(dimAmount: Float) {
    val host = LocalView.current.rootView
    DisposableEffect(host, dimAmount) {
        val scrim = ColorDrawable(android.graphics.Color.BLACK)
        scrim.alpha = 0
        scrim.setBounds(0, 0, host.width, host.height)
        val resize = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scrim.setBounds(0, 0, host.width, host.height)
        }
        host.addOnLayoutChangeListener(resize)
        host.overlay.add(scrim)
        fun fade(target: Int, milliseconds: Long, removeAtEnd: Boolean = false): ValueAnimator =
            ValueAnimator.ofInt(scrim.alpha, target).apply {
                duration = milliseconds
                addUpdateListener { scrim.alpha = it.animatedValue as Int }
                if (removeAtEnd) addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { host.overlay.remove(scrim) }
                })
                start()
            }
        val entry = fade((255 * dimAmount).toInt(), 180)
        onDispose {
            entry.cancel()
            host.removeOnLayoutChangeListener(resize)
            if (host.isAttachedToWindow) fade(0, 150, removeAtEnd = true) else host.overlay.remove(scrim)
        }
    }
}
