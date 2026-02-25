package com.pyom

import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class TermuxViewFactory(private val messenger: BinaryMessenger) :
    PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView =
        PyomTerminalPlatformView(context, viewId, messenger)
}

class PyomTerminalPlatformView(
    private val context: Context,
    viewId: Int,
    messenger: BinaryMessenger
) : PlatformView {

    private val terminalView = TerminalView(context, null)
    private var session: TerminalSession? = null
    private val channel = MethodChannel(messenger, "com.pyom/termux_terminal_$viewId")

    init {
        terminalView.setBackgroundColor(Color.parseColor("#1E1E2E"))
        terminalView.setTextSize(14)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.requestFocus()

        terminalView.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float = scale

            override fun onSingleTapUp(e: MotionEvent?) {
                // Show keyboard on tap
                terminalView.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

            override fun onLongPress(event: MotionEvent?): Boolean {
                // Long press = paste from clipboard
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(terminalView, InputMethodManager.SHOW_FORCED)
                return true
            }

            override fun readControlKey(): Boolean = false
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
            override fun onEmulatorSet() {}
            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        })

        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startSession" -> {
                    val shellPath = call.argument<String>("shellPath") ?: "/system/bin/sh"
                    val cwd       = call.argument<String>("cwd") ?: "/"
                    val env       = call.argument<List<String>>("env") ?: emptyList()
                    val args      = call.argument<List<String>>("args")
                    startSession(shellPath, cwd, env, args)
                    result.success(null)
                }
                "sendInput" -> {
                    session?.write(call.argument<String>("input") ?: "")
                    result.success(null)
                }
                "resize" -> {
                    val rows = call.argument<Int>("rows") ?: 24
                    val cols = call.argument<Int>("cols") ?: 80
                    session?.updateSize(cols, rows, 0, 0)
                    terminalView.updateSize()
                    result.success(null)
                }
                "kill" -> { session?.finishIfRunning(); result.success(null) }
                else -> result.notImplemented()
            }
        }
    }

    private fun startSession(shellPath: String, cwd: String, env: List<String>, argsList: List<String>? = null) {
        session?.finishIfRunning()
        val argsArray = argsList?.toTypedArray() ?: arrayOf(shellPath)
        val newSession = TerminalSession(
            shellPath, cwd, argsArray, env.toTypedArray(), 2000,
            object : TerminalSessionClient {
                override fun onTextChanged(s: TerminalSession) { terminalView.onScreenUpdated() }
                override fun onTitleChanged(s: TerminalSession) {}
                override fun onSessionFinished(s: TerminalSession) {
                    channel.invokeMethod("onSessionFinished", mapOf("exitCode" to s.getExitStatus()))
                }
                override fun onCopyTextToClipboard(s: TerminalSession, text: String?) {}
                override fun onPasteTextFromClipboard(s: TerminalSession?) {}
                override fun onBell(s: TerminalSession) {}
                override fun onColorsChanged(s: TerminalSession) {}
                override fun onTerminalCursorStateChange(state: Boolean) {}
                override fun setTerminalShellPid(s: TerminalSession, pid: Int) {}
                override fun getTerminalCursorStyle(): Int? = 0
                override fun logError(tag: String?, message: String?) {}
                override fun logWarn(tag: String?, message: String?) {}
                override fun logInfo(tag: String?, message: String?) {}
                override fun logDebug(tag: String?, message: String?) {}
                override fun logVerbose(tag: String?, message: String?) {}
                override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                override fun logStackTrace(tag: String?, e: Exception?) {}
            }
        )
        session = newSession
        terminalView.attachSession(newSession)
        newSession.initializeEmulator(80, 24, 0, 0)
        terminalView.requestFocus()
        // Auto-show keyboard
        terminalView.postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    override fun getView(): View = terminalView
    override fun dispose() {
        session?.finishIfRunning()
        channel.setMethodCallHandler(null)
    }
}
