package org.libretv.tv

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import org.libretv.tv.core.CoreBackend

/** 手机 / 平板入口：界面、播放器、数据层全部是原生实现。 */
@UnstableApi
class PhoneActivity : Activity() {
    private lateinit var backend: CoreBackend
    private lateinit var player: NativePlayerHost
    private lateinit var link: TvLink
    private lateinit var screen: PhoneUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.rgb(16, 16, 18)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        backend = CoreBoot.open(this) { if (::screen.isInitialized) screen.onBoot(it) }
        player = NativePlayerHost(this) { if (::screen.isInitialized) screen.playerPage.onState(it) }
        link = TvLink(applicationContext)
        screen = PhoneUi(this, backend, player, link) { recreate() }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(screen.view, FrameLayout.LayoutParams(-1, -1))
        // 系统栏、刘海和输入法占用的区域留给系统；全屏播放时这些区域为零
        // 留白完全由这里决定，不让系统再替内容区留一遍
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (screen.immersive) view.setPadding(0, 0, 0, 0)
            else if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else @Suppress("DEPRECATION") view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::screen.isInitialized && screen.back()) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }
    override fun onResume() {
        super.onResume()
        if (::player.isInitialized) player.resumeForLifecycle()
    }
    override fun onPause() {
        if (::screen.isInitialized) screen.playerPage.save()
        if (::player.isInitialized) player.pauseForLifecycle()
        super.onPause()
    }
    override fun onStop() {
        // 转入后台后随时可能被系统回收：把还没写盘的进度和设置落盘
        if (::backend.isInitialized) org.libretv.tv.core.Pool.ops.execute { backend.flush() }
        super.onStop()
    }
    override fun onDestroy() {
        if (::screen.isInitialized) screen.release()
        if (::link.isInitialized) link.close()
        if (::player.isInitialized) player.release()
        if (::backend.isInitialized) backend.release()
        super.onDestroy()
    }
}
