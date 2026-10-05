package top.wkbin.taixu.runtime.gui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.wkbin.taixu.runtime.privilege.PrivilegeManager

data class ScreenObservation(
    val packageName: String,
    val activityName: String,
    val nodes: List<GuiNode>,
    val rawXml: String = "",
) {
    fun toAgentSummary(maxNodes: Int = 80): String = buildString {
        appendLine("[Current Foreground App] $packageName (Activity: $activityName)")
        if (nodes.isEmpty()) {
            appendLine("[Screen Nodes] No interactive nodes detected or loading animation in progress")
        } else {
            val shown = selectForDisplay(maxNodes)
            appendLine("[Interactive & Visual Nodes] (total ${nodes.size}, showing ${shown.size}; id omits package prefix, @x,y is widget center for direct tap)")
            shown.forEach { node ->
                appendLine("- ${node.toCompactString()}")
            }
            if (shown.size < nodes.size) {
                appendLine("[Remaining ${nodes.size - shown.size} nodes omitted (interactive widgets prioritized)...]")
            }
        }
    }

    /**
     * Select nodes to display. Nodes are produced in DFS preorder; a simple
     * take(maxNodes) would drop trailing interactive widgets (send buttons,
     * FABs at bottom/right), making them invisible to the model.
     * Prioritize editable/clickable/scrollable widgets first, then fill remaining
     * slots with text nodes in original order; final list preserves original
     * order to maintain the model's spatial/reading-order understanding.
     */
    private fun selectForDisplay(maxNodes: Int): List<GuiNode> {
        if (nodes.size <= maxNodes) return nodes
        val interactive = nodes.filter { it.editable || it.clickable || it.scrollable }
        if (interactive.size >= maxNodes) return interactive.take(maxNodes)
        val keepIds = interactive.mapTo(HashSet()) { it.id }
        val textBudget = maxNodes - keepIds.size
        nodes.asSequence()
            .filter { it.id !in keepIds }
            .take(textBudget)
            .forEach { keepIds.add(it.id) }
        return nodes.filter { it.id in keepIds }
    }
}

class HostGuiController(
    private val context: Context,
    private val privilegeManager: PrivilegeManager,
    private val toolkit: HostGuiToolkit,
    private val hud: WorkflowGuiHudBridge,
) {
    /**
     * Sense screen state: get current foreground app, Activity, and UI widget tree
     */
    suspend fun observeScreen(onlyInteractive: Boolean = true): Result<ScreenObservation> = withContext(Dispatchers.IO) {
        hud.beginScreenOp("Sensing screen...")
        try {
            runCatching {
                val foreground = getForegroundInfo()
                val dumpPath = "/data/local/tmp/taixu_gui_dump.xml"
                val fallbackDumpPath = "/sdcard/taixu_gui_dump.xml"

                // Prefer dump to /data/local/tmp, fallback to /sdcard
                val dumpResult = privilegeManager.executeShellCommand(
                    "/system/bin/uiautomator dump $dumpPath >/dev/null 2>&1 && /system/bin/cat $dumpPath; /system/bin/rm -f $dumpPath"
                )

                val xmlContent = if (dumpResult.success && dumpResult.stdout.isNotBlank()) {
                    dumpResult.stdout
                } else {
                    val fallback = privilegeManager.executeShellCommand(
                        "/system/bin/uiautomator dump $fallbackDumpPath >/dev/null 2>&1 && /system/bin/cat $fallbackDumpPath; /system/bin/rm -f $fallbackDumpPath"
                    )
                    fallback.stdout
                }

                val nodes = AndroidGuiXmlParser.parse(xmlContent, onlyInteractive)
                // rawXml no longer held in observation result: uiautomator full XML can reach
                // several MB; retaining it would pin the entire node tree in the Java heap on
                // every screen_observe (dead weight with no consumer, previously caused target
                // footprint OOM). The transient string needed for parsing is GC-eligible once
                // this scope exits.
                ScreenObservation(
                    packageName = foreground.first,
                    activityName = foreground.second,
                    nodes = nodes,
                )
            }
        } finally {
            hud.endScreenOp()
        }
    }

    /**
     * Wait for UI to settle after an action.
     * If AccessibilityService is available and has received window events: observe a
     * short window; if no window state change occurred, the UI didn't actually move
     * (pure text input, ineffective tap, etc.) — return immediately; if transitioning,
     * wait for event quiescence up to maxMs.
     * Without service or prior events, fall back to fixed wait, behavior matches pre-refactor.
     */
    suspend fun awaitUiSettled(
        settleWindowMs: Long = 350L,
        quietMs: Long = 200L,
        maxMs: Long = 1_500L,
        fallbackMs: Long = 900L,
    ) {
        if (!AccessibilityGestureBridge.isAvailable() || !UiSettleSignal.hasSignal()) {
            delay(fallbackMs)
            return
        }
        val start = SystemClock.uptimeMillis()
        val before = UiSettleSignal.lastEventAt()
        delay(settleWindowMs)
        if (UiSettleSignal.lastEventAt() == before) return
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if (SystemClock.uptimeMillis() - UiSettleSignal.lastEventAt() >= quietMs) return
            delay(40L)
        }
    }

    suspend fun execute(action: GuiPrimitive): Result<String> {
        hud.beginScreenOp(actionHudLabel(action))
        return try {
            runCatching {
                val result = toolkit.execute(action)
                if (result.success) result.toAgentLine() else error(result.toAgentLine())
            }
        } finally {
            hud.endScreenOp()
        }
    }

    private fun actionHudLabel(action: GuiPrimitive): String = when (action) {
        is GuiPrimitive.Tap -> "Tapping..."
        is GuiPrimitive.DoubleTap -> "Double-tapping..."
        is GuiPrimitive.LongPress -> "Long-pressing..."
        is GuiPrimitive.Swipe -> "Swiping..."
        is GuiPrimitive.Scroll -> "Scrolling..."
        is GuiPrimitive.Key -> "Key ${action.key.name.lowercase()}..."
        is GuiPrimitive.PasteText -> "Pasting/typing..."
    }

    /** Tap screen coordinates (x, y) — auto-fallback: accessibility gesture → cmd input → bin input */
    suspend fun click(x: Int, y: Int): Result<String> = execute(GuiPrimitive.Tap(x, y))

    suspend fun doubleClick(x: Int, y: Int): Result<String> = execute(GuiPrimitive.DoubleTap(x, y))

    suspend fun longPress(x: Int, y: Int, durationMs: Long = 800L): Result<String> =
        execute(GuiPrimitive.LongPress(x, y, durationMs))

    /** Swipe screen: from (x1, y1) to (x2, y2) */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Result<String> =
        execute(GuiPrimitive.Swipe(x1, y1, x2, y2, durationMs))

    suspend fun scroll(
        direction: ScrollDirection,
        distanceRatio: Float = 0.45f,
        durationMs: Long = 350L,
    ): Result<String> = execute(GuiPrimitive.Scroll(direction, distanceRatio, durationMs))

    /**
     * Input text into currently focused widget (CJK via clipboard paste,
     * multi-backend fallback).
     */
    suspend fun inputText(text: String): Result<String> = execute(GuiPrimitive.PasteText(text))

    /** Send system navigation or function key */
    suspend fun sendKey(keyName: String): Result<String> {
        val key = GuiKey.parse(keyName)
            ?: return Result.failure(IllegalArgumentException("Unknown key: $keyName (supported: back/home/recents/enter/delete/paste/power)"))
        return execute(GuiPrimitive.Key(key))
    }

    /**
     * Launch specified Android app
     */
    suspend fun launchApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                "Launched app: $packageName"
            } else {
                // Fallback monkey wake
                val res = privilegeManager.executeShellCommand(
                    "/system/bin/monkey -p ${shellQuote(packageName)} -c android.intent.category.LAUNCHER 1"
                )
                if (res.success) "Launched app via shell: $packageName" else error("Failed to launch app $packageName: ${res.stderr}")
            }
        }
    }

    /**
     * Capture current screen and save to specified path
     */
    suspend fun captureScreenshot(targetPath: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val res = privilegeManager.executeShellCommand("/system/bin/screencap -p ${shellQuote(targetPath)}")
            if (res.success) "Screenshot saved to $targetPath" else error(res.stderr.ifBlank { "Screenshot failed" })
        }
    }

    /** Send broadcast via Context; caller decides whether to fall back to privileged am broadcast on failure. */
    fun sendBroadcastIntent(
        action: String,
        packageName: String? = null,
        component: String? = null,
        extras: Map<String, String> = emptyMap(),
    ): Result<String> = runCatching {
        val intent = Intent(action).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        packageName?.takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
        component?.takeIf { it.isNotBlank() }?.let { intent.component = parseComponent(it) }
        putExtras(intent, extras)
        context.sendBroadcast(intent)
        "Broadcast sent: $action" + (packageName?.let { " → $it" } ?: "")
    }

    fun startActivityIntent(
        component: String? = null,
        action: String? = null,
        dataUri: String? = null,
        mimeType: String? = null,
        extras: Map<String, String> = emptyMap(),
    ): Result<String> = runCatching {
        require(!component.isNullOrBlank() || !action.isNullOrBlank() || !dataUri.isNullOrBlank()) {
            "start_activity requires at least one of component, action, or dataUri"
        }
        val intent = Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        component?.takeIf { it.isNotBlank() }?.let { intent.component = parseComponent(it) }
        action?.takeIf { it.isNotBlank() }?.let { intent.action = it }
        dataUri?.takeIf { it.isNotBlank() }?.let { uri ->
            if (mimeType.isNullOrBlank()) intent.data = Uri.parse(uri) else intent.setDataAndType(Uri.parse(uri), mimeType)
        }
        putExtras(intent, extras)
        context.startActivity(intent)
        "Activity launched: " + listOfNotNull(component, action, dataUri).joinToString(" ")
    }

    suspend fun forceStopApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val res = privilegeManager.executeShellCommand("/system/bin/am force-stop ${shellQuote(packageName)}")
            if (res.success) "Force-stopped: $packageName" else error(res.stderr.ifBlank { "force-stop failed" })
        }
    }

    suspend fun clearAppData(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val res = privilegeManager.executeShellCommand("/system/bin/pm clear ${shellQuote(packageName)}")
            if (res.success) "Data cleared: $packageName\n${res.stdout}".trim() else error(res.stderr.ifBlank { "pm clear failed" })
        }
    }

    suspend fun waitForForeground(packageName: String, timeoutMs: Long = 15_000L): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(500L)
            while (System.currentTimeMillis() < deadline) {
                val (pkg, activity) = getForegroundInfo()
                if (pkg.equals(packageName, ignoreCase = true)) {
                    return@runCatching "Foreground ready: $pkg/$activity"
                }
                delay(400)
            }
            val (pkg, activity) = getForegroundInfo()
            error("Wait for foreground timeout: expected $packageName, current $pkg/$activity")
        }
    }

    fun showToast(text: String): Result<String> = runCatching {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
        "Toast shown: $text"
    }

    @Suppress("DEPRECATION")
    fun vibrate(durationMs: Long = 200L): Result<String> = runCatching {
        val ms = durationMs.coerceIn(10L, 5_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(ms)
            }
        }
        "Vibrated ${ms}ms"
    }

    fun clipboardSet(text: String): Result<String> = runCatching {
        val latch = java.util.concurrent.CountDownLatch(1)
        var error: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("taixu-workflow", text))
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
            error("Clipboard write timeout")
        }
        error?.let { throw it }
        "Written to clipboard (${text.length} chars)"
    }

    fun clipboardGet(): Result<String> = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        text
    }

    suspend fun foregroundPackage(): Pair<String, String> = getForegroundInfo()

    private fun putExtras(intent: Intent, extras: Map<String, String>) {
        extras.forEach { (rawKey, rawValue) ->
            val (key, type) = parseExtraKey(rawKey)
            when (type) {
                "int" -> intent.putExtra(key, rawValue.toInt())
                "long" -> intent.putExtra(key, rawValue.toLong())
                "bool", "boolean" -> intent.putExtra(key, rawValue.toBooleanStrictOrNull() ?: rawValue.equals("1"))
                "float" -> intent.putExtra(key, rawValue.toFloat())
                "uri" -> intent.putExtra(key, Uri.parse(rawValue))
                else -> intent.putExtra(key, rawValue)
            }
        }
    }

    private fun parseExtraKey(raw: String): Pair<String, String> {
        val parts = raw.split(':', limit = 2)
        return if (parts.size == 2 && parts[1] in setOf("int", "long", "bool", "boolean", "float", "uri", "string")) {
            parts[0] to parts[1]
        } else if (parts.size == 2 && parts[0] in setOf("int", "long", "bool", "boolean", "float", "uri", "string")) {
            parts[1] to parts[0]
        } else {
            raw to "string"
        }
    }

    private fun parseComponent(value: String): ComponentName {
        ComponentName.unflattenFromString(value)?.let { return it }
        if (value.contains('/')) {
            val pkg = value.substringBefore('/')
            val cls = value.substringAfter('/')
            val fullClass = if (cls.startsWith('.')) "$pkg$cls" else cls
            return ComponentName(pkg, fullClass)
        }
        error("Cannot parse component: $value")
    }

    private suspend fun getForegroundInfo(): Pair<String, String> {
        val res = privilegeManager.executeShellCommand(
            "/system/bin/dumpsys activity activities | /system/bin/grep -E 'topResumedActivity|mResumedActivity' | /system/bin/head -n 1"
        )
        if (!res.success || res.stdout.isBlank()) return "Unknown" to "Unknown"
        // Match format like: topResumedActivity=ActivityRecord{... u0 com.tencent.mm/.ui.LauncherUI ...}
        val match = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)").find(res.stdout)
        return if (match != null) {
            val (pkg, act) = match.destructured
            pkg to act
        } else {
            "Unknown" to "Unknown"
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
