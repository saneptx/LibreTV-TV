package org.libretv.desktop

import java.awt.Font
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.TrayIcon
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * 启动诊断：把启动各阶段耗时写进 app/startup.log；启动后一分钟内界面线程卡住超过 1 秒时，
 * 记下它当时在做什么，便于查「打开时卡住」。
 */
object Startup {
    private val t0 = System.currentTimeMillis()
    private var file: File? = null
    fun begin(home: File) {
        file = File(home, "app/startup.log").apply { parentFile.mkdirs(); writeText("") }
        mark("启动")
        Thread({
            val until = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < until) {
                val flag = java.util.concurrent.atomic.AtomicBoolean(false)
                val sent = System.currentTimeMillis()
                SwingUtilities.invokeLater { flag.set(true) }
                Thread.sleep(1000)
                if (!flag.get()) {
                    val edt = Thread.getAllStackTraces().entries.firstOrNull { it.key.name.startsWith("AWT-EventQueue") }
                    mark("界面线程已卡 ${System.currentTimeMillis() - sent} ms：\n" + (edt?.value?.take(25)?.joinToString("\n") { "    at $it" } ?: "（找不到界面线程）"))
                    while (!flag.get()) Thread.sleep(100)
                    mark("界面线程恢复，共卡 ${System.currentTimeMillis() - sent} ms")
                }
            }
        }, "supertv-startup-watch").apply { isDaemon = true }.start()
    }
    @Synchronized fun mark(what: String) {
        val line = "[%6d ms] %s".format(System.currentTimeMillis() - t0, what)
        runCatching { file?.appendText(line + "\n") }
        if (System.getenv("SUPERTV_DEBUG") != null) System.err.println(line)
    }
}

/** 系统托盘图标。自己建 AWT 菜单并指定中文字体，否则 Windows 上菜单里的中文显示成方框。 */
class SystemTray(onOpen: () -> Unit, onQuit: () -> Unit) {
    private val icon: TrayIcon? = runCatching {
        if (!java.awt.SystemTray.isSupported()) return@runCatching null
        val tray = java.awt.SystemTray.getSystemTray()
        val image = SystemTray::class.java.getResourceAsStream("/supertv.png")!!.use { ImageIO.read(it) }
        val font = Font(if (LocalService.windows) "Microsoft YaHei UI" else "PingFang SC", Font.PLAIN, 12)
        val menu = PopupMenu().apply {
            this.font = font
            add(MenuItem("打开 SuperTV").apply { this.font = font; addActionListener { SwingUtilities.invokeLater(onOpen) } })
            addSeparator()
            add(MenuItem("退出 SuperTV").apply { this.font = font; addActionListener { SwingUtilities.invokeLater(onQuit) } })
        }
        TrayIcon(image, "SuperTV", menu).apply {
            isImageAutoSize = true
            addActionListener { SwingUtilities.invokeLater(onOpen) }
            tray.add(this)
        }
    }.getOrNull()
    fun remove() { icon?.let { runCatching { java.awt.SystemTray.getSystemTray().remove(it) } } }
}
