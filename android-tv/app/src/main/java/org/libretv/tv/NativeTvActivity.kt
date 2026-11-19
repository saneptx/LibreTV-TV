package org.libretv.tv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import org.libretv.tv.core.CoreBackend

/** 电视 / 投影仪入口：界面、播放器、数据层全部是原生实现。 */
@UnstableApi
class NativeTvActivity : Activity() {
    private lateinit var backend: CoreBackend
    private lateinit var player: NativePlayerHost
    private lateinit var screen: NativeTvUi
    private var companion: TvCompanion? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideBars()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        backend = CoreBoot.open(this) { if (::screen.isInitialized) screen.onBoot(it) }
        player = NativePlayerHost(this) { if (::screen.isInitialized) screen.onPlaybackState(it) }
        screen = NativeTvUi(this, backend, player) { recreate() }
        // 手机伴侣服务启动失败（端口占用等）不影响看片
        companion = runCatching { TvCompanion(this, screen.companion) }.getOrNull()?.also { screen.companionService = it }
        player.container.addView(screen.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 片头黑幕盖在界面层之上（界面层的焦点光晕画在它自己的 overlay 里，不能透出来）
        player.container.addView(screen.curtain, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(player.container)
        if (BuildConfig.DEBUG) android.util.Log.i("LibreTV", "hardware decoders ${DecoderCapabilities.json}")
    }
    private fun hideBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::screen.isInitialized && screen.dispatchKey(event)) return true
        return super.dispatchKeyEvent(event)
    }
    override fun onResume() {
        super.onResume(); hideBars()
        if (::player.isInitialized) player.resumeForLifecycle()
    }
    override fun onPause() {
        if (::screen.isInitialized) screen.savePlayback()
        if (::player.isInitialized) player.pauseForLifecycle()
        super.onPause()
    }
    override fun onStop() {
        // 转入后台后随时可能被系统回收：把还没写盘的进度和设置落盘
        if (::backend.isInitialized) org.libretv.tv.core.Pool.ops.execute { backend.flush() }
        super.onStop()
    }
    override fun onDestroy() {
        companion?.release()
        if (::screen.isInitialized) screen.release()
        if (::player.isInitialized) player.release()
        if (::backend.isInitialized) backend.release()
        super.onDestroy()
    }
}
