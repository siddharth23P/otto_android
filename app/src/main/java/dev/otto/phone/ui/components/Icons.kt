package dev.otto.phone.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The few line icons the app draws, in the web's lucide style: a 24 grid, 1.6 stroke, round caps.
 *  Tinted by the Icon that shows them. Every icon-only button that uses one carries its own label. */
object OttoIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Menu = icon("menu", "M4 6h16", "M4 12h16", "M4 18h16")
    val More = icon("more", "M12 5.5v.01", "M12 12v.01", "M12 18.5v.01")
    val ArrowUp = icon("arrow-up", "M12 19V5", "M5 12l7-7 7 7")
    val Square = icon("square", "M6 6h12v12H6z")
    val Brain = icon(
        "brain",
        "M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z",
        "M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z",
        "M12 5v13",
    )
    val FileText = icon("file-text", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M9 13h6", "M9 17h6")
    val ArrowUpRight = icon("arrow-up-right", "M7 17L17 7", "M8 7h9v9")
    val ArrowLeft = icon("arrow-left", "M19 12H5", "M12 19l-7-7 7-7")
    val Copy = icon("copy", "M9 9h11v11H9z", "M5 15H4V4h11v1")
    val Share = icon("share", "M12 3v12", "M8 7l4-4 4 4", "M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6")
    val Download = icon("download", "M12 3v12", "M8 11l4 4 4-4", "M5 19h14")
    val Close = icon("x", "M6 6l12 12", "M18 6L6 18")
    val Plus = icon("plus", "M12 5v14", "M5 12h14")
    val Upload = icon("upload", "M12 15V3", "M8 7l4-4 4 4", "M5 19h14")
    val ChevronRight = icon("chevron-right", "M9 6l6 6-6 6")
    val ChevronDown = icon("chevron-down", "M6 9l6 6 6-6")
    val Reply = icon("reply", "M9 14L4 9l5-5", "M4 9h10a6 6 0 0 1 6 6v4")
    val Ban = icon("ban", "M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18z", "M5.6 5.6l12.8 12.8")
    val Check = icon("check", "M5 12l5 5L19 7")
    val Trash = icon("trash", "M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    val Pencil = icon("pencil", "M16 4l4 4L8 20H4v-4z")

}
