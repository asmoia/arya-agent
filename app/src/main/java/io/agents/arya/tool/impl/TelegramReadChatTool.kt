// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.tool.impl

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.arya.agent.hermes.core.HermesDirectOpen
import io.agents.arya.service.ClawAccessibilityService
import io.agents.arya.tool.BaseTool
import io.agents.arya.tool.ToolParameter
import io.agents.arya.tool.ToolResult
import io.agents.arya.tool.UiWait
import io.agents.arya.utils.ContactListUiUtils
import io.agents.arya.utils.ContactMatchUtils
import io.agents.arya.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Opens a Telegram chat and scrolls back through the message history, scraping
 * the visible message texts into a structured, de-duplicated list.
 *
 * This composes the same proven primitives as [OpenMessagingChatTool] (open app,
 * reach the chat list, search or scroll to the contact) and then adds the
 * read/scroll/collect step that makes "read all my chats with someone and
 * summarize" possible with an on-device model.
 *
 * Reliability note: Telegram renders its message list as a virtualized
 * RecyclerView, so only the currently-visible bubbles are in the accessibility
 * tree at any moment. We therefore page upward and de-duplicate by text; the
 * `complete` flag tells the caller whether we reached the top or hit the page
 * budget. Because chat layouts differ across Telegram versions/themes, the
 * bounds-based heuristic may need calibration on a specific device/ROM.
 */
class TelegramReadChatTool : BaseTool() {

    override fun getName(): String = "telegram_read_chat"
    override fun getDisplayName(): String = "Read Telegram Chat"

    override fun getDescriptionEN(): String =
        "Open a person/group/channel in Telegram, scroll back through the chat, and return the " +
            "message texts (oldest-to-newest) so they can be read or summarised. Never sends messages."

    override fun getDescriptionCN(): String = getDescriptionEN()

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "contact",
            "string",
            "Exact or visible name of the person, group or channel",
            true,
        ),
        ToolParameter(
            "max_pages",
            "integer",
            "Maximum number of scroll pages to read (default 8). More pages = older history.",
            false,
        ),
        ToolParameter(
            "per_page",
            "integer",
            "Messages kept per page before de-duplication (default 12).",
            false,
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val contact = requireString(params, "contact").trim()
        if (contact.isEmpty()) return ToolResult.error("Chat name is empty")
        val maxPages = optionalInt(params, "max_pages", 8).coerceIn(1, 60)
        val perPage = optionalInt(params, "per_page", 12).coerceIn(4, 60)

        val service = requireAccessibilityService(2_000L)
        if (service == null) return ToolResult.error("Accessibility service is not running")

        try {
            val app = "Telegram"
            val packageName = OpenAppTool.resolveAppNameStatic(app) ?: app
            val opened = HermesDirectOpen.INSTANCE.open(app)
            if (!opened.isSuccess && !service.openApp(packageName)) {
                return ToolResult.error("Could not open $app. Is it installed?")
            }
            if (!waitForActivePackage(service, packageName, 4_000L)) {
                return ToolResult.error("$app did not become active")
            }
            if (!ContactListUiUtils.prepareForContactLookup(service, packageName, 3, 650L)) {
                return ToolResult.error("Could not reach $app chat list")
            }
            val aliases = LinkedHashSet(ContactMatchUtils.buildNormalizedAliases(contact))
            val digitAliases = LinkedHashSet(ContactMatchUtils.buildDigitAliases(contact))
            val beforeLookup = service.getScreenTree()
            if (!ContactListUiUtils.searchOrScrollAndFindAndClick(
                    service, contact, aliases, digitAliases, 3, 650L
                )
            ) {
                return ToolResult.error("Could not find '$contact' in $app")
            }
            UiWait.until(1_500L, 80L) {
                val current = service.getScreenTree()
                current != null && current != beforeLookup
            }
            // Give the chat one moment to render the first page.
            UiWait.until(1_200L, 100L) {
                val root = service.getRootInActiveWindow()
                root != null && collectVisibleTexts(root).isNotEmpty()
            }

            val collected = LinkedHashMap<String, String>() // text -> (dedup key already covers ordering)
            val seen = LinkedHashSet<String>()
            var pagesScraped = 0
            var lastNewCount = 0
            val width = getScreenSize().let { it[0].toFloat() }
            val height = getScreenSize().let { it[1].toFloat() }

            while (pagesScraped < maxPages) {
                val root = service.getRootInActiveWindow()
                if (root == null) break
                val pageTexts = collectVisibleTexts(root, perPage)
                val added = pageTexts.filter { seen.add(it) }
                added.forEach { collected[it] = it }
                lastNewCount = added.size

                // Reached the top / no more history: stop.
                if (lastNewCount == 0 && pagesScraped > 0) break

                if (!service.performSwipe(
                        (width / 2).toInt(),
                        (height * 0.82f).toInt(),
                        (width / 2).toInt(),
                        (height * 0.30f).toInt(),
                        360L,
                    )
                ) {
                    break
                }
                // Let the list settle before the next scrape.
                UiWait.until(700L, 90L) {
                    val r = service.getRootInActiveWindow()
                    r != null && collectVisibleTexts(r).isNotEmpty()
                }
                pagesScraped++
            }

            val messages = JSONArray()
            for (text in collected.keys) messages.put(text)
            val json = JSONObject()
            json.put("chat", contact)
            json.put("app", app)
            json.put("complete", lastNewCount == 0 && pagesScraped > 0)
            json.put("pages", pagesScraped)
            json.put("message_count", collected.size)
            json.put("messages", messages)

            val compact = json.toString()
            return ToolResult.success("Read $collected.size messages from '$contact' (pages=$pagesScraped). $compact")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return ToolResult.error("Reading chat interrupted")
        } catch (e: Exception) {
            XLog.w(TAG, "Telegram read chat failed", e)
            return ToolResult.error("Read chat failed: ${e.message}")
        }
    }

    /**
     * Collect the visible text-bearing leaf nodes, sorted top-to-bottom, then
     * de-duplicated by text. Filters obvious non-message chrome (buttons, tabs,
     * search fields) so the model gets message text, not UI noise.
     */
    private fun collectVisibleTexts(root: AccessibilityNodeInfo, limit: Int = 60): List<String> {
        val out = mutableListOf<Pair<Int, String>>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 64) return
            if (!node.isVisibleToUser) {
                // A hidden parent can still have visible children; keep walking.
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
                return
            }
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val label = listOfNotNull(text, desc).firstOrNull { it.isNotBlank() }?.trim()
            if (label != null && label.isNotBlank() && isLikelyMessage(label)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.height() > 0) {
                    out.add(rect.centerY() to label)
                }
            }
            // Always recurse so nested TextView children of a container are found.
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out.sortedBy { it.first }.map { it.second }.distinct().take(limit)
    }

    private fun isLikelyMessage(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.length > 2400) return true
        // Drop obvious UI chrome.
        return !UI_NOISE.any { t.equals(it, ignoreCase = true) } &&
            !t.startsWith("Search", ignoreCase = true) &&
            !t.startsWith("Chats", ignoreCase = true) &&
            !t.startsWith("Settings", ignoreCase = true)
    }

    private fun waitForActivePackage(service: ClawAccessibilityService, expected: String, timeoutMs: Long): Boolean {
        val expectedLower = expected.lowercase(Locale.ROOT)
        return try {
            UiWait.until(timeoutMs, 80L) {
                val root = service.getRootInActiveWindow()
                val current = root?.packageName?.toString()?.lowercase(Locale.ROOT) ?: ""
                current == expectedLower || (expectedLower.contains("telegram") && current.contains("telegram"))
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    companion object {
        private const val TAG = "TelegramReadChat"
        private val UI_NOISE = setOf(
            "Gifts", "All Stories", "Archived Chats", "Saved Messages",
            "Contacts", "Calls", "Settings", "New Group", "New Channel",
            "Comments", "Forward", "Reply", "Report", "Delete", "Copy",
            "Edit", "Pin", "Mute", "Search", "Back", "More",
        )
    }
}
