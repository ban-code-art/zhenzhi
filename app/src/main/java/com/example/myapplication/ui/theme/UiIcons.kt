package com.example.myapplication.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** S1 glyphs on a 24-unit grid. The caller supplies tint and accessibility labels. */
internal object UiIcons {
    val Back by lazy {
        outline("Back", mirror = true) {
            moveTo(10f, 5.5f)
            lineTo(3.5f, 12f)
            lineTo(10f, 18.5f)
            moveTo(4f, 12f)
            lineTo(20f, 12f)
        }
    }

    val Chevron by lazy {
        outline("Chevron", mirror = true) {
            moveTo(9f, 5.5f)
            lineTo(15.5f, 12f)
            lineTo(9f, 18.5f)
        }
    }

    val Close by lazy {
        outline("Close") {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }

    val More by lazy {
        vector("More").path(fill = ink) {
            circle(12f, 5f, 1.55f)
            circle(12f, 12f, 1.55f)
            circle(12f, 19f, 1.55f)
        }.build()
    }

    val Search by lazy {
        outline("Search") {
            circle(10.5f, 10.5f, 5.9f)
            moveTo(14.75f, 14.75f)
            lineTo(20f, 20f)
        }
    }

    val Edit by lazy {
        outline("Edit") {
            moveTo(4.25f, 19.75f)
            lineTo(5.2f, 15.15f)
            lineTo(15.65f, 4.7f)
            curveTo(16.3f, 4.05f, 17.35f, 4.05f, 18f, 4.7f)
            lineTo(19.3f, 6f)
            curveTo(19.95f, 6.65f, 19.95f, 7.7f, 19.3f, 8.35f)
            lineTo(8.85f, 18.8f)
            close()
            moveTo(14.1f, 6.25f)
            lineTo(17.75f, 9.9f)
        }
    }

    val Refresh by lazy {
        outline("Refresh") {
            moveTo(19.5f, 9f)
            curveTo(18.3f, 5.9f, 15.55f, 4f, 12f, 4f)
            curveTo(8.1f, 4f, 5.1f, 6.65f, 4.25f, 10.05f)
            moveTo(19.5f, 4.8f)
            lineTo(19.5f, 9f)
            lineTo(15.3f, 9f)
            moveTo(4.5f, 15f)
            curveTo(5.7f, 18.1f, 8.45f, 20f, 12f, 20f)
            curveTo(15.9f, 20f, 18.9f, 17.35f, 19.75f, 13.95f)
            moveTo(4.5f, 19.2f)
            lineTo(4.5f, 15f)
            lineTo(8.7f, 15f)
        }
    }

    val Export by lazy {
        outline("Export") {
            tray()
            moveTo(12f, 16f)
            lineTo(12f, 3.5f)
            moveTo(7.5f, 8f)
            lineTo(12f, 3.5f)
            lineTo(16.5f, 8f)
        }
    }

    val Import by lazy {
        outline("Import") {
            tray()
            moveTo(12f, 3.5f)
            lineTo(12f, 15.5f)
            moveTo(7.5f, 11f)
            lineTo(12f, 15.5f)
            lineTo(16.5f, 11f)
        }
    }

    val Restore by lazy {
        outline("Restore", mirror = true) {
            moveTo(3.5f, 4.8f)
            lineTo(3.5f, 9.25f)
            lineTo(8f, 9.25f)
            moveTo(4f, 8.9f)
            curveTo(5.5f, 5.55f, 8.6f, 4f, 12.2f, 4f)
            curveTo(16.65f, 4f, 20.2f, 7.6f, 20.2f, 12f)
            curveTo(20.2f, 16.4f, 16.65f, 20f, 12.2f, 20f)
            curveTo(8.55f, 20f, 5.45f, 17.6f, 4.45f, 14.25f)
            moveTo(12.2f, 7.65f)
            lineTo(12.2f, 12.25f)
            lineTo(15.65f, 14.1f)
        }
    }

    val Trash by lazy {
        outline("Trash") {
            moveTo(3.5f, 6.5f)
            lineTo(20.5f, 6.5f)
            moveTo(9f, 6.5f)
            lineTo(9f, 4f)
            lineTo(15f, 4f)
            lineTo(15f, 6.5f)
            moveTo(6f, 6.5f)
            lineTo(7f, 18.75f)
            quadTo(7.1f, 20.25f, 8.6f, 20.25f)
            horizontalLineTo(15.4f)
            quadTo(16.9f, 20.25f, 17f, 18.75f)
            lineTo(18f, 6.5f)
            moveTo(10f, 10f)
            lineTo(10.4f, 16.75f)
            moveTo(14f, 10f)
            lineTo(13.6f, 16.75f)
        }
    }

    val Check by lazy {
        outline("Check") {
            moveTo(4.5f, 12.2f)
            lineTo(9.35f, 17f)
            lineTo(19.5f, 6.85f)
        }
    }

    val Books by lazy {
        outline("Books") {
            moveTo(12f, 6.6f)
            curveTo(9.6f, 4.7f, 6.8f, 4.1f, 3.25f, 4.7f)
            lineTo(3.25f, 18.7f)
            curveTo(6.8f, 18.1f, 9.6f, 18.75f, 12f, 20.6f)
            curveTo(14.4f, 18.75f, 17.2f, 18.1f, 20.75f, 18.7f)
            lineTo(20.75f, 4.7f)
            curveTo(17.2f, 4.1f, 14.4f, 4.7f, 12f, 6.6f)
            verticalLineTo(20.6f)
            moveTo(6f, 8.6f)
            quadTo(8f, 8.5f, 9.5f, 9.5f)
            moveTo(14.5f, 9.5f)
            quadTo(16f, 8.5f, 18f, 8.6f)
        }
    }

    val Quiz by lazy {
        outline("Quiz") {
            clipboard()
            moveTo(8.6f, 13.9f)
            lineTo(11.1f, 16.4f)
            lineTo(15.6f, 11.9f)
        }
    }

    val Encyclopedia by lazy {
        outline("Encyclopedia") {
            moveTo(10.5f, 3.4f)
            arcToRelative(2.2f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 3f, dy1 = 0f)
            lineToRelative(7.1f, 7.1f)
            arcToRelative(2.2f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 0f, dy1 = 3f)
            lineToRelative(-7.1f, 7.1f)
            arcToRelative(2.2f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = -3f, dy1 = 0f)
            lineToRelative(-7.1f, -7.1f)
            arcToRelative(2.2f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 0f, dy1 = -3f)
            close()
            moveTo(9.4f, 14.8f)
            lineTo(11.0f, 13.2f)
            moveTo(13.0f, 10.8f)
            lineTo(14.6f, 9.2f)
            circle(8.3f, 15.9f, 1.45f)
            circle(12f, 12f, 1.45f)
            circle(15.7f, 8.1f, 1.45f)
        }
    }

    val Records by lazy {
        outline("Records") {
            roundedBox(4.5f, 3.75f, 19.5f, 20.25f, 2f)
            moveTo(8f, 8.5f)
            horizontalLineTo(16f)
            moveTo(8f, 12f)
            horizontalLineTo(16f)
            moveTo(8f, 15.5f)
            horizontalLineTo(13.75f)
        }
    }

    val Person by lazy {
        outline("Person") {
            circle(12f, 7.5f, 3.6f)
            moveTo(4.5f, 20f)
            curveTo(4.5f, 16.6f, 7.85f, 14.1f, 12f, 14.1f)
            curveTo(16.15f, 14.1f, 19.5f, 16.6f, 19.5f, 20f)
        }
    }

    val Directory by lazy {
        outline("Directory") {
            moveTo(3.5f, 17.5f)
            verticalLineTo(6.25f)
            quadTo(3.5f, 4.5f, 5.25f, 4.5f)
            horizontalLineTo(9.25f)
            lineTo(11.35f, 6.75f)
            horizontalLineTo(18.75f)
            quadTo(20.5f, 6.75f, 20.5f, 8.5f)
            verticalLineTo(17.5f)
            quadTo(20.5f, 19.25f, 18.75f, 19.25f)
            horizontalLineTo(5.25f)
            quadTo(3.5f, 19.25f, 3.5f, 17.5f)
            close()
        }
    }

    val Bookmark by lazy { outline("Bookmark") { bookmark() } }
    val BookmarkFilled by lazy { glyph("BookmarkFilled", filled = true) { bookmark() } }
    val Favorite by lazy { outline("Favorite") { heart() } }
    val FavoriteFilled by lazy { glyph("FavoriteFilled", filled = true) { heart() } }

    val Ai by lazy {
        outline("Ai") {
            moveTo(9f, 18.5f)
            lineTo(5f, 21f)
            verticalLineTo(17.5f)
            quadTo(3f, 16f, 3f, 13f)
            verticalLineTo(8f)
            quadTo(3f, 4.5f, 6.5f, 4.5f)
            horizontalLineTo(17.5f)
            quadTo(21f, 4.5f, 21f, 8f)
            verticalLineTo(15f)
            quadTo(21f, 18.5f, 17.5f, 18.5f)
            close()
            moveTo(12f, 7.3f)
            quadTo(12.4f, 10.9f, 16.1f, 11.45f)
            quadTo(12.4f, 12.1f, 12f, 15.6f)
            quadTo(11.4f, 12.1f, 7.9f, 11.45f)
            quadTo(11.4f, 10.9f, 12f, 7.3f)
            close()
        }
    }

    val Settings by lazy {
        outline("Settings") {
            moveTo(4.5f, 6.5f)
            horizontalLineTo(7.5f)
            moveTo(13.5f, 6.5f)
            horizontalLineTo(19.5f)
            circle(10.5f, 6.5f, 3f)
            moveTo(4.5f, 17.5f)
            horizontalLineTo(12.5f)
            moveTo(18.5f, 17.5f)
            horizontalLineTo(19.5f)
            circle(15.5f, 17.5f, 3f)
        }
    }

    val Notifications by lazy {
        outline("Notifications") {
            moveTo(6f, 10f)
            curveTo(6f, 6.55f, 8.5f, 4f, 12f, 4f)
            curveTo(15.5f, 4f, 18f, 6.55f, 18f, 10f)
            verticalLineTo(13.5f)
            lineTo(20f, 16.75f)
            horizontalLineTo(4f)
            lineTo(6f, 13.5f)
            close()
            moveTo(9.7f, 20f)
            quadTo(12f, 22f, 14.3f, 20f)
        }
    }

    val WrongQuestions by lazy {
        outline("WrongQuestions") {
            clipboard()
            moveTo(9f, 11.75f)
            lineTo(15f, 17.75f)
            moveTo(15f, 11.75f)
            lineTo(9f, 17.75f)
        }
    }

    val Progress by lazy {
        outline("Progress") {
            moveTo(3.75f, 19.25f)
            horizontalLineTo(20.25f)
            moveTo(5.75f, 16f)
            verticalLineTo(13.5f)
            moveTo(12f, 16f)
            verticalLineTo(9.5f)
            moveTo(18.25f, 16f)
            verticalLineTo(5f)
        }
    }

    val Database by lazy {
        outline("Database") {
            moveTo(19.5f, 6.5f)
            curveTo(19.5f, 8.2f, 16.15f, 9.5f, 12f, 9.5f)
            curveTo(7.85f, 9.5f, 4.5f, 8.2f, 4.5f, 6.5f)
            curveTo(4.5f, 4.8f, 7.85f, 3.5f, 12f, 3.5f)
            curveTo(16.15f, 3.5f, 19.5f, 4.8f, 19.5f, 6.5f)
            verticalLineTo(17.5f)
            curveTo(19.5f, 19.2f, 16.15f, 20.5f, 12f, 20.5f)
            curveTo(7.85f, 20.5f, 4.5f, 19.2f, 4.5f, 17.5f)
            verticalLineTo(6.5f)
            moveTo(4.5f, 12f)
            curveTo(4.5f, 13.7f, 7.85f, 15f, 12f, 15f)
            curveTo(16.15f, 15f, 19.5f, 13.7f, 19.5f, 12f)
        }
    }

    val Lock by lazy {
        outline("Lock") {
            roundedBox(5f, 10f, 19f, 20.5f, 2f)
            moveTo(8f, 10f)
            verticalLineTo(7.25f)
            curveTo(8f, 4.95f, 9.8f, 3.5f, 12f, 3.5f)
            curveTo(14.2f, 3.5f, 16f, 4.95f, 16f, 7.25f)
            verticalLineTo(10f)
            moveTo(12f, 14f)
            verticalLineTo(16.7f)
        }
    }

    val Logout by lazy {
        outline("Logout", mirror = true) {
            moveTo(12.75f, 4f)
            horizontalLineTo(6.25f)
            quadTo(4.25f, 4f, 4.25f, 6f)
            verticalLineTo(18f)
            quadTo(4.25f, 20f, 6.25f, 20f)
            horizontalLineTo(12.75f)
            moveTo(10f, 12f)
            horizontalLineTo(20.5f)
            moveTo(16f, 7.5f)
            lineTo(20.5f, 12f)
            lineTo(16f, 16.5f)
        }
    }

    val Info by lazy {
        outline("Info") {
            circle(12f, 12f, 8.6f)
            moveTo(12f, 10.65f)
            verticalLineTo(16.5f)
            circle(12f, 7.55f, .25f)
        }
    }

    val Add by lazy {
        outline("Add") {
            moveTo(12f, 5f)
            verticalLineTo(19f)
            moveTo(5f, 12f)
            horizontalLineTo(19f)
        }
    }

    val ChevronDown by lazy {
        outline("ChevronDown") {
            moveTo(6.5f, 9.25f)
            lineTo(12f, 14.75f)
            lineTo(17.5f, 9.25f)
        }
    }

    val Palette by lazy {
        outline("Palette") {
            circle(12f, 12f, 8.5f)
            circle(9f, 9.25f, .9f)
            circle(14.75f, 9f, .9f)
            circle(8.75f, 13.75f, .9f)
            circle(14.5f, 14.25f, 1.35f)
        }
    }

    val Eyedropper by lazy {
        outline("Eyedropper") {
            moveTo(14.5f, 5.5f)
            lineTo(18.5f, 9.5f)
            moveTo(16.5f, 4f)
            curveTo(17.6f, 2.9f, 19.4f, 2.9f, 20.5f, 4f)
            curveTo(21.6f, 5.1f, 21.6f, 6.9f, 20.5f, 8f)
            lineTo(18f, 10.5f)
            moveTo(15.5f, 6.5f)
            lineTo(6.5f, 15.5f)
            lineTo(4.75f, 19.25f)
            lineTo(8.5f, 17.5f)
            lineTo(17.5f, 8.5f)
        }
    }

    private val ink = SolidColor(Color.Black)

    private fun vector(name: String, mirror: Boolean = false) = ImageVector.Builder(
        name = "S1.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = mirror,
    )

    private fun outline(name: String, mirror: Boolean = false, draw: PathBuilder.() -> Unit) =
        glyph(name, mirror, filled = false, draw = draw)

    private fun glyph(
        name: String,
        mirror: Boolean = false,
        filled: Boolean,
        draw: PathBuilder.() -> Unit,
    ) = vector(name, mirror).path(
        fill = if (filled) ink else null,
        stroke = ink,
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) { draw() }.build()

    private fun PathBuilder.circle(x: Float, y: Float, radius: Float) {
        val handle = radius * .552285f
        moveTo(x + radius, y)
        curveTo(x + radius, y + handle, x + handle, y + radius, x, y + radius)
        curveTo(x - handle, y + radius, x - radius, y + handle, x - radius, y)
        curveTo(x - radius, y - handle, x - handle, y - radius, x, y - radius)
        curveTo(x + handle, y - radius, x + radius, y - handle, x + radius, y)
        close()
    }

    private fun PathBuilder.roundedBox(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        moveTo(left + radius, top)
        horizontalLineTo(right - radius)
        quadTo(right, top, right, top + radius)
        verticalLineTo(bottom - radius)
        quadTo(right, bottom, right - radius, bottom)
        horizontalLineTo(left + radius)
        quadTo(left, bottom, left, bottom - radius)
        verticalLineTo(top + radius)
        quadTo(left, top, left + radius, top)
        close()
    }

    private fun PathBuilder.tray() {
        moveTo(4f, 13.5f)
        verticalLineTo(18.5f)
        quadTo(4f, 20.5f, 6f, 20.5f)
        horizontalLineTo(18f)
        quadTo(20f, 20.5f, 20f, 18.5f)
        verticalLineTo(13.5f)
    }

    private fun PathBuilder.clipboard() {
        moveTo(8.5f, 5.25f)
        horizontalLineTo(6.5f)
        quadTo(4.75f, 5.25f, 4.75f, 7f)
        verticalLineTo(18.75f)
        quadTo(4.75f, 20.5f, 6.5f, 20.5f)
        horizontalLineTo(17.5f)
        quadTo(19.25f, 20.5f, 19.25f, 18.75f)
        verticalLineTo(7f)
        quadTo(19.25f, 5.25f, 17.5f, 5.25f)
        horizontalLineTo(15.5f)
        roundedBox(8.5f, 3.5f, 15.5f, 7f, 1.25f)
    }

    private fun PathBuilder.bookmark() {
        moveTo(7.75f, 4f)
        horizontalLineTo(16.25f)
        quadTo(18f, 4f, 18f, 5.75f)
        verticalLineTo(20.25f)
        lineTo(12f, 16.5f)
        lineTo(6f, 20.25f)
        verticalLineTo(5.75f)
        quadTo(6f, 4f, 7.75f, 4f)
        close()
    }

    private fun PathBuilder.heart() {
        moveTo(12f, 20f)
        curveTo(9.25f, 17.8f, 3.5f, 13.65f, 3.5f, 9.2f)
        curveTo(3.5f, 6.45f, 5.5f, 4.65f, 8.05f, 4.65f)
        curveTo(9.65f, 4.65f, 11.05f, 5.55f, 12f, 7.2f)
        curveTo(12.95f, 5.55f, 14.35f, 4.65f, 15.95f, 4.65f)
        curveTo(18.5f, 4.65f, 20.5f, 6.45f, 20.5f, 9.2f)
        curveTo(20.5f, 13.65f, 14.75f, 17.8f, 12f, 20f)
        close()
    }
}
