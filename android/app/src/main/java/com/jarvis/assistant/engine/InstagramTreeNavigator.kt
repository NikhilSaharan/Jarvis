package com.jarvis.assistant.engine

import android.graphics.Rect
import android.util.Log
import com.jarvis.assistant.JarvisAccessibilityService
import com.jarvis.assistant.ScreenElement
import com.jarvis.assistant.brain.SocialIntent
import kotlinx.coroutines.delay

data class FastNavResult(
    val handled: Boolean,
    val success: Boolean = false,
    val message: String = "",
    val stepsTaken: List<String> = emptyList()
)

/**
 * Instant, 100% live-Accessibility-Tree driven navigator for Instagram.
 * Uses ZERO static coordinates or hardcoded layout positions — resolves every tab,
 * inbox icon, search field, profile row, story ring, Follow, and Send button dynamically
 * from the active Accessibility tree with sub-100ms polling.
 * Automatically hands off cleanly to UniversalAppAgent's Gemini loop if an unexpected screen appears.
 */
object InstagramTreeNavigator {

    private const val TAG = "InstagramTreeNav"

    suspend fun execute(
        service: JarvisAccessibilityService,
        intent: SocialIntent,
        rawInstruction: String
    ): FastNavResult {
        val steps = mutableListOf<String>()
        dismissBlockingPopupIfAny(service)?.let { steps.add(it) }

        return when (intent.actionType) {
            "dm" -> {
                if (intent.target.isBlank() || intent.message.isBlank()) {
                    FastNavResult(handled = false, stepsTaken = steps)
                } else {
                    executeDirectMessage(service, intent.target, intent.message, steps)
                }
            }
            "story" -> {
                if (intent.target.isBlank()) {
                    FastNavResult(handled = false, stepsTaken = steps)
                } else {
                    executeWatchStory(service, intent.target, steps)
                }
            }
            "follow" -> {
                if (intent.target.isBlank()) {
                    FastNavResult(handled = false, stepsTaken = steps)
                } else {
                    executeFollowAccount(service, intent.target, steps)
                }
            }
            "reels" -> executeReels(service, rawInstruction, steps)
            else -> FastNavResult(handled = false, stepsTaken = steps)
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 1. DIRECT MESSAGE (HOME -> MESSAGES/INBOX -> SEARCH -> CHAT -> SEND)
    // ═════════════════════════════════════════════════════════════════
    private suspend fun executeDirectMessage(
        service: JarvisAccessibilityService,
        target: String,
        message: String,
        steps: MutableList<String>
    ): FastNavResult {
        val dm = service.resources.displayMetrics
        val h = dm.heightPixels

        // Already inside the exact target's chat thread? Send immediately!
        val initialComposer = findChatComposer(service, h)
        if (initialComposer != null) {
            if (isChatHeaderMatchingTarget(service, target, h)) {
                steps.add("Already inside chat with $target")
                return typeAndSendInChat(service, initialComposer, target, message, h, steps)
            } else {
                // Inside another chat -> go back once cleanly
                service.performBack()
                delay(400)
            }
        }

        // Step A: Navigate to Messages / Direct Inbox if not already there
        if (!isInMessagesInbox(service, h)) {
            var inboxBtn = pollElement(service, timeoutMs = 1500, intervalMs = 90) { findInboxButton(it, h) }
            if (inboxBtn == null) {
                // Maybe on Search/Reels/Profile tab -> tap Home tab first to surface top-right Messages icon
                val homeTab = findBottomNavTab(service, h, listOf("home", "feed"), listOf("feed_tab", "home_tab"))
                if (homeTab != null) {
                    service.tapNodeSingleTouch(homeTab.node)
                    steps.add("Tapped Home tab to access Messages")
                    inboxBtn = pollElement(service, timeoutMs = 1600, intervalMs = 100) { findInboxButton(it, h) }
                } else {
                    // Press Back once in case stuck inside an open post/modal
                    service.performBack()
                    delay(450)
                    inboxBtn = pollElement(service, timeoutMs = 1200, intervalMs = 100) { findInboxButton(it, h) }
                }
            }

            if (inboxBtn == null) {
                Log.w(TAG, "Messages icon not found in tree; handing off.")
                return FastNavResult(handled = false, stepsTaken = steps)
            }

            service.tapNodeSingleTouch(inboxBtn.node)
            steps.add("Opened Messages/Inbox")

            // Wait dynamically for Messages Inbox search or threads to appear
            val inboxLoaded = pollCondition(timeoutMs = 2000, intervalMs = 90) {
                isInMessagesInbox(service, h)
            }
            if (!inboxLoaded) {
                dismissBlockingPopupIfAny(service)?.let { steps.add(it) }
                if (!isInMessagesInbox(service, h)) {
                    return FastNavResult(handled = false, stepsTaken = steps)
                }
            }
        }

        // Step B: Fast shortcut — check if recipient row is already directly visible in top Inbox list
        val initialElements = service.dumpScreenElements()
        val searchBoxTopGuess = findSearchTriggerOrInput(initialElements, h)?.bounds?.bottom ?: (h * 0.25f).toInt()
        val visibleChatRow = findBestPersonMatch(initialElements, target, searchBoxTopGuess, requireStrictTopInbox = true)
        if (visibleChatRow != null) {
            service.tapNodeSingleTouch(visibleChatRow.node)
            steps.add("Tapped visible chat '$target'")
        } else {
            // Step C: Tap Search bar in Messages and type recipient (NO IME_ENTER so live results aren't dismissed!)
            val searchBottom = activateAndTypeInSearchBar(service, h, target, steps, pressEnter = false)
                ?: return FastNavResult(handled = false, stepsTaken = steps)

            // Wait cleanly for live filtered chat row matching target
            val matchedRow = pollElement(service, timeoutMs = 2400, intervalMs = 110) { elements ->
                findBestPersonMatch(elements, target, searchBottom, requireStrictTopInbox = false)
            }

            if (matchedRow == null) {
                Log.w(TAG, "Chat recipient '$target' not found in DM search; handing off.")
                return FastNavResult(handled = false, stepsTaken = steps)
            }

            service.tapNodeSingleTouch(matchedRow.node)
            steps.add("Selected chat '${matchedRow.text.ifBlank { target }}'")
        }

        // Step D: Wait for chat composer EditText, type message, and tap Send cleanly
        val composer = pollElement(service, timeoutMs = 2200, intervalMs = 90) { elements ->
            elements.firstOrNull { isChatComposerElement(it, h) }
        } ?: return FastNavResult(handled = false, stepsTaken = steps)

        return typeAndSendInChat(service, composer, target, message, h, steps)
    }

    private suspend fun typeAndSendInChat(
        service: JarvisAccessibilityService,
        composer: ScreenElement,
        target: String,
        message: String,
        screenHeight: Int,
        steps: MutableList<String>
    ): FastNavResult {
        // Focus composer with single touch so Send button renders reliably when text is injected
        service.tapNodeSingleTouch(composer.node)
        delay(90)

        // Re-acquire fresh reference cleanly if possible
        val freshComposer = service.dumpScreenElements().firstOrNull { isChatComposerElement(it, screenHeight) } ?: composer
        val typed = service.typeText(freshComposer.node, message, pressEnter = false)
        if (!typed) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }
        steps.add("Typed message into chat")

        val sendBtn = pollElement(service, timeoutMs = 1600, intervalMs = 75) { elements ->
            findGenuineChatSendButton(elements, screenHeight)
        } ?: return FastNavResult(handled = false, stepsTaken = steps)

        service.tapNodeSingleTouch(sendBtn.node)
        delay(320)

        // Verify sent: if Send button is genuinely still visible on screen, tap once via clickNode
        val stillUnsent = findGenuineChatSendButton(service.dumpScreenElements(), screenHeight)
        if (stillUnsent != null) {
            service.clickNode(stillUnsent.node)
            delay(280)
        }

        steps.add("Sent message to $target")
        return FastNavResult(
            handled = true,
            success = true,
            message = "Instagram par $target ko message bhej diya hai Sir.",
            stepsTaken = steps
        )
    }

    // ═════════════════════════════════════════════════════════════════
    // 2. WATCH STORY (STORY TRAY -> HORIZONTAL SWIPE -> PROFILE/AVATAR FALLBACK)
    // ═════════════════════════════════════════════════════════════════
    private suspend fun executeWatchStory(
        service: JarvisAccessibilityService,
        target: String,
        steps: MutableList<String>
    ): FastNavResult {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        // 1. Check right away if target story is visible on screen
        var storyEl = findMatchingStoryElement(service.dumpScreenElements(), target)
        if (storyEl != null) {
            service.tapNodeSingleTouch(storyEl.node)
            delay(400)
            steps.add("Opened $target's story directly")
            return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
        }

        // 2. If not on Home feed (e.g. Search/Reels/Chat), tap Home tab so story tray is visible
        val homeTab = findBottomNavTab(service, h, listOf("home", "feed"), listOf("feed_tab", "home_tab"))
        if (homeTab != null && !homeTab.node.isSelected) {
            service.tapNodeSingleTouch(homeTab.node)
            steps.add("Switched to Home feed for Story tray")
            storyEl = pollElement(service, timeoutMs = 1300, intervalMs = 100) { findMatchingStoryElement(it, target) }
            if (storyEl != null) {
                service.tapNodeSingleTouch(storyEl.node)
                delay(400)
                return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
            }
        }

        // 3. Swipe the top horizontal Story tray once dynamically from its live bounds
        val trayRect = findStoryTrayRect(service.dumpScreenElements(), w, h)
        if (trayRect != null) {
            service.swipeHorizontalInRect(trayRect, toLeft = true)
            delay(350)
            storyEl = findMatchingStoryElement(service.dumpScreenElements(), target)
            if (storyEl != null) {
                service.tapNodeSingleTouch(storyEl.node)
                delay(400)
                steps.add("Opened $target's story after tray swipe")
                return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
            }
        }

        // 4. Fallback: Search user profile via normal Search & tap their profile picture / story ring
        val openedProfile = navigateToUserProfileViaSearch(service, w, h, target, steps)
        if (openedProfile) {
            val profileAvatarOrStory = pollElement(service, timeoutMs = 1500, intervalMs = 100) { elements ->
                elements.firstOrNull { el ->
                    val label = labelOf(el)
                    val res = resIdOf(el)
                    el.bounds.top < h * 0.45 && (el.isClickable || el.node.parent?.isClickable == true) &&
                        (label.contains("story") || res.contains("profile_header_avatar") || res.contains("reel_ring") || res.contains("avatar"))
                }
            }
            if (profileAvatarOrStory != null) {
                service.tapNodeSingleTouch(profileAvatarOrStory.node)
                delay(450)
                steps.add("Opened $target's story from profile")
                return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
            }
        }

        return FastNavResult(handled = false, stepsTaken = steps)
    }

    // ═════════════════════════════════════════════════════════════════
    // 3. FOLLOW ACCOUNT (NORMAL SEARCH TAB -> TYPE -> OPEN PROFILE -> FOLLOW)
    // ═════════════════════════════════════════════════════════════════
    private suspend fun executeFollowAccount(
        service: JarvisAccessibilityService,
        target: String,
        steps: MutableList<String>
    ): FastNavResult {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        // Check if already on the target's profile screen
        val currentFollow = findFollowOrFollowingButton(service.dumpScreenElements(), h)
        if (currentFollow != null && isChatHeaderMatchingTarget(service, target, h)) {
            return completeFollowOnProfile(service, currentFollow, target, h, steps)
        }

        val openedProfile = navigateToUserProfileViaSearch(service, w, h, target, steps)
        if (!openedProfile) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        val profileBtn = pollElement(service, timeoutMs = 2200, intervalMs = 100) {
            findFollowOrFollowingButton(it, h)
        } ?: return FastNavResult(handled = false, stepsTaken = steps)

        return completeFollowOnProfile(service, profileBtn, target, h, steps)
    }

    private suspend fun completeFollowOnProfile(
        service: JarvisAccessibilityService,
        btn: ScreenElement,
        target: String,
        screenHeight: Int,
        steps: MutableList<String>
    ): FastNavResult {
        val label = btn.text.ifBlank { btn.contentDescription }.trim().lowercase()
        if (label == "following" || label == "requested") {
            return FastNavResult(true, true, "Instagram par $target pehle se followed hai Sir.", steps)
        }

        service.tapNodeSingleTouch(btn.node)
        delay(350)

        // Confirm state flipped to Following/Requested (retry once only if still "Follow")
        val after = findFollowOrFollowingButton(service.dumpScreenElements(), screenHeight)
        val afterLabel = after?.text?.ifBlank { after.contentDescription }?.trim()?.lowercase() ?: ""
        if (after != null && (afterLabel == "follow" || afterLabel == "follow back")) {
            service.clickNode(after.node)
            delay(250)
        }

        steps.add("Followed $target")
        return FastNavResult(true, true, "Instagram par $target ko follow kar diya hai Sir.", steps)
    }

    private suspend fun navigateToUserProfileViaSearch(
        service: JarvisAccessibilityService,
        screenWidth: Int,
        screenHeight: Int,
        target: String,
        steps: MutableList<String>
    ): Boolean {
        // Check if Search tab is already selected or Search bar is already at top of Explore
        var searchBar = findSearchTriggerOrInput(service.dumpScreenElements(), screenHeight)
        val isSearchTabSelected = findBottomNavTab(service, screenHeight, listOf("search"), listOf("search_tab"))?.node?.isSelected == true
        val inDmInbox = isInMessagesInbox(service, screenHeight)

        if (inDmInbox || (!isSearchTabSelected && searchBar == null)) {
            var searchTab = findBottomNavTab(service, screenHeight, listOf("search and explore", "search"), listOf("search_tab", "explore_tab"))
            if (searchTab == null) {
                service.performBack()
                delay(400)
                searchTab = pollElement(service, timeoutMs = 1200, intervalMs = 100) {
                    findBottomNavTab(it, screenHeight, listOf("search and explore", "search"), listOf("search_tab", "explore_tab"))
                }
            }
            if (searchTab == null) return false
            service.tapNodeSingleTouch(searchTab.node)
            steps.add("Tapped Search tab")
            delay(250)
        }

        // Activate & type account query without IME Enter so live account autocomplete list stays open
        val searchBottom = activateAndTypeInSearchBar(service, screenHeight, target, steps, pressEnter = false)
            ?: return false

        var matchedAccount = pollElement(service, timeoutMs = 2000, intervalMs = 110) { elements ->
            findBestPersonMatch(elements, target, searchBottom, requireStrictTopInbox = false)
        }

        // Fallback: if autocomplete didn't surface account row, submit Search & check "Accounts" tab
        if (matchedAccount == null) {
            val activeSearch = service.dumpScreenElements().firstOrNull { it.isEditable && it.bounds.top < screenHeight * 0.35 }
            if (activeSearch != null) {
                service.typeText(activeSearch.node, target, pressEnter = true)
                delay(700)
                val accountsTab = service.dumpScreenElements().firstOrNull { el ->
                    val l = labelOf(el)
                    (l == "accounts" || l == "users") && el.isClickable
                }
                if (accountsTab != null) {
                    service.tapNodeSingleTouch(accountsTab.node)
                    steps.add("Switched to Accounts tab")
                    delay(500)
                }
                matchedAccount = pollElement(service, timeoutMs = 1800, intervalMs = 110) { elements ->
                    findBestPersonMatch(elements, target, searchBottom, requireStrictTopInbox = false)
                }
            }
        }

        if (matchedAccount == null) return false
        service.tapNodeSingleTouch(matchedAccount.node)
        steps.add("Opened profile '${matchedAccount.text.ifBlank { target }}'")
        return true
    }

    // ═════════════════════════════════════════════════════════════════
    // 4. REELS TAB NAVIGATION
    // ═════════════════════════════════════════════════════════════════
    private suspend fun executeReels(
        service: JarvisAccessibilityService,
        rawInstruction: String,
        steps: MutableList<String>
    ): FastNavResult {
        val h = service.resources.displayMetrics.heightPixels
        val lower = rawInstruction.lowercase()
        val wantsNextScroll = lower.contains("next") || lower.contains("scroll") ||
            lower.contains("agla") || lower.contains("dusra") || lower.contains("change") || lower.contains("neeche")

        var reelsTab = findBottomNavTab(service, h, listOf("reels", "clips"), listOf("clips_tab", "reels_tab"))
        if (reelsTab == null && isInMessagesInbox(service, h)) {
            service.performBack()
            delay(400)
            reelsTab = pollElement(service, timeoutMs = 1200, intervalMs = 100) {
                findBottomNavTab(it, h, listOf("reels", "clips"), listOf("clips_tab", "reels_tab"))
            }
        }

        if (reelsTab != null) {
            if (!reelsTab.node.isSelected) {
                service.tapNodeSingleTouch(reelsTab.node)
                steps.add("Opened Reels tab")
                delay(550)
            }
            if (wantsNextScroll) {
                service.scroll("down")
                steps.add("Scrolled to next Reel")
            }
            return FastNavResult(true, true, "Instagram par Reels chala di hain Sir.", steps)
        }

        return FastNavResult(handled = false, stepsTaken = steps)
    }

    // ═════════════════════════════════════════════════════════════════
    // DYNAMIC LIVE-TREE MATCHING HELPERS (ZERO STATIC COORDS)
    // ═════════════════════════════════════════════════════════════════

    private suspend fun activateAndTypeInSearchBar(
        service: JarvisAccessibilityService,
        screenHeight: Int,
        query: String,
        steps: MutableList<String>,
        pressEnter: Boolean
    ): Int? {
        var searchEl = pollElement(service, timeoutMs = 1600, intervalMs = 90) { elements ->
            findSearchTriggerOrInput(elements, screenHeight)
        } ?: return null

        if (!searchEl.isEditable) {
            service.tapNodeSingleTouch(searchEl.node)
            steps.add("Activated Search bar")
            val editable = pollElement(service, timeoutMs = 1400, intervalMs = 80) { elements ->
                elements.firstOrNull { el ->
                    el.isEditable && el.bounds.top < screenHeight * 0.40
                }
            } ?: return null
            searchEl = editable
        } else {
            service.tapNodeSingleTouch(searchEl.node)
            delay(70)
        }

        val fresh = service.dumpScreenElements().firstOrNull { it.isEditable && it.bounds.top < screenHeight * 0.40 } ?: searchEl
        val typed = service.typeText(fresh.node, query, pressEnter = pressEnter)
        if (!typed) return null

        steps.add("Typed '$query' in search")
        return fresh.bounds.bottom
    }

    private fun findInboxButton(elements: List<ScreenElement>, screenHeight: Int): ScreenElement? {
        return elements.filter { el ->
            val label = labelOf(el)
            val res = resIdOf(el)
            val inBarArea = el.bounds.top < screenHeight * 0.24 || el.bounds.top > screenHeight * 0.72
            if (!inBarArea || el.isEditable) return@filter false

            val isInvalid = label.contains("comment") || label.contains("share") || label.contains("voice") ||
                label.contains("like") || label.contains("story") || label.contains("notification") ||
                label == "message..." || label.contains("send message") || res.contains("notification")
            if (isInvalid) return@filter false

            val resMatch = res.contains("inbox") || res.contains("direct") || res.contains("messages_tab")
            val descMatch = label.contains("unread message") || label == "direct" || label == "messages" ||
                label == "messaging" || label.contains("direct message") || label == "chats"
            (resMatch || descMatch) && (el.isClickable || el.node.parent?.isClickable == true)
        }.minByOrNull { el ->
            val res = resIdOf(el)
            if (res.contains("inbox") || res.contains("direct")) 0 else 1
        }
    }

    private fun isInMessagesInbox(service: JarvisAccessibilityService, screenHeight: Int): Boolean {
        val elements = service.dumpScreenElements()
        val hasBottomFeedTabs = findBottomNavTab(service, screenHeight, listOf("reels", "search and explore"), listOf("clips_tab", "search_tab")) != null
        val summary = elements.joinToString(" | ") { labelOf(it) }
        val resSummary = elements.joinToString(" | ") { resIdOf(it) }
        val hasInboxSignals = summary.contains("ask meta ai") || summary.contains("your note") ||
            summary.contains("primary") || summary.contains("requests") || summary.contains("new message") ||
            summary.contains("chats") || resSummary.contains("direct_inbox") || resSummary.contains("inbox_recycler") ||
            resSummary.contains("direct_search")
        return !hasBottomFeedTabs && hasInboxSignals
    }

    private fun findChatComposer(service: JarvisAccessibilityService, screenHeight: Int): ScreenElement? {
        return service.dumpScreenElements().firstOrNull { isChatComposerElement(it, screenHeight) }
    }

    private fun isChatComposerElement(el: ScreenElement, screenHeight: Int): Boolean {
        if (!el.isEditable || el.bounds.bottom < screenHeight * 0.50) return false
        val label = labelOf(el)
        val res = resIdOf(el)
        if (res.contains("search") || label == "search") return false
        return res.contains("entry") || res.contains("message") || res.contains("composer") ||
            label.contains("message") || el.bounds.top > screenHeight * 0.45
    }

    private fun isChatHeaderMatchingTarget(service: JarvisAccessibilityService, target: String, screenHeight: Int): Boolean {
        val tokens = target.lowercase().trim().split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return false
        return service.dumpScreenElements().any { el ->
            if (el.bounds.top > screenHeight * 0.26) return@any false
            val l = labelOf(el)
            tokens.all { l.contains(it) }
        }
    }

    private fun findGenuineChatSendButton(elements: List<ScreenElement>, screenHeight: Int): ScreenElement? {
        return elements.firstOrNull { el ->
            if (el.bounds.top < screenHeight * 0.40 || el.isEditable) return@firstOrNull false
            val label = labelOf(el)
            val res = resIdOf(el)
            val forbidden = res.contains("voice") || res.contains("mic") || res.contains("audio") ||
                res.contains("record") || res.contains("camera") || res.contains("gallery") ||
                res.contains("sticker") || res.contains("like") ||
                label.contains("voice") || label.contains("mic") || label.contains("audio") ||
                label.contains("record") || label.contains("camera")
            if (forbidden) return@firstOrNull false

            val isSend = res.endsWith("send_button") || res.endsWith(":id/send") || res == "send" ||
                res.contains("composer_send") || el.text.equals("Send", ignoreCase = true) ||
                el.contentDescription.equals("Send", ignoreCase = true) || el.contentDescription.equals("भेजें", ignoreCase = true)
            isSend && (el.isClickable || el.node.parent?.isClickable == true)
        }
    }

    private fun findSearchTriggerOrInput(elements: List<ScreenElement>, screenHeight: Int): ScreenElement? {
        return elements.filter { el ->
            if (el.bounds.top > screenHeight * 0.40) return@filter false
            val label = labelOf(el)
            val res = resIdOf(el)
            val isSearch = res.contains("search") || label.contains("search") || label.contains("ask meta ai")
            isSearch && (el.isEditable || el.isClickable)
        }.minByOrNull { el ->
            if (el.isEditable) 0 else 1
        }
    }

    private fun findBottomNavTab(
        service: JarvisAccessibilityService,
        screenHeight: Int,
        labelKeywords: List<String>,
        resKeywords: List<String>
    ): ScreenElement? {
        return findBottomNavTab(service.dumpScreenElements(), screenHeight, labelKeywords, resKeywords)
    }

    private fun findBottomNavTab(
        elements: List<ScreenElement>,
        screenHeight: Int,
        labelKeywords: List<String>,
        resKeywords: List<String>
    ): ScreenElement? {
        return elements.firstOrNull { el ->
            if (el.bounds.top < screenHeight * 0.70 || el.bounds.height() > screenHeight * 0.22) return@firstOrNull false
            val label = labelOf(el)
            val res = resIdOf(el)
            val matches = resKeywords.any { res.contains(it) } || labelKeywords.any { label.contains(it) }
            matches && (el.isClickable || el.node.parent?.isClickable == true)
        }
    }

    private fun findBestPersonMatch(
        elements: List<ScreenElement>,
        target: String,
        minTopY: Int,
        requireStrictTopInbox: Boolean
    ): ScreenElement? {
        val targetLower = target.lowercase().trim()
        val tokens = targetLower.split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return null

        val forbidden = listOf(
            "meta ai", "ask meta", "see all", "invite", "create a group", "create group",
            "your note", "add note", "clear all", "recent searches", "suggestions", "send", "share"
        )

        var bestEl: ScreenElement? = null
        var bestScore = 0

        for (el in elements) {
            if (el.isEditable || el.bounds.top <= minTopY + 10) continue
            if (!(el.isClickable || el.node.parent?.isClickable == true)) continue

            val raw = el.text.ifBlank { el.contentDescription }.trim()
            val label = raw.lowercase()
            if (label.isBlank() || label.startsWith("#")) continue
            if (forbidden.any { label.contains(it) }) continue
            if (label.startsWith("search \"") || label.startsWith("search for")) continue
            if (el.bounds.width() < 180) continue // Ignore tiny icons like camera/call buttons on a row

            val segments = label.split("|", "\n", "·", ",").map { it.trim() }.filter { it.isNotEmpty() }
            var score = 0
            if (segments.any { it == targetLower }) {
                score = 120
            } else if (segments.any { it.startsWith(targetLower) }) {
                score = 85
            } else if (tokens.all { tok -> segments.any { seg -> Regex("\\b${Regex.escape(tok)}\\b").containsMatchIn(seg) } }) {
                score = 70
            } else if (tokens.all { label.contains(it) }) {
                score = 45
            } else if (!requireStrictTopInbox && tokens.size == 1 && label.contains(tokens[0])) {
                score = 25
            }

            if (requireStrictTopInbox && score < 70) continue

            if (score > bestScore) {
                bestScore = score
                bestEl = el
            }
        }
        return bestEl
    }

    private fun findMatchingStoryElement(elements: List<ScreenElement>, target: String): ScreenElement? {
        val tokens = target.lowercase().trim().split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return null
        return elements.firstOrNull { el ->
            val label = labelOf(el)
            if (!label.contains("story")) return@firstOrNull false
            if (label.contains("your story") && !tokens.contains("your") && !tokens.contains("my")) return@firstOrNull false
            tokens.all { label.contains(it) } && (el.isClickable || el.node.parent?.isClickable == true)
        }
    }

    private fun findStoryTrayRect(elements: List<ScreenElement>, screenWidth: Int, screenHeight: Int): Rect? {
        val stories = elements.filter { el ->
            el.bounds.top < screenHeight * 0.38 && labelOf(el).contains("story")
        }
        if (stories.isEmpty()) return null
        val minLeft = stories.minOf { it.bounds.left }.coerceAtLeast(0)
        val minTop = stories.minOf { it.bounds.top }.coerceAtLeast(0)
        val maxRight = screenWidth
        val maxBottom = stories.maxOf { it.bounds.bottom }
        return Rect(minLeft, minTop, maxRight, maxBottom)
    }

    private fun findFollowOrFollowingButton(elements: List<ScreenElement>, screenHeight: Int): ScreenElement? {
        return elements.firstOrNull { el ->
            if (el.bounds.top < screenHeight * 0.12 || el.bounds.top > screenHeight * 0.78) return@firstOrNull false
            val t = el.text.trim().lowercase()
            val c = el.contentDescription.trim().lowercase()
            val v = t.ifBlank { c }
            val isExactFollowBtn = v == "follow" || v == "follow back" || v == "following" || v == "requested" ||
                (c.startsWith("follow ") && v.length <= 28)
            isExactFollowBtn && !v.contains("followers") && !v.contains("accounts") &&
                (el.isClickable || el.node.parent?.isClickable == true)
        }
    }

    private suspend fun dismissBlockingPopupIfAny(service: JarvisAccessibilityService): String? {
        val elements = service.dumpScreenElements()
        val dismissLabels = listOf("not now", "no thanks", "skip", "maybe later")
        val popupBtn = elements.firstOrNull { el ->
            val t = el.text.trim().lowercase()
            val c = el.contentDescription.trim().lowercase()
            (t in dismissLabels || c in dismissLabels) && el.isClickable
        }
        if (popupBtn != null) {
            service.tapNodeSingleTouch(popupBtn.node)
            delay(300)
            return "Dismissed popup '${popupBtn.text.ifBlank { popupBtn.contentDescription }}'"
        }
        return null
    }

    private suspend fun pollElement(
        service: JarvisAccessibilityService,
        timeoutMs: Long,
        intervalMs: Long,
        matcher: (List<ScreenElement>) -> ScreenElement?
    ): ScreenElement? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val match = matcher(service.dumpScreenElements())
            if (match != null) return match
            delay(intervalMs)
        }
        return null
    }

    private suspend fun pollCondition(
        timeoutMs: Long,
        intervalMs: Long,
        condition: () -> Boolean
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            delay(intervalMs)
        }
        return false
    }

    private fun labelOf(el: ScreenElement): String =
        "${el.text} ${el.contentDescription}".trim().lowercase()

    private fun resIdOf(el: ScreenElement): String =
        el.node.viewIdResourceName?.substringAfterLast('/')?.lowercase() ?: ""
}
