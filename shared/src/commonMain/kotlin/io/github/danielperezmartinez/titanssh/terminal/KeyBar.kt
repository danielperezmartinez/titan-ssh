package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.terminal.TerminalKeys.SpecialKey

/**
 * What a key on the key bar does. The same bar serves the terminal and the
 * mouse pad, so each action says how it reaches both: as PTY bytes
 * ([terminalBytes]) and as agent input frames ([desktopFrames]).
 */
sealed interface KeyBarAction {
    /** A sticky modifier (InputKeys `MOD_*` bit), applied to the next key or character. */
    data class Modifier(val mod: Int) : KeyBarAction

    /**
     * A named key. [terminal] is null for the keys a terminal has no sequence
     * for (Print Screen, the media keys…); [desktopMods] are pressed with it
     * on the desktop (Shift for Shift+Tab).
     */
    data class Key(val terminal: SpecialKey?, val desktop: Int, val desktopMods: Int = 0) : KeyBarAction

    /** Types [text], as the keyboard would. */
    data class Text(val text: String) : KeyBarAction

    /** Ctrl + [letter] in one tap. */
    data class Ctrl(val letter: Char) : KeyBarAction

    /** Pastes the clipboard. */
    data object Paste : KeyBarAction
}

/** The sections of the key catalog, in the order the editor lists them. */
enum class KeyBarGroup(val title: String) {
    MODIFIERS("Modificadores"),
    CONTROL("Control"),
    NAVIGATION("Navegación"),
    SHORTCUTS("Atajos"),
    FUNCTION("Funciones"),
    SYMBOLS("Símbolos"),
    DESKTOP("Escritorio remoto"),
    ACTIONS("Acciones"),
}

/**
 * One key the bar can hold. [id] is what the settings store
 * (`AppSettings.keyBar`), so it never changes once published.
 */
data class KeyBarKey(val id: String, val label: String, val group: KeyBarGroup, val action: KeyBarAction) {
    /** False for the keys only a remote desktop has (Win, Print Screen, the media keys). */
    val inTerminal: Boolean
        get() = when (action) {
            is KeyBarAction.Modifier -> action.mod != InputKeys.MOD_META
            is KeyBarAction.Key -> action.terminal != null
            else -> true
        }

    /**
     * The bytes this key sends to a terminal with the sticky [modifiers], or
     * null for a modifier, Paste, and the keys a terminal lacks.
     */
    fun terminalBytes(modifiers: Int): ByteArray? = when (action) {
        is KeyBarAction.Key -> action.terminal?.let { TerminalKeys.special(it, modifiers) }
        is KeyBarAction.Text -> TerminalKeys.typed(action.text, modifiers)
        is KeyBarAction.Ctrl -> TerminalKeys.typed(action.letter.toString(), modifiers or InputKeys.MOD_CTRL)
        is KeyBarAction.Modifier, KeyBarAction.Paste -> null
    }

    /**
     * The frames this key sends to a remote desktop with the sticky
     * [modifiers], or null for a modifier and Paste.
     */
    fun desktopFrames(modifiers: Int): List<AgentFrame>? = when (action) {
        is KeyBarAction.Key -> listOf(AgentFrame.Key(action.desktop, modifiers or action.desktopMods))
        is KeyBarAction.Text -> MousepadTyping.typed(action.text, modifiers)
        is KeyBarAction.Ctrl -> MousepadTyping.typed(action.letter.toString(), modifiers or InputKeys.MOD_CTRL)
        is KeyBarAction.Modifier, KeyBarAction.Paste -> null
    }
}

/**
 * The keys the bar above the keyboard can hold, and the bar a user who never
 * configured it gets. One bar for the whole app: the terminal and the mouse
 * pad show the same keys ([[Barra de teclas configurable con arrastre]]).
 */
object KeyBar {

    val catalog: List<KeyBarKey> = buildList {
        fun key(id: String, label: String, group: KeyBarGroup, action: KeyBarAction) =
            add(KeyBarKey(id, label, group, action))

        key("ctrl", "Ctrl", KeyBarGroup.MODIFIERS, KeyBarAction.Modifier(InputKeys.MOD_CTRL))
        key("alt", "Alt", KeyBarGroup.MODIFIERS, KeyBarAction.Modifier(InputKeys.MOD_ALT))
        key("shift", "Mayús", KeyBarGroup.MODIFIERS, KeyBarAction.Modifier(InputKeys.MOD_SHIFT))
        key("win", "Win", KeyBarGroup.MODIFIERS, KeyBarAction.Modifier(InputKeys.MOD_META))

        key("esc", "Esc", KeyBarGroup.CONTROL, KeyBarAction.Key(SpecialKey.ESCAPE, InputKeys.ESCAPE))
        key("tab", "Tab", KeyBarGroup.CONTROL, KeyBarAction.Key(SpecialKey.TAB, InputKeys.TAB))
        key(
            "backtab", "Mayús+Tab", KeyBarGroup.CONTROL,
            KeyBarAction.Key(SpecialKey.BACK_TAB, InputKeys.TAB, desktopMods = InputKeys.MOD_SHIFT),
        )
        key("enter", "Intro", KeyBarGroup.CONTROL, KeyBarAction.Key(SpecialKey.ENTER, InputKeys.ENTER))

        key("left", "←", KeyBarGroup.NAVIGATION, KeyBarAction.Key(SpecialKey.LEFT, InputKeys.ARROW_LEFT))
        key("down", "↓", KeyBarGroup.NAVIGATION, KeyBarAction.Key(SpecialKey.DOWN, InputKeys.ARROW_DOWN))
        key("up", "↑", KeyBarGroup.NAVIGATION, KeyBarAction.Key(SpecialKey.UP, InputKeys.ARROW_UP))
        key("right", "→", KeyBarGroup.NAVIGATION, KeyBarAction.Key(SpecialKey.RIGHT, InputKeys.ARROW_RIGHT))
        key("home", "Inicio", KeyBarGroup.NAVIGATION, KeyBarAction.Key(SpecialKey.HOME, InputKeys.HOME))
        key("end", "Fin", KeyBarGroup.NAVIGATION, KeyBarAction.Key(SpecialKey.END, InputKeys.END))
        key("pgup", "RePág", KeyBarGroup.NAVIGATION, KeyBarAction.Key(SpecialKey.PAGE_UP, InputKeys.PAGE_UP))
        key("pgdn", "AvPág", KeyBarGroup.NAVIGATION, KeyBarAction.Key(SpecialKey.PAGE_DOWN, InputKeys.PAGE_DOWN))
        key("ins", "Insert", KeyBarGroup.NAVIGATION, KeyBarAction.Key(SpecialKey.INSERT, InputKeys.INSERT))
        key("del", "Supr", KeyBarGroup.NAVIGATION, KeyBarAction.Key(SpecialKey.DELETE, InputKeys.DELETE))

        "cdzlrae".forEach { letter ->
            key("ctrl-$letter", "^${letter.uppercaseChar()}", KeyBarGroup.SHORTCUTS, KeyBarAction.Ctrl(letter))
        }

        val functionKeys = listOf(
            SpecialKey.F1, SpecialKey.F2, SpecialKey.F3, SpecialKey.F4, SpecialKey.F5, SpecialKey.F6,
            SpecialKey.F7, SpecialKey.F8, SpecialKey.F9, SpecialKey.F10, SpecialKey.F11, SpecialKey.F12,
        )
        functionKeys.forEachIndexed { i, special ->
            val n = i + 1
            key("f$n", "F$n", KeyBarGroup.FUNCTION, KeyBarAction.Key(special, InputKeys.function(n)))
        }

        SYMBOLS.forEach { symbol ->
            key("sym:$symbol", symbol.toString(), KeyBarGroup.SYMBOLS, KeyBarAction.Text(symbol.toString()))
        }

        key("menu", "Menú", KeyBarGroup.DESKTOP, KeyBarAction.Key(null, InputKeys.CONTEXT_MENU))
        key("prtsc", "ImpPnt", KeyBarGroup.DESKTOP, KeyBarAction.Key(null, InputKeys.PRINT_SCREEN))
        key("vol-down", "Vol-", KeyBarGroup.DESKTOP, KeyBarAction.Key(null, InputKeys.VOLUME_DOWN))
        key("vol-up", "Vol+", KeyBarGroup.DESKTOP, KeyBarAction.Key(null, InputKeys.VOLUME_UP))
        key("mute", "Silencio", KeyBarGroup.DESKTOP, KeyBarAction.Key(null, InputKeys.VOLUME_MUTE))
        key("play", "Play/Pausa", KeyBarGroup.DESKTOP, KeyBarAction.Key(null, InputKeys.MEDIA_PLAY_PAUSE))
        key("prev", "Anterior", KeyBarGroup.DESKTOP, KeyBarAction.Key(null, InputKeys.MEDIA_PREVIOUS))
        key("next", "Siguiente", KeyBarGroup.DESKTOP, KeyBarAction.Key(null, InputKeys.MEDIA_NEXT))

        key("paste", "Pegar", KeyBarGroup.ACTIONS, KeyBarAction.Paste)
    }

    private val byId: Map<String, KeyBarKey> = catalog.associateBy { it.id }

    /**
     * The bar of a user who never configured it: the terminal's original keys,
     * with arrows drawn as arrows, plus Shift and Paste.
     */
    val defaultIds: List<String> = listOf(
        "esc", "tab", "ctrl", "alt", "shift", "left", "down", "up", "right",
        "sym:|", "sym:/", "sym:-", "sym:~", "paste",
    )

    /** The catalog key with [id], or null for an id this build does not know. */
    fun key(id: String): KeyBarKey? = byId[id]

    /**
     * The bar saved as [ids] (null: never configured, so [defaultIds]). Unknown
     * ids, from a newer build or a hand-edited file, are skipped, and so are
     * repeats.
     */
    fun resolve(ids: List<String>?): List<KeyBarKey> =
        (ids ?: defaultIds).distinct().mapNotNull(byId::get)

    private const val SYMBOLS = "|/\\-_~`'\":;=+*&$#!?@%^<>{}[]()"
}
