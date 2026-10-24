package org.libretv.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import org.libretv.tv.TvPlaybackNavigation.Action.*
import org.libretv.tv.TvPlaybackNavigation.Key.*
import org.libretv.tv.TvPlaybackNavigation.State

class TvPlaybackNavigationTest {
    @Test fun returnClosesPanelThenPreviewThenHudThenExits() {
        assertEquals(CLOSE_PANEL,TvPlaybackNavigation.action(State(true,false,true,panel=true),BACK))
        assertEquals(CANCEL_SEEK,TvPlaybackNavigation.action(State(true,false,true),BACK))
        assertEquals(HIDE_HUD,TvPlaybackNavigation.action(State(true,false,false),BACK))
        assertEquals(EXIT,TvPlaybackNavigation.action(State(false,false,false),BACK))
    }
    @Test fun leftRightMoveControlsButPreviewFromVideoOrTimeline() {
        assertEquals(MOVE_FOCUS,TvPlaybackNavigation.action(State(true,true,false),RIGHT))
        assertEquals(PREVIEW_FORWARD,TvPlaybackNavigation.action(State(true,false,false),RIGHT))
        assertEquals(PREVIEW_BACKWARD,TvPlaybackNavigation.action(State(false,false,false),LEFT))
        assertEquals(PREVIEW_FORWARD,TvPlaybackNavigation.action(State(true,true,true),RIGHT))
    }
    @Test fun upFromControlsFocusesProgressAndNextUpOpensEpisodes() {
        assertEquals(FOCUS_PROGRESS,TvPlaybackNavigation.action(State(true,true,false),UP))
        assertEquals(EPISODES,TvPlaybackNavigation.action(State(true,false,false),UP))
        assertEquals(FOCUS_PLAY,TvPlaybackNavigation.action(State(false,false,true),DOWN))
    }
    @Test fun confirmOnlyCommitsPreviewOrClicksTheFocusedControl() {
        assertEquals(COMMIT_SEEK,TvPlaybackNavigation.action(State(true,false,true),CONFIRM))
        assertEquals(CLICK,TvPlaybackNavigation.action(State(true,true,false),CONFIRM))
        assertEquals(TOGGLE_PLAY,TvPlaybackNavigation.action(State(true,false,false),CONFIRM))
        assertEquals(TOGGLE_PLAY,TvPlaybackNavigation.action(State(false,false,false),CONFIRM))
    }
    @Test fun menuTogglesSettingsAndPanelDirectionsStayInPanel() {
        assertEquals(SETTINGS,TvPlaybackNavigation.action(State(true,false,false,panel=true),MENU))
        assertEquals(CLOSE_PANEL,TvPlaybackNavigation.action(State(true,false,false,panel=true,settings=true),MENU))
        assertEquals(MOVE_FOCUS,TvPlaybackNavigation.action(State(true,false,false,panel=true),DOWN))
        assertEquals(CLICK,TvPlaybackNavigation.action(State(true,false,false,panel=true),CONFIRM))
    }
}
