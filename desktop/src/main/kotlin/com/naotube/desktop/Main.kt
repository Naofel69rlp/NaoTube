/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.naotube.desktop.ui.NaoDesktopRoot
import com.naotube.desktop.ui.WindowControls
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Color
import java.awt.Dimension
import java.awt.Window as AwtWindow
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    startLog()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        System.err.println("Uncaught exception in ${thread.name}")
        error.printStackTrace()
    }
    Nao.init()
    if ("--selftest" in args) {
        exitProcess(SelfTest.run())
    }

    application {
        val state = rememberWindowState(
            placement = if (Nao.settings.startFullscreen.value) WindowPlacement.Fullscreen else WindowPlacement.Floating,
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(1280.dp, 760.dp),
        )
        val controls = remember {
            WindowControls(
                isFullscreen = { state.placement == WindowPlacement.Fullscreen },
                setFullscreen = { full ->
                    state.placement = if (full) WindowPlacement.Fullscreen else WindowPlacement.Floating
                },
            )
        }
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "NaoTube",
            icon = Assets.painter("logo_mark"),
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(800, 450)
                window.background = Color(6, 6, 7)
                WindowsLook.darkTitleBar(window)
            }
            NaoDesktopRoot(controls)
        }
    }
}

/** Everything printed by the app goes to %APPDATA%\NaoTube\naotube.log (there is no console). */
private fun startLog() {
    runCatching {
        val file = File(AppDirs.data, "naotube.log")
        if (file.exists() && file.length() > 2_000_000) file.delete()
        val stream = PrintStream(FileOutputStream(file, true), true, "UTF-8")
        stream.println("---- NaoTube ${BuildInfo.VERSION} ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")
        System.setOut(stream)
        System.setErr(stream)
    }
}

/** Windows 10/11 details: dark title bar matching the app. */
object WindowsLook {
    interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    fun darkTitleBar(window: AwtWindow) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        runCatching {
            val dwm = Native.load("dwmapi", Dwm::class.java)
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            // DWMWA_USE_IMMERSIVE_DARK_MODE (20, Windows 10 2004+ / 11), 19 on older Windows 10
            if (dwm.DwmSetWindowAttribute(hwnd, 20, IntByReference(1), 4) != 0) {
                dwm.DwmSetWindowAttribute(hwnd, 19, IntByReference(1), 4)
            }
            // DWMWA_CAPTION_COLOR (35, Windows 11): same black as the app (COLORREF 0x00BBGGRR)
            dwm.DwmSetWindowAttribute(hwnd, 35, IntByReference(0x00070606), 4)
        }
    }
}
