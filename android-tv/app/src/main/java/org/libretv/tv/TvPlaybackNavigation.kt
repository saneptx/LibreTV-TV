package org.libretv.tv

/** 原网页遥控器状态机，独立于 View 以验证迁移前后的按键行为。 */
internal object TvPlaybackNavigation {
    enum class Key { BACK, MENU, PLAY, CONFIRM, LEFT, RIGHT, UP, DOWN }
    enum class Action { CLOSE_PANEL, SETTINGS, TOGGLE_PLAY, CLICK, MOVE_FOCUS, PREVIEW_BACKWARD, PREVIEW_FORWARD, COMMIT_SEEK, CANCEL_SEEK, HIDE_HUD, EXIT, FOCUS_PLAY, FOCUS_PROGRESS, EPISODES }
    data class State(val hud: Boolean, val controlsFocused: Boolean, val seeking: Boolean, val panel: Boolean=false, val settings: Boolean=false)
    fun action(s: State,key: Key): Action = when(key) {
        Key.BACK -> when { s.panel -> Action.CLOSE_PANEL; s.seeking -> Action.CANCEL_SEEK; s.hud -> Action.HIDE_HUD; else -> Action.EXIT }
        Key.MENU -> if(s.settings) Action.CLOSE_PANEL else Action.SETTINGS
        Key.PLAY -> Action.TOGGLE_PLAY
        Key.CONFIRM -> when { s.panel -> Action.CLICK; s.seeking -> Action.COMMIT_SEEK; s.hud && s.controlsFocused -> Action.CLICK; else -> Action.TOGGLE_PLAY }
        Key.LEFT,Key.RIGHT -> if(s.panel || s.hud && s.controlsFocused && !s.seeking) Action.MOVE_FOCUS else if(key==Key.LEFT) Action.PREVIEW_BACKWARD else Action.PREVIEW_FORWARD
        Key.UP -> if(s.panel) Action.MOVE_FOCUS else if(s.hud && s.controlsFocused) Action.FOCUS_PROGRESS else Action.EPISODES
        Key.DOWN -> if(s.panel) Action.MOVE_FOCUS else Action.FOCUS_PLAY
    }
}
