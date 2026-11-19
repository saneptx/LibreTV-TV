package org.libretv.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** 桌面图标的落点：按设备类型转到电视界面或手机界面。 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, if (isTelevision()) NativeTvActivity::class.java else PhoneActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
    private fun isTelevision(): Boolean {
        val mode = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType
        return mode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION || packageManager.hasSystemFeature("android.software.leanback")
            || !packageManager.hasSystemFeature("android.hardware.touchscreen")
    }
}
