package com.jarvis.assistant.engine

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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
 * inbox icon, search field, profile row, story ring, shared DM media, Follow, and Send button dynamically
 * from the active Accessibility tree with sub-80ms polling.
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
            "chat_media" -> {
                if (intent.target.isBlank()) {
                    FastNavResult(handled = false, stepsTaken = steps)
                } else {
                    executeChatMedia(service, intent.target, steps)
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
            "reels" -> {
                val lower = rawInstruction.lowercase()
                val looksLikeFriendSharedReel = intent.target.isNotBlank() &&
                    (lower.contains("bhej") || lower.contains("sent") || lower.contains("share") ||
                        lower.contains("chat") || lower.contains("dm"))
                if (looksLikeFriendSharedReel) {
                    executeChatMedia(service, intent.target, steps)
                } else {
                    executeReels(service, rawInstruction, steps)
                }
            }
            else -> FastNavResult(handled = false, stepsTaken = steps)
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 1. DIRECT MESSAGE (FAST SHARED INBOX/SEARCH -> CHAT -> SEND)
    // ═════════════════════════════════════════════════════════════════
    private suspend fun executeDirectMessage(
        service: JarvisAccessibilityService,
        target: String,
        message: String,
        steps: MutableList<String>
    ): FastNavResult {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        if (!openDirectChatThread(service, target, w, h, steps)) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        val composer = pollElement(service, timeoutMs = 1500, intervalMs = 55) { elements ->
            elements.firstOrNull { isChatComposerElement(it, h) }
        } ?: return FastNavResult(handled = false, stepsTaken = steps)

        return typeAndSendInChat(service, composer, target, message, h, steps)
    }

    // ═════════════════════════════════════════════════════════════════
    // 1B. PLAY LATEST REEL / MEDIA SHARED BY A FRIEND IN DM CHAT
    // ═════════════════════════════════════════════════════════════════
    private suspend fun executeChatMedia(
        service: JarvisAccessibilityService,
        target: String,
        steps: MutableList<String>
    ): FastNavResult {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        if (!openDirectChatThread(service, target, w, h, steps)) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        val initialEls = service.dumpScreenElements()
        val composerTop = initialEls.firstOrNull { isChatComposerElement(it, h) }?.bounds?.top ?: (h * 0.85f).toInt()
        val headerBottom = (initialEls.filter {
            it.bounds.top < h * 0.17 && it.bounds.height() < h * 0.14
        }.maxOfOrNull { it.bounds.bottom } ?: (h * 0.13f).toInt()).coerceIn((h * 0.08f).toInt(), (h * 0.19f).toInt())

        var mediaCard = pollElement(service, timeoutMs = 1100, intervalMs = 70) { els ->
            findLatestSharedMediaInChat(els, w, h, headerBottom, composerTop)
        }

        // If latest texts sit below the reel, scroll up once to reveal the most recent shared reel
        if (mediaCard == null) {
            service.scroll("up")
            delay(400)
            mediaCard = findLatestSharedMediaInChat(service.dumpScreenElements(), w, h, headerBottom, composerTop)
        }

        if (mediaCard == null) {
            Log.w(TAG, "No shared reel/media card found in $target's chat; handing off.")
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        service.tapNodeSingleTouch(mediaCard.node)
        steps.add("Tapped latest shared reel/media from $target")

        // Verify viewer opened cleanly (composer left screen); retry once with accessibility click if unchanged
        val opened = pollCondition(timeoutMs = 1100, intervalMs = 90) {
            service.dumpScreenElements().none { isChatComposerElement(it, h) }
        }
        if (!opened) {
            service.clickNode(mediaCard.node)
            delay(350)
        }

        return FastNavResult(
            handled = true,
            success = true,
            message = "Instagram par $target ki latest bheji hui reel chala di hai Sir.",
            stepsTaken = steps
        )
    }

    // ═════════════════════════════════════════════════════════════════
    // SHARED INBOX & DM THREAD NAVIGATION HELPERS
    // ═════════════════════════════════════════════════════════════════
    private suspend fun openMessagesInbox(
        service: JarvisAccessibilityService,
        screenHeight: Int,
        steps: MutableList<String>
    ): Boolean {
        if (isInMessagesInbox(service, screenHeight)) return true

        var inboxBtn = pollElement(service, timeoutMs = 1100, intervalMs = 60) { findInboxButton(it, screenHeight) }
        if (inboxBtn == null) {
            // Maybe inside another screen/tab -> switch to Home tab first to surface top Messages icon
            val homeTab = findBottomNavTab(service, screenHeight, listOf("home", "feed"), listOf("feed_tab", "home_tab"))
            if (homeTab != null && !homeTab.node.isSelected) {
                service.tapNodeSingleTouch(homeTab.node)
                steps.add("Tapped Home tab to access Messages")
                inboxBtn = pollElement(service, timeoutMs = 1200, intervalMs = 60) { findInboxButton(it, screenHeight) }
            }
            if (inboxBtn == null) {
                service.performBack()
                delay(350)
                if (isInMessagesInbox(service, screenHeight)) return true
                inboxBtn = pollElement(service, timeoutMs = 1000, intervalMs = 60) { findInboxButton(it, screenHeight) }
            }
        }

        if (inboxBtn == null) {
            Log.w(TAG, "Messages icon not found in tree; handing off.")
            return false
        }

        service.tapNodeSingleTouch(inboxBtn.node)
        steps.add("Opened Messages/Inbox")

        val inboxLoaded = pollCondition(timeoutMs = 1600, intervalMs = 55) {
            isInMessagesInbox(service, screenHeight)
        }
        if (!inboxLoaded) {
            dismissBlockingPopupIfAny(service)?.let { steps.add(it) }
            return isInMessagesInbox(service, screenHeight)
        }
        return true
    }

    private suspend fun openDirectChatThread(
        service: JarvisAccessibilityService,
        target: String,
        screenWidth: Int,
        screenHeight: Int,
        steps: MutableList<String>
    ): Boolean {
        // Already inside a chat thread?
        val currentComposer = findChatComposer(service, screenHeight)
        if (currentComposer != null) {
            if (isChatHeaderMatchingTarget(service.dumpScreenElements(), target, screenHeight)) {
                steps.add("Already inside chat with $target")
                return true
            }
            // Exit unrelated chat back to Inbox
            service.performBack()
            delay(320)
        }

        if (!openMessagesInbox(service, screenHeight, steps)) return false

        // Fast shortcut: check if contact row is already visible in top Inbox list
        val initialElements = service.dumpScreenElements()
        val searchTrigger = findSearchTriggerOrInput(initialElements, screenWidth, screenHeight)
        val searchBoxBottom = searchTrigger?.bounds?.bottom ?: (screenHeight * 0.24f).toInt()
        val visibleChatRow = findBestPersonMatch(
            initialElements, target, searchBoxBottom, screenHeight, screenWidth, requireStrictTopInbox = true
        )

        if (visibleChatRow != null) {
            service.tapNodeSingleTouch(visibleChatRow.node)
            steps.add("Tapped visible chat '$target'")
        } else {
            // Search inside Messages Inbox (NO IME_ENTER so live chat list stays open)
            val searchBottom = activateAndTypeInSearchBar(service, screenWidth, screenHeight, target, steps, pressEnter = false)
                ?: return false

            val matchedRow = pollElement(service, timeoutMs = 1900, intervalMs = 65) { elements ->
                findBestPersonMatch(elements, target, searchBottom, screenHeight, screenWidth, requireStrictTopInbox = false)
            }
            if (matchedRow == null) {
                Log.w(TAG, "Chat recipient '$target' not found in DM search; handing off.")
                return false
            }

            service.tapNodeSingleTouch(matchedRow.node)
            steps.add("Selected chat '${matchedRow.text.ifBlank { target }}'")
        }

        val composer = pollElement(service, timeoutMs = 1800, intervalMs = 55) { elements ->
            elements.firstOrNull { isChatComposerElement(it, screenHeight) }
        }
        return composer != null
    }

    private suspend fun typeAndSendInChat(
        service: JarvisAccessibilityService,
        composer: ScreenElement,
        target: String,
        message: String,
        screenHeight: Int,
        steps: MutableList<String>
    ): FastNavResult {
        if (!composer.node.isFocused) {
            service.clickNode(composer.node)
            delay(50)
        }

        var freshComposer = service.dumpScreenElements().firstOrNull { isChatComposerElement(it, screenHeight) } ?: composer
        var typed = service.typeText(freshComposer.node, message, pressEnter = false)
        delay(55)
        freshComposer.node.refresh()

        val enteredOk = typed && (freshComposer.node.text?.toString()?.contains(message.take(6), ignoreCase = true) == true)
        if (!enteredOk) {
            service.tapNodeSingleTouch(freshComposer.node)
            delay(85)
            freshComposer = service.dumpScreenElements().firstOrNull { isChatComposerElement(it, screenHeight) } ?: freshComposer
            typed = service.typeText(freshComposer.node, message, pressEnter = false)
        }

        if (!typed) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }
        steps.add("Typed message into chat")

        val sendBtn = pollElement(service, timeoutMs = 1400, intervalMs = 50) { elements ->
            findGenuineChatSendButton(elements, screenHeight)
        } ?: return FastNavResult(handled = false, stepsTaken = steps)

        service.tapNodeSingleTouch(sendBtn.node)
        delay(240)

        // Confirm send: if Send button is genuinely still present, retry once via clickNode
        val stillUnsent = findGenuineChatSendButton(service.dumpScreenElements(), screenHeight)
        if (stillUnsent != null) {
            service.clickNode(stillUnsent.node)
            delay(220)
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
    // 2. WATCH STORY (STORY TRAY -> MESSAGES/CHAT FRIENDS -> EXPLORE)
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
            delay(350)
            steps.add("Opened $target's story directly")
            return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
        }

        // 2. Check Home Story Tray if Home tab is reachable (up to 3 horizontal swipes)
        val inDmInboxInitially = isInMessagesInbox(service, h)
        if (!inDmInboxInitially) {
            val homeTab = findBottomNavTab(service, h, listOf("home", "feed"), listOf("feed_tab", "home_tab"))
            if (homeTab != null && !homeTab.node.isSelected) {
                service.tapNodeSingleTouch(homeTab.node)
                steps.add("Switched to Home feed for Story tray")
                storyEl = pollElement(service, timeoutMs = 1000, intervalMs = 75) { findMatchingStoryElement(it, target) }
                if (storyEl != null) {
                    service.tapNodeSingleTouch(storyEl.node)
                    delay(350)
                    return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
                }
            }

            val trayRect = findStoryTrayRect(service.dumpScreenElements(), w, h)
            if (trayRect != null) {
                for (swipeAttempt in 1..3) {
                    service.swipeHorizontalInRect(trayRect, toLeft = true)
                    delay(260)
                    storyEl = findMatchingStoryElement(service.dumpScreenElements(), target)
                    if (storyEl != null) {
                        service.tapNodeSingleTouch(storyEl.node)
                        delay(350)
                        steps.add("Opened $target's story after tray swipe #$swipeAttempt")
                        return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
                    }
                }
            }
        }

        // 3. Primary Friend Search in Messages / Chat Inbox (avoids Explore matching famous strangers first!)
        if (openMessagesInbox(service, h, steps)) {
            var inboxEls = service.dumpScreenElements()
            var dmStory = findMatchingStoryElement(inboxEls, target)
            if (dmStory != null) {
                service.tapNodeSingleTouch(dmStory.node)
                delay(350)
                steps.add("Opened $target's story from Messages")
                return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
            }

            val searchBoxBottom = findSearchTriggerOrInput(inboxEls, w, h)?.bounds?.bottom ?: (h * 0.24f).toInt()
            var friendRow = findBestPersonMatch(inboxEls, target, searchBoxBottom, h, w, requireStrictTopInbox = true)
            var searchedInDm = false

            if (friendRow == null) {
                val searchBottom = activateAndTypeInSearchBar(service, w, h, target, steps, pressEnter = false)
                if (searchBottom != null) {
                    searchedInDm = true
                    val deadline = System.currentTimeMillis() + 1800
                    while (System.currentTimeMillis() < deadline) {
                        inboxEls = service.dumpScreenElements()
                        dmStory = findMatchingStoryElement(inboxEls, target)
                        if (dmStory != null) {
                            service.tapNodeSingleTouch(dmStory.node)
                            delay(350)
                            steps.add("Opened $target's story in DM search")
                            return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
                        }
                        friendRow = findBestPersonMatch(inboxEls, target, searchBottom, h, w, requireStrictTopInbox = false)
                        if (friendRow != null) break
                        delay(65)
                    }
                }
            }

            if (friendRow != null) {
                service.tapNodeSingleTouch(friendRow.node)
                steps.add("Opened friend '$target' chat from Messages")

                val chatReady = pollCondition(timeoutMs = 1700, intervalMs = 60) {
                    val els = service.dumpScreenElements()
                    els.any { isChatComposerElement(it, h) } || isChatHeaderMatchingTarget(els, target, h)
                }

                if (chatReady) {
                    val chatEls = service.dumpScreenElements()
                    val headerStory = findMatchingStoryElement(chatEls, target)
                    if (headerStory != null && headerStory.bounds.top < h * 0.22) {
                        service.tapNodeSingleTouch(headerStory.node)
                        delay(350)
                        steps.add("Opened $target's story from chat header")
                        return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
                    }

                    val headerTarget = findChatHeaderTarget(chatEls, target, h)
                    if (headerTarget != null) {
                        service.tapNodeSingleTouch(headerTarget.node)
                        steps.add("Opened $target's profile from chat")

                        val profileStory = pollElement(service, timeoutMs = 1800, intervalMs = 65) { els ->
                            findProfileStoryAvatar(els, h)
                        }
                        if (profileStory != null) {
                            service.tapNodeSingleTouch(profileStory.node)
                            delay(380)
                            steps.add("Opened $target's story from friend profile")
                            return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
                        }
                    }
                }
                // Keep friend context rather than searching public Explore and hitting a celebrity
                return FastNavResult(handled = false, stepsTaken = steps)
            }

            if (searchedInDm) {
                service.performBack()
                delay(280)
            }
        }

        // 4. Last-Resort Fallback: Explore Search ONLY when not found in Messages at all
        val openedProfile = navigateToUserProfileViaSearch(service, w, h, target, steps)
        if (openedProfile) {
            val profileAvatarOrStory = pollElement(service, timeoutMs = 1500, intervalMs = 80) { elements ->
                findProfileStoryAvatar(elements, h)
            }
            if (profileAvatarOrStory != null) {
                service.tapNodeSingleTouch(profileAvatarOrStory.node)
                delay(400)
                steps.add("Opened $target's story from Explore profile")
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
        val searchBar = findSearchTriggerOrInput(service.dumpScreenElements(), screenWidth, screenHeight)
        val isSearchTabSelected = findBottomNavTab(service, screenHeight, listOf("search"), listOf("search_tab"))?.node?.isSelected == true
        val inDmInbox = isInMessagesInbox(service, screenHeight)

        if (inDmInbox || (!isSearchTabSelected && searchBar == null)) {
            var searchTab = findBottomNavTab(service, screenHeight, listOf("search and explore", "search"), listOf("search_tab", "explore_tab"))
            if (searchTab == null) {
                service.performBack()
                delay(350)
                searchTab = pollElement(service, timeoutMs = 1100, intervalMs = 70) {
                    findBottomNavTab(it, screenHeight, listOf("search and explore", "search"), listOf("search_tab", "explore_tab"))
                }
            }
            if (searchTab == null) return false
            service.tapNodeSingleTouch(searchTab.node)
            steps.add("Tapped Search tab")
            delay(220)
        }

        // Activate & type account query without IME Enter so live account autocomplete list stays open
        val searchBottom = activateAndTypeInSearchBar(service, screenWidth, screenHeight, target, steps, pressEnter = false)
            ?: return false

        var matchedAccount = pollElement(service, timeoutMs = 1800, intervalMs = 80) { elements ->
            findBestPersonMatch(elements, target, searchBottom, screenHeight, screenWidth, requireStrictTopInbox = false)
        }

        // Fallback: if autocomplete didn't surface account row, submit Search & check "Accounts" tab
        if (matchedAccount == null) {
            val activeSearch = service.dumpScreenElements().firstOrNull { it.isEditable && it.bounds.top < screenHeight * 0.42 }
            if (activeSearch != null) {
                service.typeText(activeSearch.node, target, pressEnter = true)
                delay(650)
                val accountsTab = service.dumpScreenElements().firstOrNull { el ->
                    val l = labelOf(el)
                    (l == "accounts" || l == "users") && hasClickableOrAncestor(el.node, 3)
                }
                if (accountsTab != null) {
                    service.tapNodeSingleTouch(accountsTab.node)
                    steps.add("Switched to Accounts tab")
                    delay(450)
                }
                matchedAccount = pollElement(service, timeoutMs = 1700, intervalMs = 80) { elements ->
                    findBestPersonMatch(elements, target, searchBottom, screenHeight, screenWidth, requireStrictTopInbox = false)
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
            delay(350)
            reelsTab = pollElement(service, timeoutMs = 1100, intervalMs = 70) {
                findBottomNavTab(it, h, listOf("reels", "clips"), listOf("clips_tab", "reels_tab"))
            }
        }

        if (reelsTab != null) {
            if (!reelsTab.node.isSelected) {
                service.tapNodeSingleTouch(reelsTab.node)
                steps.add("Opened Reels tab")
                delay(500)
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

    private suspend fun pollEditableSearchNode(
        service: JarvisAccessibilityService,
        triggerEl: ScreenElement,
        screenHeight: Int,
        timeoutMs: Long,
        intervalMs: Long
    ): Pair<AccessibilityNodeInfo, Rect>? {
        val start = System.currentTimeMillis()
        var clickedFallback = false
        while (System.currentTimeMillis() - start < timeoutMs) {
            val inDump = service.dumpScreenElements().firstOrNull { el ->
                el.isEditable && el.bounds.top < screenHeight * 0.48
            }
            if (inDump != null) return Pair(inDump.node, inDump.bounds)

            val crossWindow = service.findNodeAcrossWindows(resolveClickableParent = false) { node ->
                if (!node.isEditable) return@findNodeAcrossWindows false
                val r = Rect()
                node.getBoundsInScreen(r)
                r.top in 0 until (screenHeight * 0.48f).toInt() && r.height() > 15
            }
            if (crossWindow != null) {
                val r = Rect()
                crossWindow.getBoundsInScreen(r)
                return Pair(crossWindow, r)
            }

            // Self-heal if inner pill tap didn't open Search EditText within 420ms
            if (!clickedFallback && System.currentTimeMillis() - start > 420) {
                clickedFallback = true
                service.clickNode(triggerEl.node)
            }
            delay(intervalMs)
        }
        return null
    }

    private suspend fun activateAndTypeInSearchBar(
        service: JarvisAccessibilityService,
        screenWidth: Int,
        screenHeight: Int,
        query: String,
        steps: MutableList<String>,
        pressEnter: Boolean
    ): Int? {
        val searchEl = pollElement(service, timeoutMs = 1200, intervalMs = 55) { elements ->
            findSearchTriggerOrInput(elements, screenWidth, screenHeight)
        } ?: return null

        val editableNode: AccessibilityNodeInfo
        val editableBounds = Rect()

        if (!searchEl.isEditable) {
            service.tapNodeSingleTouch(searchEl.node)
            steps.add("Activated Search bar")
            val opened = pollEditableSearchNode(service, searchEl, screenHeight, timeoutMs = 1800, intervalMs = 55)
                ?: return null
            editableNode = opened.first
            editableBounds.set(opened.second)
        } else {
            editableNode = searchEl.node
            editableBounds.set(searchEl.bounds)
        }

        if (!editableNode.isFocused) {
            service.clickNode(editableNode)
            delay(45)
        }

        var typed = service.typeText(editableNode, query, pressEnter = pressEnter)
        delay(55)
        editableNode.refresh()

        val enteredOk = typed && (editableNode.text?.toString()?.contains(query.take(5), ignoreCase = true) == true)
        if (!enteredOk) {
            // Ensure IME InputConnection binds on freshly opened overlay, then retry typing once
            val retryPair = pollEditableSearchNode(service, searchEl, screenHeight, timeoutMs = 450, intervalMs = 50)
            val retryNode = retryPair?.first ?: editableNode
            service.tapNodeSingleTouch(retryNode)
            delay(85)
            typed = service.typeText(retryNode, query, pressEnter = pressEnter)
            retryNode.getBoundsInScreen(editableBounds)
        }

        if (!typed) return null

        steps.add("Typed '$query' in search")
        return editableBounds.bottom
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
            (resMatch || descMatch) && hasClickableOrAncestor(el.node, maxDepth = 4)
        }.minByOrNull { el ->
            val res = resIdOf(el)
            if (res.contains("inbox") || res.contains("direct") || res.contains("messages_tab")) 0 else 1
        }
    }

    private fun isInMessagesInbox(service: JarvisAccessibilityService, screenHeight: Int): Boolean {
        val elements = service.dumpScreenElements()
        // Inside an active DM thread with composer is NOT Inbox list
        if (elements.any { isChatComposerElement(it, screenHeight) }) return false

        val summary = elements.joinToString(" | ") { labelOf(it) }
        val resSummary = elements.joinToString(" | ") { resIdOf(it) }

        val hasStrongInboxId = resSummary.contains("direct_inbox") || resSummary.contains("inbox_recycler") ||
            resSummary.contains("direct_search") || resSummary.contains("thread_list") ||
            resSummary.contains("notes_tray") || resSummary.contains("row_inbox")

        val hasStrongInboxText = summary.contains("ask meta ai") || summary.contains("your note") ||
            summary.contains("leave a note") || summary.contains("new message") ||
            summary.contains("requests") || summary.contains("chats") ||
            (summary.contains("primary") && (summary.contains("general") || summary.contains("channels")))

        // Bottom Messages tab selected (do not reject just because bottom nav tabs stay visible on Inbox)
        val messagesTabSelected = findInboxButton(elements, screenHeight)?.node?.isSelected == true &&
            !summary.contains("your story")

        val homeTabSelected = findBottomNavTab(elements, screenHeight, listOf("home", "feed"), listOf("feed_tab", "home_tab"))?.node?.isSelected == true
        if (homeTabSelected && !hasStrongInboxId && !messagesTabSelected) return false

        return hasStrongInboxId || hasStrongInboxText || messagesTabSelected
    }

    private fun findChatComposer(service: JarvisAccessibilityService, screenHeight: Int): ScreenElement? {
        return service.dumpScreenElements().firstOrNull { isChatComposerElement(it, screenHeight) }
    }

    private fun isChatComposerElement(el: ScreenElement, screenHeight: Int): Boolean {
        if (!el.isEditable || el.bounds.bottom < screenHeight * 0.38) return false
        val label = labelOf(el)
        val res = resIdOf(el)
        if (res.contains("search") || label == "search") return false
        return res.contains("entry") || res.contains("message") || res.contains("composer") ||
            label.contains("message") || el.bounds.top > screenHeight * 0.34
    }

    private fun isChatHeaderMatchingTarget(service: JarvisAccessibilityService, target: String, screenHeight: Int): Boolean {
        return isChatHeaderMatchingTarget(service.dumpScreenElements(), target, screenHeight)
    }

    private fun isChatHeaderMatchingTarget(elements: List<ScreenElement>, target: String, screenHeight: Int): Boolean {
        val tokens = target.lowercase().trim().split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return false
        return elements.any { el ->
            if (el.bounds.top > screenHeight * 0.26) return@any false
            val l = labelOf(el)
            tokens.all { l.contains(it) }
        }
    }

    private fun findChatHeaderTarget(elements: List<ScreenElement>, target: String, screenHeight: Int): ScreenElement? {
        val tokens = target.lowercase().trim().split(Regex("\\s+")).filter { it.length >= 2 }
        return elements.filter { el ->
            if (el.bounds.top > screenHeight * 0.19 || el.isEditable) return@filter false
            if (!hasClickableOrAncestor(el.node, maxDepth = 4)) return@filter false
            val label = labelOf(el)
            val res = resIdOf(el)
            if (label.contains("back") || label.contains("call") || label.contains("video") || label.contains("info")) return@filter false
            val matchesTarget = tokens.isNotEmpty() && tokens.any { label.contains(it) }
            val isHeaderRes = res.contains("header_title") || res.contains("thread_title") ||
                res.contains("other_user") || res.contains("avatar")
            matchesTarget || isHeaderRes
        }.minByOrNull { el ->
            val label = labelOf(el)
            if (tokens.isNotEmpty() && tokens.all { label.contains(it) }) 0 else 1
        }
    }

    private fun findProfileStoryAvatar(elements: List<ScreenElement>, screenHeight: Int): ScreenElement? {
        return elements.filter { el ->
            if (el.bounds.top > screenHeight * 0.48 || el.isEditable) return@filter false
            if (!hasClickableOrAncestor(el.node, maxDepth = 4)) return@filter false
            val label = labelOf(el)
            val res = resIdOf(el)
            (label.contains("story") && !label.contains("highlight") && !label.contains("no story")) ||
                res.contains("reel_ring") || res.contains("profile_header_avatar") || res.contains("avatar")
        }.minByOrNull { el ->
            val label = labelOf(el)
            val res = resIdOf(el)
            when {
                label.contains("story") -> 0
                res.contains("reel_ring") -> 1
                res.contains("profile_header_avatar") -> 2
                else -> 3
            }
        }
    }

    private fun findGenuineChatSendButton(elements: List<ScreenElement>, screenHeight: Int): ScreenElement? {
        return elements.firstOrNull { el ->
            if (el.bounds.top < screenHeight * 0.35 || el.isEditable) return@firstOrNull false
            val label = labelOf(el)
            val res = resIdOf(el)
            val forbidden = res.contains("voice") || res.contains("mic") || res.contains("audio") ||
                res.contains("record") || res.contains("camera") || res.contains("gallery") ||
                res.contains("sticker") || res.contains("like") || res.contains("emoji") ||
                res.contains("gif") || res.contains("meta_ai") ||
                label.contains("voice") || label.contains("mic") || label.contains("audio") ||
                label.contains("record") || label.contains("camera")
            if (forbidden) return@firstOrNull false

            val isSend = res.contains("send_button") || res.contains("send_btn") || res.endsWith(":id/send") ||
                res == "send" || res.contains("composer_send") || label == "send" || label == "send message" ||
                el.text.equals("Send", ignoreCase = true) || el.contentDescription.equals("Send", ignoreCase = true) ||
                el.contentDescription.equals("भेजें", ignoreCase = true)
            isSend && hasClickableOrAncestor(el.node, maxDepth = 3)
        }
    }

    private fun findSearchTriggerOrInput(
        elements: List<ScreenElement>,
        screenWidth: Int,
        screenHeight: Int
    ): ScreenElement? {
        return elements.filter { el ->
            if (el.bounds.top > screenHeight * 0.42) return@filter false
            val label = labelOf(el)
            val res = resIdOf(el)
            // Never tap standalone "Meta AI" icon unless it specifically is the "Ask Meta AI or search" pill or editable search box
            if (!el.isEditable && label.contains("meta ai") && !label.contains("search") && !res.contains("search")) {
                return@filter false
            }
            val isSearch = res.contains("search") || label.contains("search") || label.contains("ask meta ai")
            isSearch && (el.isEditable || hasClickableOrAncestor(el.node, maxDepth = 4))
        }.minByOrNull { el ->
            val label = labelOf(el)
            val res = resIdOf(el)
            val wide = el.bounds.width() >= screenWidth * 0.35
            when {
                el.isEditable -> 0
                (res.contains("search_bar") || res.contains("direct_search") || res.contains("action_bar_search")) && wide -> 1
                label.contains("search") && wide -> 2
                label.contains("ask meta ai or search") -> 3
                label.contains("search") || res.contains("search") -> 4
                else -> 5
            }
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
            matches && hasClickableOrAncestor(el.node, maxDepth = 3)
        }
    }

    private fun findBestPersonMatch(
        elements: List<ScreenElement>,
        target: String,
        minTopY: Int,
        screenHeight: Int,
        screenWidth: Int,
        requireStrictTopInbox: Boolean
    ): ScreenElement? {
        val targetLower = target.lowercase().trim()
        val tokens = targetLower.split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return null

        val forbidden = listOf(
            "meta ai", "ask meta", "see all", "invite", "create a group", "create group",
            "your note", "add note", "clear all", "recent searches", "suggestions", "send", "share",
            "camera", "video call", "audio call", "voice message", "more accounts", "unread"
        )

        var bestEl: ScreenElement? = null
        var bestScore = 0

        for (el in elements) {
            if (el.isEditable || el.bounds.centerY() <= minTopY + 2 || el.bounds.top >= screenHeight * 0.89) continue
            if (!hasClickableOrAncestor(el.node, maxDepth = 4)) continue

            val raw = el.text.ifBlank { el.contentDescription }.trim()
            val label = raw.lowercase()
            if (label.isBlank() || label.startsWith("#")) continue
            if (forbidden.any { label.contains(it) }) continue
            if (label.startsWith("search \"") || label.startsWith("search for")) continue
            // Allow wrap_content short names ("Anuj", "Om", "Raj") >= 50px; filter tiny unlabeled icons
            if (el.bounds.width() < 50 || (el.text.isBlank() && el.bounds.width() < (screenWidth * 0.14f).toInt())) continue

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

    private fun findLatestSharedMediaInChat(
        elements: List<ScreenElement>,
        screenWidth: Int,
        screenHeight: Int,
        headerBottom: Int,
        composerTop: Int
    ): ScreenElement? {
        val viewportHeight = (composerTop - headerBottom).coerceAtLeast((screenHeight * 0.25f).toInt())
        val minW = (screenWidth * 0.24f).toInt()
        val minH = (screenHeight * 0.085f).toInt()
        val maxContainerH = (viewportHeight * 0.86f).toInt()
        val maxContainerW = (screenWidth * 0.94f).toInt()

        val excludedTerms = listOf(
            "voice", "mic", "audio", "record", "camera", "gallery", "sticker", "emoji",
            "send", "video call", "audio call", "react", "reply", "seen", "delivered", "message..."
        )

        data class MediaCandidate(val el: ScreenElement, val isReelOrVideo: Boolean, val score: Int)

        val candidates = mutableListOf<MediaCandidate>()
        for (el in elements) {
            if (el.isEditable) continue
            val cy = el.bounds.centerY()
            if (cy <= headerBottom || cy >= composerTop) continue
            if (el.bounds.width() < minW || el.bounds.height() < minH) continue
            if (el.bounds.height() >= maxContainerH && el.bounds.width() >= maxContainerW) continue
            val cls = el.className.lowercase()
            if (cls.contains("recyclerview") || cls.contains("listview") || cls.contains("scrollview")) continue
            if (!hasClickableOrAncestor(el.node, maxDepth = 5)) continue

            val label = labelOf(el)
            val res = resIdOf(el)
            if (excludedTerms.any { label.contains(it) || res.contains(it) }) continue

            val isReelKeyword = label.contains("reel") || label.contains("clip") || res.contains("reel") || res.contains("clip")
            val isVideoOrXma = isReelKeyword || label.contains("video") || label.contains("watch") ||
                label.contains("play") || res.contains("video") || res.contains("xma")
            val isOtherMedia = isVideoOrXma || label.contains("post") || label.contains("photo") ||
                label.contains("shared") || res.contains("media") || res.contains("attachment") ||
                res.contains("thumbnail") || res.contains("portrait")

            // For unlabeled visual cards without media text, require blank own text so multi-line plain DM texts are ignored
            val isUnlabeledVisualCard = !isOtherMedia && el.text.isBlank() &&
                el.bounds.height() >= (screenHeight * 0.16f).toInt() &&
                el.bounds.width() >= (screenWidth * 0.28f).toInt()

            if (!isOtherMedia && !isUnlabeledVisualCard) continue

            val score = when {
                isReelKeyword -> 100
                isVideoOrXma -> 70
                isOtherMedia -> 45
                else -> 25
            }
            candidates.add(MediaCandidate(el, isVideoOrXma, score))
        }

        if (candidates.isEmpty()) return null
        // Prefer reel/video cards if present; pick bottom-most (= latest sent in chat)
        val pool = candidates.filter { it.isReelOrVideo }.ifEmpty { candidates }
        val maxBottom = pool.maxOf { it.el.bounds.bottom }
        return pool
            .filter { it.el.bounds.bottom >= maxBottom - 60 }
            .maxWithOrNull(compareBy<MediaCandidate> { it.score }.thenBy { it.el.bounds.bottom })
            ?.el
    }

    private fun hasClickableOrAncestor(node: AccessibilityNodeInfo?, maxDepth: Int = 4): Boolean {
        var cur = node
        var depth = 0
        while (cur != null && depth <= maxDepth) {
            if (cur.isClickable) return true
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun findMatchingStoryElement(elements: List<ScreenElement>, target: String): ScreenElement? {
        val tokens = target.lowercase().trim().split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return null
        return elements.firstOrNull { el ->
            val label = labelOf(el)
            if (!label.contains("story")) return@firstOrNull false
            if (label.contains("your story") && !tokens.contains("your") && !tokens.contains("my")) return@firstOrNull false
            tokens.all { label.contains(it) } && hasClickableOrAncestor(el.node, maxDepth = 4)
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
                hasClickableOrAncestor(el.node, maxDepth = 4)
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
