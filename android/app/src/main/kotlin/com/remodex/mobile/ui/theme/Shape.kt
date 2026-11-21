package com.remodex.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Shared rounded “pill / capsule” language aligned with iOS Remodex references. */
val RemodexShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(22.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

val RemodexComposerCapsuleShape: RoundedCornerShape = RoundedCornerShape(24.dp)
val RemodexToolbarIconShape: RoundedCornerShape = RoundedCornerShape(20.dp)
