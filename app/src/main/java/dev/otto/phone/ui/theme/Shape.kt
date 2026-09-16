package dev.otto.phone.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Corners on one scale (theme.css --r1..--r4, --r-pill). */
object OttoShapes {
    val r1 = RoundedCornerShape(8.dp)
    val r2 = RoundedCornerShape(13.dp)
    val r3 = RoundedCornerShape(21.dp)
    val r4 = RoundedCornerShape(34.dp)
    val pill = RoundedCornerShape(percent = 50)
    /** Bottom sheets: 18 dp top corners. */
    val sheet = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)

    val material = Shapes(extraSmall = r1, small = r1, medium = r2, large = r3, extraLarge = r4)
}

/** Fibonacci spacing, and the few fixed measures the screens share. */
object Spacing {
    val s5 = 5.dp
    val s8 = 8.dp
    val s13 = 13.dp
    val s21 = 21.dp
    val s34 = 34.dp

    val messageListHorizontal = 12.dp
    val messageListVertical = 10.dp
    val messageGap = 21.dp

    val hairline = 1.dp
    /** The start rule beside your own message, and its padding. */
    val userRule = 1.dp
    val userRulePadding = 14.dp
    /** Toasts and the ask card: a 3 dp start edge. */
    val stateEdge = 3.dp

    val minTarget = 48.dp
    val sendButton = 36.dp
    val pulseDot = 6.dp
    val typingDot = 4.dp
}
