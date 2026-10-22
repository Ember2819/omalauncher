package dev.omalauncher.ui

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView

/** What a focused node holds of a key on it: whether its press was seen here, and whether holding it long-pressed. */
private class KeyHold {
    var down = false
    var longed = false
}

/** Keys that tap a focused node: a gamepad's A comes as itself, or as the D-pad's center where nothing takes it. */
private val activateKeys = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar, Key.ButtonA)

/**
 * Lets a keyboard or a gamepad reach something that takes taps: it takes focus once keys are in use (not while
 * touching, like Compose's clickable), so Tab and the arrows move to it. Enter, Space, A or the D-pad's center taps it
 * as the key lifts; holding one, or Menu (a gamepad's Select), long-presses it. [onFocus] follows its focus, to show it.
 */
@Composable
fun Modifier.keyActivated(
    onTap: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    onFocus: (Boolean) -> Unit = {},
): Modifier {
    val inputMode = LocalInputModeManager.current
    val hold = remember { KeyHold() }
    return this
        .focusProperties { canFocus = inputMode.inputMode == InputMode.Keyboard }
        .onFocusChanged {
            if (!it.isFocused) hold.down = false
            onFocus(it.isFocused)
        }.onKeyEvent { e ->
            when {
                e.key in activateKeys ->
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            if (e.nativeKeyEvent.repeatCount == 0) {
                                hold.down = true
                                hold.longed = false
                            } else if (hold.down && !hold.longed && onLongPress != null && onTap != null) {
                                hold.longed = true
                                onLongPress()
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            // Only a press that started here: Enter that opened this screen lifts on it.
                            val ours = hold.down
                            if (ours && !hold.longed) (onTap ?: onLongPress)?.invoke()
                            hold.down = false
                            ours
                        }
                        else -> false
                    }
                e.key == Key.Menu && onLongPress != null -> {
                    if (e.type == KeyEventType.KeyUp) onLongPress()
                    true
                }
                else -> false
            }
        }.focusable()
}

/**
 * For something that covers home, like a panel: keys' focus goes into it as it appears, while keys are in use (a touch
 * shouldn't focus its text field, raising the keyboard), and stays in it, so the arrows can't reach what's behind.
 * It starts on [start] where that's there, like a panel's ✕, rather than on a text field that keeps the arrows. An
 * arrow with nothing focused in the window, as after a tap, starts there again ([keysStartAgain]).
 *
 * The drawer only wants the last: it opens from the bar, which keeps focus to type in, and the bar is outside it, so
 * [enters] and [traps] are false there.
 */
@Composable
fun Modifier.holdsKeys(
    start: FocusRequester? = null,
    enters: Boolean = true,
    traps: Boolean = true,
): Modifier {
    val inputMode = LocalInputModeManager.current
    val view = LocalView.current
    val entry = remember { FocusRequester() }
    val holder = remember(view, start) { KeyHolder(view) { start?.requestFocus() == true || entry.requestFocus() } }
    DisposableEffect(holder) {
        holders += holder
        onDispose { holders -= holder }
    }
    LaunchedEffect(holder) {
        if (!enters || inputMode.inputMode != InputMode.Keyboard) return@LaunchedEffect
        // Content that isn't laid out yet takes it a frame later.
        if (!holder.enter()) withFrameNanos {}.also { holder.enter() }
    }
    return focusRequester(entry)
        // Only the keys' moves stay in: focus asked for elsewhere, like the next settings page, goes.
        .then(if (traps) Modifier.focusProperties { onExit = { if (requestedFocusDirection in keyMoves) cancelFocusChange() } } else Modifier)
        .focusGroup()
}

/** A [holdsKeys] showing in [view]'s window, and how keys go into it. */
private class KeyHolder(
    val view: View,
    val enter: () -> Boolean,
)

/** The [holdsKeys] showing, newest last. */
private val holders = mutableListOf<KeyHolder>()

/**
 * An arrow with nothing focused in [window] has nowhere to move from, as after a tap: it starts in the newest
 * [holdsKeys] there. Whether one took it.
 */
fun keysStartAgain(window: View): Boolean = holders.lastOrNull { it.view.rootView === window.rootView }?.enter?.invoke() == true

/** The arrows, which move keys' focus. */
val arrowKeys = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

private val keyMoves = setOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right, FocusDirection.Next, FocusDirection.Previous)

/**
 * Something keys move rather than press, like a slider or a drag handle: it takes focus once keys are in use, ringed
 * while it has it, and the arrows along it ([vertical] or not) call [onStep] with -1 or 1, and whether Shift asks for a
 * finer step, again as a held key repeats. Where [onStep] can't go further (false), the arrow moves focus on instead.
 */
@Composable
fun Modifier.keyStepped(
    vertical: Boolean,
    onStep: (by: Int, fine: Boolean) -> Boolean,
): Modifier {
    val inputMode = LocalInputModeManager.current
    var focused by remember { mutableStateOf(false) }
    return this
        .focusProperties { canFocus = inputMode.inputMode == InputMode.Keyboard }
        .onFocusChanged { focused = it.isFocused }
        .onKeyEvent { e ->
            val by =
                when (e.key) {
                    if (vertical) Key.DirectionUp else Key.DirectionLeft -> -1
                    if (vertical) Key.DirectionDown else Key.DirectionRight -> 1
                    else -> return@onKeyEvent false
                }
            e.type != KeyEventType.KeyDown || onStep(by, e.isShiftPressed)
        }.focusable()
        .drawWithContent {
            drawContent()
            if (focused) drawKeyFocus()
        }
}

/**
 * What keys have focus on shows it: a ring in the accent color over a tint of it, with the launcher's corners. A line in
 * the background color inside the ring keeps it apart from what it's around, a button filled with the accent too.
 */
fun DrawScope.drawKeyFocus(radius: Float = 4f * LauncherStyle.cornerScale) {
    val ring = 3.dp.toPx()
    val gap = 2.dp.toPx()
    val r = radius.dp.toPx()
    drawRoundRect(Palette.accent.copy(alpha = 0.15f), cornerRadius = CornerRadius(r))
    drawRoundRect(Palette.accent, Offset(ring / 2, ring / 2), Size(size.width - ring, size.height - ring), CornerRadius(r), style = Stroke(ring))
    val inset = ring + gap / 2
    val inner = CornerRadius((r - ring).coerceAtLeast(0f))
    drawRoundRect(Palette.background, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), inner, style = Stroke(gap))
}

/**
 * What a clickable shows: a press or a hover darkens it as Compose's own does, and keys' focus rings it
 * ([drawKeyFocus]) where Compose's barely tints it, so a keyboard or a gamepad can see where it is.
 */
private object KeyIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)

    override fun equals(other: Any?) = other === this

    override fun hashCode() = 0

    private class Node(
        private val source: InteractionSource,
    ) : Modifier.Node(),
        DrawModifierNode {
        private var pressed = 0
        private var hovered = 0
        private var focused = 0

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect {
                    when (it) {
                        is PressInteraction.Press -> pressed++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed--
                        is HoverInteraction.Enter -> hovered++
                        is HoverInteraction.Exit -> hovered--
                        is FocusInteraction.Focus -> focused++
                        is FocusInteraction.Unfocus -> focused--
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            when {
                pressed > 0 -> drawRect(Color.Black.copy(alpha = 0.3f))
                hovered > 0 -> drawRect(Color.Black.copy(alpha = 0.1f))
            }
            if (focused > 0) drawKeyFocus()
        }
    }
}

/** Gives every clickable in [content] the launcher's [KeyIndication]. */
@Composable
fun KeyIndicated(content: @Composable () -> Unit) = CompositionLocalProvider(LocalIndication provides KeyIndication, content = content)
