package com.openchat.android.ui.terminalui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openchat.android.AppGraph
import com.openchat.android.core.storage.AppSettings
import com.openchat.android.terminal.TerminalSession
import com.openchat.android.ui.theme.TermPalette
import kotlin.math.roundToInt

/**
 * Terminal tab (spec §6): real PTY rendering over [TerminalSession]'s VT buffer.
 * - Canvas grid renderer; repaints on session [TerminalSession.revision] changes.
 *   Scrollback is reachable via ▲/▼ overlay buttons and vertical drag (0..scrollbackCount).
 * - Invisible always-empty text field sends every IME insertion straight to the PTY;
 *   hardware keyboard keys are translated (Enter/Backspace/Tab/Esc/arrows/Ctrl+letter)
 *   via onPreviewKeyEvent. CTRL is also a latch: the next letter goes out as control code.
 * - Extra keys row (ESC/CTRL/TAB/- /| and arrows), copy/paste, font size +/− (settings),
 *   session switcher + "New session", Kill, and a dead-session restart overlay.
 *
 * TerminalManager removes dead sessions from its list, so the currently shown
 * session is held in composition state — the "Session ended (exit code X)" card
 * stays visible until the user restarts or switches.
 */

/** 6×6×6 color cube component levels for xterm 256-color decoding. */
private val CubeLevels = intArrayOf(0, 95, 135, 175, 215, 255)

/**
 * Pure decoder for a packed terminal color attribute value (9-bit field).
 * Encoding per the TerminalBuffer contract:
 *  - 0        → default → [Color.Unspecified] (caller substitutes its default)
 *  - 1..16    → 16-color palette index into [palette] (TermPalette.dark / light)
 *  - 17..272  → xterm-256 index stored as (xterm index + 17):
 *               idx 0..15 → base palette, idx < 216 → 6×6×6 cube
 *               [0,95,135,175,215,255], else grayscale 8+(idx-216)*10 (clamped)
 */
fun attrColor(v: Int, palette: Map<Int, Color>): Color = when {
    v <= 0 -> Color.Unspecified
    v <= 16 -> palette[v] ?: Color.Unspecified
    v <= 272 -> indexedColor(v - 17, palette)
    else -> Color.Unspecified
}

/** xterm-256 index → [Color]; indices 0..15 map to the 16-color [palette]. */
private fun indexedColor(idx: Int, palette: Map<Int, Color>): Color = when {
    idx < 16 -> palette[idx + 1] ?: Color.Unspecified
    idx < 216 -> Color(CubeLevels[idx / 36], CubeLevels[(idx % 36) / 6], CubeLevels[idx % 6])
    else -> {
        val level = (8 + (idx - 216) * 10).coerceIn(0, 255)
        Color(level, level, level)
    }
}

/** Resolves an attribute value, falling back to [default] when unspecified. */
private fun resolveAttr(v: Int, palette: Map<Int, Color>, default: Color): Color {
    val c = attrColor(v, palette)
    return if (c.isUnspecified) default else c
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val settings by AppGraph.settings.settings.collectAsState()
    val sessions by AppGraph.terminal.sessions.collectAsState()
    var active by remember { mutableStateOf<TerminalSession?>(null) }

    // First composition: create the default shell when nothing exists yet,
    // otherwise adopt the newest session (e.g. one started via Attach).
    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) {
            active = AppGraph.terminal.create(title = "Ubuntu shell", cwd = "/root")
        } else {
            active = sessions.lastOrNull()
        }
    }

    val session = active

    Scaffold(topBar = { TopAppBar(title = { Text("Terminal") }) }) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            if (session == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Starting shell…", modifier = Modifier.padding(top = 8.dp))
                }
            } else {
                TerminalView(
                    session = session,
                    settings = settings,
                    sessions = sessions,
                    onSwitchSession = { active = AppGraph.terminal.get(it) ?: sessions.lastOrNull() },
                    onNewSession = { active = AppGraph.terminal.create(title = "Ubuntu shell", cwd = "/root") },
                )
            }
        }
    }
}

@Composable
private fun TerminalView(
    session: TerminalSession,
    settings: AppSettings,
    sessions: List<TerminalSession>,
    onSwitchSession: (String) -> Unit,
    onNewSession: () -> Unit,
) {
    val alive by session.alive.collectAsState()
    val exitCode by session.exitCode.collectAsState()
    val revision by session.revision.collectAsState()
    val ts = settings.terminal

    val palette = if (ts.theme == "light") TermPalette.light else TermPalette.dark
    val fontFamily = if (ts.fontFamily == "default") FontFamily.Default else FontFamily.Monospace
    val textStyle = TextStyle(
        fontFamily = fontFamily,
        fontSize = ts.fontSizeSp.sp,
        fontWeight = FontWeight.Normal,
    )
    val measurer = rememberTextMeasurer()
    val cellSize = remember(measurer, textStyle) {
        measurer.measure(AnnotatedString("M"), textStyle).size
    }
    val cellW = cellSize.width.coerceAtLeast(1)
    val cellH = cellSize.height.coerceAtLeast(1)

    val fgDefault = MaterialTheme.colorScheme.onBackground
    val bgDefault = MaterialTheme.colorScheme.background

    var scrollOffset by remember { mutableIntStateOf(0) }
    var ctrlMode by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val focusRequester = remember { FocusRequester() }
    val inputState = remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    // Scrollback depth (re-read per revision so the ▲/▼ overlay stays fresh).
    val scrollbackCount = remember(revision, session.id) { session.buffer().scrollbackCount() }

    // Resize the PTY when the canvas size, font metrics or session change.
    LaunchedEffect(session.id, canvasSize, cellSize) {
        if (canvasSize.width > 0 && canvasSize.height > 0 && cellSize.width > 0) {
            session.resize(
                (canvasSize.width / cellSize.width).coerceAtLeast(4),
                (canvasSize.height / cellSize.height).coerceAtLeast(2),
            )
        }
    }

    LaunchedEffect(session.id) {
        focusRequester.requestFocus()
    }

    // Keep screen on while the terminal is open (spec §27 setting).
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOnInTerminal) {
        view.keepScreenOn = settings.keepScreenOnInTerminal
        onDispose { view.keepScreenOn = false }
    }

    val blinkTransition = rememberInfiniteTransition(label = "cursorBlink")
    val blinkValue by blinkTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "cursorAlpha",
    )

    Column(Modifier.fillMaxSize()) {
        // ---- toolbar ---------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { menuOpen = true }) {
                    Text(session.title, maxLines = 1)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Sessions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    sessions.forEach { s ->
                        DropdownMenuItem(
                            text = { Text((if (s.id == session.id) "● " else "○ ") + s.title) },
                            onClick = {
                                menuOpen = false
                                onSwitchSession(s.id)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("New session") },
                        onClick = {
                            menuOpen = false
                            onNewSession()
                        },
                    )
                }
            }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(session.buffer().dumpPlain()))
            }) { Text("Copy") }
            TextButton(onClick = {
                val t = clipboard.text?.text
                if (!t.isNullOrEmpty()) session.write(t)
            }) { Text("Paste") }
            TextButton(onClick = {
                AppGraph.settings.update {
                    it.copy(
                        terminal = it.terminal.copy(
                            fontSizeSp = (it.terminal.fontSizeSp - 1f).coerceIn(6f, 28f),
                        )
                    )
                }
            }) { Text("A−") }
            TextButton(onClick = {
                AppGraph.settings.update {
                    it.copy(
                        terminal = it.terminal.copy(
                            fontSizeSp = (it.terminal.fontSizeSp + 1f).coerceIn(6f, 28f),
                        )
                    )
                }
            }) { Text("A+") }
            TextButton(onClick = { session.kill() }) {
                Text("Kill", color = MaterialTheme.colorScheme.error)
            }
        }

        // ---- terminal canvas ---------------------------------------------------
        Box(Modifier.weight(1f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) {
                        detectTapGestures { focusRequester.requestFocus() }
                    }
                    .pointerInput(session.id) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            val max = session.buffer().scrollbackCount()
                            if (max > 0) {
                                scrollOffset =
                                    (scrollOffset + (dragAmount / cellH).roundToInt())
                                        .coerceIn(0, max)
                            }
                        }
                    },
            ) {
                // Snapshot read in the draw phase → the canvas repaints whenever the
                // session buffer revision changes (contract: observe revision, not bytes).
                @Suppress("UNUSED_EXPRESSION") revision
                drawRect(bgDefault, Offset.Zero, size)

                val buffer = session.buffer()
                val rows = buffer.rows()
                val cols = buffer.cols()
                val scrollback = buffer.scrollbackCount()
                val visibleRows = (size.height / cellH).toInt().coerceAtLeast(1)
                val bottomLine = scrollback + rows - scrollOffset
                val topLine = bottomLine - visibleRows

                for (i in 0 until visibleRows) {
                    val line = topLine + i
                    if (line < 0) continue
                    val y = (i * cellH).toFloat()
                    if (line < scrollback) {
                        // Inside the scrollback ring (any scrollOffset > 0 lands here).
                        val text = buffer.scrollbackLine(line)
                        if (text.isNotBlank()) {
                            drawText(
                                measurer,
                                text,
                                topLeft = Offset(0f, y),
                                style = textStyle.copy(color = fgDefault),
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    } else {
                        val row = line - scrollback
                        if (row >= rows) continue
                        val text = buffer.lineText(row)
                        val attrs = buffer.lineAttrs(row)
                        if (text.isNotEmpty()) {
                            val styled = buildAnnotatedString {
                                val n = minOf(text.length, attrs.size)
                                var idx = 0
                                while (idx < n) {
                                    val a = attrs[idx]
                                    var end = idx + 1
                                    while (end < n && attrs[end] == a) end++
                                    val fgV = a and 0x1FF
                                    val bgV = (a shr 9) and 0x1FF
                                    val bold = (a and (1 shl 18)) != 0
                                    val italic = (a and (1 shl 19)) != 0
                                    val reverse = (a and (1 shl 20)) != 0
                                    val fg = if (reverse) resolveAttr(bgV, palette, fgDefault)
                                    else resolveAttr(fgV, palette, fgDefault)
                                    pushStyle(
                                        SpanStyle(
                                            color = fg,
                                            fontWeight = if (bold) FontWeight.Bold else null,
                                            fontStyle = if (italic) FontStyle.Italic else null,
                                        )
                                    )
                                    append(text.substring(idx, end))
                                    pop()
                                    val bg = if (reverse) resolveAttr(fgV, palette, fgDefault)
                                    else attrColor(bgV, palette)
                                    if (!bg.isUnspecified) {
                                        drawRect(
                                            bg,
                                            Offset((idx * cellW).toFloat(), y),
                                            Size(
                                                ((end - idx) * cellW).toFloat(),
                                                cellH.toFloat(),
                                            ),
                                        )
                                    }
                                    idx = end
                                }
                                if (text.length > n) append(text.substring(n))
                            }
                            drawText(
                                measurer,
                                styled,
                                topLeft = Offset(0f, y),
                                style = textStyle,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // Cursor block, only on the live view (not while reading scrollback).
                if (scrollOffset == 0) {
                    val cr = buffer.cursorRow()
                    val cc = buffer.cursorCol()
                    if (cr in 0 until rows && cc in 0 until cols) {
                        val cursorLineAbs = scrollback + cr
                        val yPos = (cursorLineAbs - topLine) * cellH
                        if (yPos >= 0 && yPos < size.height) {
                            val alpha = if (ts.cursorBlink) blinkValue else 1f
                            drawRect(
                                fgDefault.copy(alpha = alpha),
                                topLeft = Offset((cc * cellW).toFloat(), yPos.toFloat()),
                                size = Size(cellW.toFloat(), cellH.toFloat()),
                            )
                        }
                    }
                }
            }

            // ---- scrollback overlay: ▲ / ▼ older-newer + tap-to-return pill ----
            if (scrollbackCount > 0) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = {
                            scrollOffset = (scrollOffset + 5).coerceAtMost(scrollbackCount)
                        },
                    ) { Text("▲", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = {
                            scrollOffset = (scrollOffset - 5).coerceAtLeast(0)
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("▼", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
                }
            }
            if (scrollOffset > 0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = { scrollOffset = 0 },
                ) {
                    Text(
                        "scrollback (−$scrollOffset) — tap to return to live",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // Dead-session overlay with real restart (close + create).
            if (!alive) {
                Card(modifier = Modifier.align(Alignment.Center)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Session ended (exit code ${exitCode ?: "?"})")
                        Button(
                            onClick = {
                                AppGraph.terminal.close(session.id)
                                onNewSession()
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Restart shell") }
                    }
                }
            }
        }

        // ---- extra keys row ------------------------------------------------------
        if (ts.showExtraKeys) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExtraKey("ESC") { session.write("\u001B") }
                ExtraKey("CTRL", highlighted = ctrlMode) { ctrlMode = !ctrlMode }
                ExtraKey("TAB") { session.write("\t") }
                ExtraKey("−") { session.write("-") }
                ExtraKey("/") { session.write("/") }
                ExtraKey("|") { session.write("|") }
                ExtraKey("↑") { session.write("\u001B[A") }
                ExtraKey("↓") { session.write("\u001B[B") }
                ExtraKey("←") { session.write("\u001B[D") }
                ExtraKey("→") { session.write("\u001B[C") }
            }
        }

        // ---- invisible always-empty input: IME + hardware keyboard ---------------
        BasicTextField(
            value = inputState.value,
            onValueChange = { v ->
                // The field value stays "" forever; every reported insertion is
                // forwarded to the PTY as raw bytes.
                inputState.value = ""
                if (v.isEmpty()) return@BasicTextField
                if (ctrlMode) {
                    // CTRL latch: the next letters go out as control characters (code & 0x1F).
                    val sb = StringBuilder(v.length)
                    v.forEach { c ->
                        if (c in 'A'..'Z' || c in 'a'..'z') {
                            sb.append((c.uppercaseChar().code and 0x1F).toChar())
                        } else {
                            sb.append(c)
                        }
                    }
                    session.write(sb.toString())
                    ctrlMode = false
                } else {
                    session.write(v)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { e -> handleKey(e, session) },
            textStyle = TextStyle(color = Color.Transparent, fontSize = 10.sp),
            cursorBrush = SolidColor(Color.Transparent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { session.write("\r") }),
        )
    }
}

/** Hardware key handling: Enter/Backspace/Tab/Esc/arrows/Ctrl+letter → PTY bytes. */
private fun handleKey(e: KeyEvent, session: TerminalSession): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    return when (e.key) {
        Key.Enter, Key.NumPadEnter -> {
            session.write("\r"); true
        }
        Key.Backspace -> {
            session.write("\u007F"); true
        }
        Key.Tab -> {
            session.write("\t"); true
        }
        Key.Escape -> {
            session.write("\u001B"); true
        }
        Key.DirectionUp -> {
            session.write("\u001B[A"); true
        }
        Key.DirectionDown -> {
            session.write("\u001B[B"); true
        }
        Key.DirectionRight -> {
            session.write("\u001B[C"); true
        }
        Key.DirectionLeft -> {
            session.write("\u001B[D"); true
        }
        else -> {
            val keyCode = e.nativeKeyEvent.keyCode
            val ctrl = e.isCtrlPressed
            when {
                ctrl && keyCode in AndroidKeyEvent.KEYCODE_A..AndroidKeyEvent.KEYCODE_Z -> {
                    session.write(((keyCode - AndroidKeyEvent.KEYCODE_A + 1).toChar()).toString())
                    true
                }
                ctrl && keyCode == AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> {
                    session.write("\u001B"); true
                }
                ctrl && keyCode == AndroidKeyEvent.KEYCODE_BACKSLASH -> {
                    session.write("\u001C"); true
                }
                ctrl && keyCode == AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> {
                    session.write("\u001D"); true
                }
                ctrl && (keyCode == AndroidKeyEvent.KEYCODE_AT ||
                    keyCode == AndroidKeyEvent.KEYCODE_SPACE) -> {
                    session.write("\u0000"); true
                }
                else -> false
            }
        }
    }
}

/** Compact extra-key button. */
@Composable
private fun ExtraKey(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (highlighted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
