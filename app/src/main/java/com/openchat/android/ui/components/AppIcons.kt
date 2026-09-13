package com.openchat.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * App-level icons. Bottom-nav icons are custom vector paths so we only depend
 * on material-icons-core (fast builds). Paths use even-odd fill so "holes"
 * render correctly. Icons are tinted at usage sites via Icon(..., tint=...).
 */
object AppIcons {

    /** Chat bubble icon. */
    val Chat: ImageVector = makeIcon("Chat", evenOdd = false) {
        moveTo(4.0f, 5.0f)
        cubicTo(4.0f, 3.9f, 4.9f, 3.0f, 6.0f, 3.0f)
        lineTo(18.0f, 3.0f)
        cubicTo(19.1f, 3.0f, 20.0f, 3.9f, 20.0f, 5.0f)
        lineTo(20.0f, 14.0f)
        cubicTo(20.0f, 15.1f, 19.1f, 16.0f, 18.0f, 16.0f)
        lineTo(9.0f, 16.0f)
        lineTo(4.0f, 20.5f)
        close()
        moveTo(7.0f, 7.0f)
        lineTo(11.0f, 9.5f)
        lineTo(7.0f, 12.0f)
        close()
        moveTo(13.0f, 11.0f)
        lineTo(17.0f, 11.0f)
        lineTo(17.0f, 13.0f)
        lineTo(13.0f, 13.0f)
        close()
    }

    /** Terminal prompt icon (outer frame with screen hole + prompt glyphs). */
    val Terminal: ImageVector = makeIcon("Terminal", evenOdd = true) {
        moveTo(3.0f, 4.0f)
        lineTo(21.0f, 4.0f)
        lineTo(21.0f, 20.0f)
        lineTo(3.0f, 20.0f)
        close()
        moveTo(5.0f, 6.0f)
        lineTo(5.0f, 18.0f)
        lineTo(19.0f, 18.0f)
        lineTo(19.0f, 6.0f)
        close()
        moveTo(7.0f, 8.5f)
        lineTo(10.5f, 11.0f)
        lineTo(7.0f, 13.5f)
        close()
        moveTo(12.0f, 13.0f)
        lineTo(17.0f, 13.0f)
        lineTo(17.0f, 15.0f)
        lineTo(12.0f, 15.0f)
        close()
    }

    /** Folder icon. */
    val Folder: ImageVector = makeIcon("Folder", evenOdd = false) {
        moveTo(3.0f, 5.0f)
        cubicTo(3.0f, 4.45f, 3.45f, 4.0f, 4.0f, 4.0f)
        lineTo(9.2f, 4.0f)
        lineTo(11.2f, 6.0f)
        lineTo(20.0f, 6.0f)
        cubicTo(20.55f, 6.0f, 21.0f, 6.45f, 21.0f, 7.0f)
        lineTo(21.0f, 18.0f)
        cubicTo(21.0f, 18.55f, 20.55f, 19.0f, 20.0f, 19.0f)
        lineTo(4.0f, 19.0f)
        cubicTo(3.45f, 19.0f, 3.0f, 18.55f, 3.0f, 18.0f)
        close()
    }

    /** Gear (settings) icon with center hole. */
    val Gear: ImageVector = makeIcon("Gear", evenOdd = true) {
        moveTo(10.3f, 3.0f)
        lineTo(13.7f, 3.0f)
        lineTo(14.2f, 5.5f)
        cubicTo(14.8f, 5.7f, 15.4f, 6.0f, 15.9f, 6.4f)
        lineTo(18.3f, 5.3f)
        lineTo(20.5f, 8.7f)
        lineTo(18.5f, 10.3f)
        cubicTo(18.6f, 10.9f, 18.6f, 11.1f, 18.5f, 11.7f)
        lineTo(20.5f, 13.3f)
        lineTo(18.3f, 16.7f)
        lineTo(15.9f, 15.6f)
        cubicTo(15.4f, 16.0f, 14.8f, 16.3f, 14.2f, 16.5f)
        lineTo(13.7f, 19.0f)
        lineTo(10.3f, 19.0f)
        lineTo(9.8f, 16.5f)
        cubicTo(9.2f, 16.3f, 8.6f, 16.0f, 8.1f, 15.6f)
        lineTo(5.7f, 16.7f)
        lineTo(3.5f, 13.3f)
        lineTo(5.5f, 11.7f)
        cubicTo(5.4f, 11.1f, 5.4f, 10.9f, 5.5f, 10.3f)
        lineTo(3.5f, 8.7f)
        lineTo(5.7f, 5.3f)
        lineTo(8.1f, 6.4f)
        cubicTo(8.6f, 6.0f, 9.2f, 5.7f, 9.8f, 5.5f)
        close()
        moveTo(12.0f, 9.0f)
        cubicTo(10.34f, 9.0f, 9.0f, 10.34f, 9.0f, 12.0f)
        cubicTo(9.0f, 13.66f, 10.34f, 15.0f, 12.0f, 15.0f)
        cubicTo(13.66f, 15.0f, 15.0f, 13.66f, 15.0f, 12.0f)
        cubicTo(15.0f, 10.34f, 13.66f, 9.0f, 12.0f, 9.0f)
        close()
    }

    /** Check badge for "Test Connection OK". */
    val BadgeCheck: ImageVector = makeIcon("BadgeCheck", evenOdd = true) {
        moveTo(12.0f, 2.0f)
        lineTo(14.4f, 4.3f)
        lineTo(17.7f, 3.7f)
        lineTo(18.9f, 6.8f)
        lineTo(22.0f, 8.0f)
        lineTo(21.4f, 11.3f)
        lineTo(22.0f, 14.6f)
        lineTo(18.9f, 15.8f)
        lineTo(17.7f, 18.9f)
        lineTo(14.4f, 18.3f)
        lineTo(12.0f, 20.6f)
        lineTo(9.6f, 18.3f)
        lineTo(6.3f, 18.9f)
        lineTo(5.1f, 15.8f)
        lineTo(2.0f, 14.6f)
        lineTo(2.6f, 11.3f)
        lineTo(2.0f, 8.0f)
        lineTo(5.1f, 6.8f)
        lineTo(6.3f, 3.7f)
        lineTo(9.6f, 4.3f)
        close()
        moveTo(10.6f, 14.6f)
        lineTo(15.4f, 9.8f)
        lineTo(14.1f, 8.5f)
        lineTo(10.6f, 12.0f)
        lineTo(9.0f, 10.4f)
        lineTo(7.7f, 11.7f)
        close()
    }

    /** Cross badge for "Test failed". */
    val BadgeCross: ImageVector = makeIcon("BadgeCross", evenOdd = true) {
        moveTo(12.0f, 2.0f)
        lineTo(14.4f, 4.3f)
        lineTo(17.7f, 3.7f)
        lineTo(18.9f, 6.8f)
        lineTo(22.0f, 8.0f)
        lineTo(21.4f, 11.3f)
        lineTo(22.0f, 14.6f)
        lineTo(18.9f, 15.8f)
        lineTo(17.7f, 18.9f)
        lineTo(14.4f, 18.3f)
        lineTo(12.0f, 20.6f)
        lineTo(9.6f, 18.3f)
        lineTo(6.3f, 18.9f)
        lineTo(5.1f, 15.8f)
        lineTo(2.0f, 14.6f)
        lineTo(2.6f, 11.3f)
        lineTo(2.0f, 8.0f)
        lineTo(5.1f, 6.8f)
        lineTo(6.3f, 3.7f)
        lineTo(9.6f, 4.3f)
        close()
        moveTo(9.1f, 8.0f)
        lineTo(7.7f, 9.4f)
        lineTo(10.2f, 11.9f)
        lineTo(7.7f, 14.4f)
        lineTo(9.1f, 15.8f)
        lineTo(11.6f, 13.3f)
        lineTo(14.1f, 15.8f)
        lineTo(15.5f, 14.4f)
        lineTo(13.0f, 11.9f)
        lineTo(15.5f, 9.4f)
        lineTo(14.1f, 8.0f)
        lineTo(11.6f, 10.5f)
        close()
    }

    val Back = Icons.AutoMirrored.Filled.ArrowBack
    val Add = Icons.Filled.Add
    val Check = Icons.Filled.Check
    val CheckCircle = Icons.Filled.CheckCircle
    val Close = Icons.Filled.Close
    val Delete = Icons.Filled.Delete
    val Edit = Icons.Filled.Edit
    val Refresh = Icons.Filled.Refresh
    val Settings = Icons.Filled.Settings
}

private inline fun makeIcon(
    name: String,
    evenOdd: Boolean,
    block: PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = SolidColor(Color.White),
        pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
    ) { block() }
}.build()
