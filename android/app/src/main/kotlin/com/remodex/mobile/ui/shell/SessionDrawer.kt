package com.remodex.mobile.ui.shell

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue

// The public explicit-spec overload is still available in the project's Material version.
// Direct manipulation and accessibility dismissals stay with ModalNavigationDrawer.
@Suppress("DEPRECATION")
internal suspend fun DrawerState.moveToSessionDrawer(value: DrawerValue) {
    animateTo(value, tween(250, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)))
}
