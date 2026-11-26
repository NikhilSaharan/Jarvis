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

        val lower = rawInstruction.lowercase()
        val resolvedCount = resolveRequestedMediaCount(intent, lower)

        return when (intent.actionType) {
            "dm" -> {
                if (intent.target.isBlank() || intent.message.isBlank()) {
                    FastNavResult(handled = false, stepsTaken = steps)
                } else {
                    executeDirectMessage(service, intent.target, intent.message, rawInstruction, steps)
                }
            }
            "read_dm" -> {
                if (intent.target.isBlank()) {
                    FastNavResult(handled = false, stepsTaken = steps)
                } else {
                    executeReadLastMessage(service, intent.target, steps)
                }
            }
            "chat_media_all" -> {
                if (intent.target.isBlank()) {
                    FastNavResult(handled = false, stepsTaken = steps)
                } else {
                    executeChatMedia(service, intent.target, requestedCount = -1, steps = steps)
                }
            }
            "chat_media" -> {
                if (intent.target.isBlank()) {
                    FastNavResult(handled = false, stepsTaken = steps)
                } else {
                    executeChatMedia(service, intent.target, requestedCount = resolvedCount, steps = steps)
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
                val looksLikeFriendSharedReel = intent.target.isNotBlank() &&
                    (lower.contains("bhej") || lower.contains("sent") || lower.contains("share") ||
                        lower.contains("chat") || lower.contains("dm"))
                if (looksLikeFriendSharedReel) {
                    executeChatMedia(service, intent.target, requestedCount = resolvedCount, steps = steps)
                } else {
                    executeReels(service, rawInstruction, steps)
                }
            }
            else -> FastNavResult(handled = false, stepsTaken = steps)
        }
    }

    private fun resolveRequestedMediaCount(intent: SocialIntent, lower: String): Int {
        if (intent.count != 1) return intent.count
        if (lower.contains("saari") || lower.contains("sari") || lower.contains("sabhi") ||
            lower.contains("all") || lower.contains("jitni") || lower.contains("ek ek") || lower.contains("jo jo")
        ) return -1

        Regex("\\b(\\d{1,2})\\s*(?:reels?|videos?|posts?)\\b", RegexOption.IGNORE_CASE).find(lower)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n > 0) return n
        }
        val hindiNums = mapOf("do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "panch" to 5, "paanch" to 5, "chhe" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10)
        for ((word, value) in hindiNums) {
            if (Regex("\\b$word\\s*(?:reels?|videos?|posts?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
                return value
            }
        }
        return 1
    }

    // ═════════════════════════════════════════════════════════════════
    // 1. DIRECT MESSAGE (FAST SHARED INBOX/SEARCH -> CHAT -> SEND)
    // ═════════════════════════════════════════════════════════════════
    private suspend fun executeDirectMessage(
        service: JarvisAccessibilityService,
        target: String,
        message: String,
        rawInstruction: String,
        steps: MutableList<String>
    ): FastNavResult {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        if (!openDirectChatThread(service, target, w, h, steps, rawInstruction)) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        val composer = pollElement(service, timeoutMs = 1500, intervalMs = 55) { elements ->
            elements.firstOrNull { isChatComposerElement(it, h) }
        } ?: return FastNavResult(handled = false, stepsTaken = steps)

        return typeAndSendInChat(service, composer, target, message, h, steps)
    }

    // ═════════════════════════════════════════════════════════════════
    // 1B. PLAY LATEST, LAST N, OR ALL REELS / MEDIA IN FRIEND DM CHAT
    // ═════════════════════════════════════════════════════════════════
    private suspend fun executeChatMedia(
        service: JarvisAccessibilityService,
        target: String,
        requestedCount: Int,
        steps: MutableList<String>
    ): FastNavResult {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        if (!openDirectChatThread(service, target, w, h, steps)) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        // Brief initial settle inside open chat thread
        delay(200)

        val playedSignatures = mutableSetOf<String>()
        var playedCount = 0
        var emptyScrolls = 0
        var sameScreenScrolls = 0
        val maxReelsCap = if (requestedCount > 0) requestedCount else 20

        while (playedCount < maxReelsCap) {
            val els = service.dumpScreenElements()
            val (headerBottom, composerTop) = computeChatViewportBounds(els, h)
            val cards = findSharedMediaCardsInChat(els, w, h, headerBottom, composerTop)
            val nextCard = cards.firstOrNull { buildMediaCardSignature(it, playedCount) !in playedSignatures }

            if (nextCard == null) {
                // Climb upward deeply (up to 10 screens) so reels hidden above recent texts are still found
                if (emptyScrolls >= 10 || sameScreenScrolls >= 2) break
                val beforeSig = viewportMessagesSignature(els, headerBottom, composerTop)
                service.scroll("up")
                delay(430)
                val afterEls = service.dumpScreenElements()
                val afterSig = viewportMessagesSignature(afterEls, headerBottom, composerTop)
                if (beforeSig == afterSig) {
                    sameScreenScrolls++
                } else {
                    sameScreenScrolls = 0
                    emptyScrolls++
                }
                continue
            }

            emptyScrolls = 0
            sameScreenScrolls = 0
            playedSignatures.add(buildMediaCardSignature(nextCard, playedCount))
            openMediaCardViewer(service, nextCard, h)
            playedCount++
            steps.add("Playing shared reel #$playedCount from $target")

            // For single latest reel OR the final requested Nth reel, keep it open playing on screen!
            val isFinalFixedTarget = requestedCount > 0 && playedCount >= requestedCount
            if (isFinalFixedTarget) {
                val msg = if (requestedCount == 1) {
                    "Instagram par $target ki latest bheji hui reel chala di hai Sir."
                } else {
                    "$target ki chat mein bheji hui last $playedCount reels chala di hain Sir."
                }
                return FastNavResult(handled = true, success = true, message = msg, stepsTaken = steps)
            }

            val waitOutcome = waitForReelPlaybackStep(service, h)
            if (waitOutcome == ReelWaitOutcome.USER_EXITED_APP) {
                return FastNavResult(
                    handled = true,
                    success = true,
                    message = "$target ki $playedCount reels play ki hain Sir.",
                    stepsTaken = steps
                )
            }

            // Return cleanly to friend's chat thread before climbing to the next older reel
            if (service.dumpScreenElements().none { isChatComposerElement(it, h) }) {
                service.performBack()
                var backInChat = pollCondition(timeoutMs = 1500, intervalMs = 70) {
                    service.dumpScreenElements().any { isChatComposerElement(it, h) }
                }
                if (!backInChat) {
                    service.performBack()
                    backInChat = pollCondition(timeoutMs = 1100, intervalMs = 70) {
                        service.dumpScreenElements().any { isChatComposerElement(it, h) }
                    }
                }
                if (!backInChat) break
                delay(220)
            }
        }

        return if (playedCount > 0) {
            val summary = if (requestedCount > 1) {
                "$target ki chat mein mili saari $playedCount reels play kar di hain Sir."
            } else {
                "$target ki chat mein bheji hui saari ($playedCount) reels play kar di hain Sir."
            }
            FastNavResult(handled = true, success = true, message = summary, stepsTaken = steps)
        } else {
            FastNavResult(
                handled = true,
                success = false,
                message = "$target ki chat mein koi shared reel nahi mili Sir.",
                stepsTaken = steps
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 1C. OPEN FRIEND DM CHAT -> MARK SEEN -> READ LAST MESSAGE ALOUD
    // ═════════════════════════════════════════════════════════════════
    private suspend fun executeReadLastMessage(
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

        // Opening thread marks messages as Seen automatically; allow chat list to settle cleanly
        delay(450)
        val elements = service.dumpScreenElements()
        val lastMsg = extractLatestMessageInChat(elements, w, h, target)
            ?: return FastNavResult(handled = false, stepsTaken = steps)

        steps.add("Opened & marked $target's chat Seen; read latest message")
        return FastNavResult(
            handled = true,
            success = true,
            message = "$target ka last message (seen ho gaya): $lastMsg",
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
        steps: MutableList<String>,
        rawInstruction: String = ""
    ): Boolean {
        val wantsGroup = rawInstruction.lowercase().contains("group") || target.lowercase().contains("group")
        val matchMode = if (wantsGroup) PersonMatchMode.DM_GROUP else PersonMatchMode.DM_PERSON

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
            initialElements, target, searchBoxBottom, screenHeight, screenWidth,
            requireStrictTopInbox = true, mode = matchMode
        )

        if (visibleChatRow != null) {
            service.tapNodeSingleTouch(visibleChatRow.node)
            steps.add("Tapped visible chat '$target'")
        } else {
            // Search inside Messages Inbox (NO IME_ENTER so live chat list stays open)
            val searchBottom = activateAndTypeInSearchBar(service, screenWidth, screenHeight, target, steps, pressEnter = false)
                ?: return false

            val matchedRow = pollElement(service, timeoutMs = 1900, intervalMs = 65) { elements ->
                findBestPersonMatch(
                    elements, target, searchBottom, screenHeight, screenWidth,
                    requireStrictTopInbox = false, mode = matchMode
                )
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
    // 2. WATCH STORY (HOME STORY TRAY LEFT-SLIDE ONLY — NO SEARCH!)
    // ═════════════════════════════════════════════════════════════════
    private suspend fun executeWatchStory(
        service: JarvisAccessibilityService,
        target: String,
        steps: MutableList<String>
    ): FastNavResult {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        // 1. Check right away if target story is already visible on screen
        var storyEl = findMatchingStoryElement(service.dumpScreenElements(), target, h)
        if (storyEl != null) {
            service.tapNodeSingleTouch(storyEl.node)
            delay(350)
            steps.add("Opened $target's story directly")
            return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
        }

        // 2. Always return to Home Feed so the Story Tray is visible (Back out of DM/Search if needed)
        if (findStoryTrayRect(service.dumpScreenElements(), w, h) == null) {
            for (backTry in 1..3) {
                val elsNow = service.dumpScreenElements()
                if (findStoryTrayRect(elsNow, w, h) != null) break

                val homeTab = findBottomNavTab(elsNow, h, listOf("home", "feed"), listOf("feed_tab", "home_tab"))
                if (homeTab != null) {
                    service.tapNodeSingleTouch(homeTab.node)
                    steps.add("Opened Home tab for Story tray")
                    val ready = pollCondition(timeoutMs = 1300, intervalMs = 65) {
                        val els = service.dumpScreenElements()
                        findMatchingStoryElement(els, target, h) != null || findStoryTrayRect(els, w, h) != null
                    }
                    if (!ready) {
                        // Home feed was scrolled down past Story tray -> scroll back to top
                        service.scroll("up")
                        delay(350)
                    }
                    break
                } else {
                    service.performBack()
                    delay(320)
                }
            }
        }

        // 3. Check Story Tray before sliding
        storyEl = findMatchingStoryElement(service.dumpScreenElements(), target, h)
        if (storyEl != null) {
            service.tapNodeSingleTouch(storyEl.node)
            delay(350)
            steps.add("Opened $target's story on Home tray")
            return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
        }

        // 4. Slide Home Story Tray left horizontally until target's story appears or tray ends (NEVER Search!)
        var stagnantCount = 0
        for (swipeAttempt in 1..12) {
            val currentEls = service.dumpScreenElements()
            val trayRect = findStoryTrayRect(currentEls, w, h) ?: break
            val beforeSig = storyTraySignature(currentEls, h)

            service.swipeHorizontalInRect(trayRect, toLeft = true)
            delay(250)

            val afterEls = service.dumpScreenElements()
            storyEl = findMatchingStoryElement(afterEls, target, h)
            if (storyEl != null) {
                service.tapNodeSingleTouch(storyEl.node)
                delay(350)
                steps.add("Opened $target's story after Home tray slide #$swipeAttempt")
                return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
            }

            val afterSig = storyTraySignature(afterEls, h)
            if (afterSig.isNotBlank() && afterSig == beforeSig) {
                stagnantCount++
                if (stagnantCount >= 2) break
            } else {
                stagnantCount = 0
            }
        }

        steps.add("Scanned Home Story tray without Search; $target not found")
        return FastNavResult(
            handled = true,
            success = false,
            message = "Homepage story tray mein $target ki story nahi mili Sir.",
            stepsTaken = steps
        )
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

        // Activate & type account query without IME Enter so live verified account autocomplete stays open
        val searchBottom = activateAndTypeInSearchBar(service, screenWidth, screenHeight, target, steps, pressEnter = false)
            ?: return false

        var matchedAccount = pollElement(service, timeoutMs = 1300, intervalMs = 70) { elements ->
            findBestPersonMatch(
                elements, target, searchBottom, screenHeight, screenWidth,
                requireStrictTopInbox = false, mode = PersonMatchMode.PUBLIC_ACCOUNT
            )
        }

        // Fallback: submit Search & switch directly to "Accounts" tab so official/verified rows rank cleanly
        if (matchedAccount == null) {
            val activeSearch = service.dumpScreenElements().firstOrNull { it.isEditable && it.bounds.top < screenHeight * 0.42 }
            if (activeSearch != null) {
                service.typeText(activeSearch.node, target, pressEnter = true)
                delay(550)
                val accountsTab = pollElement(service, timeoutMs = 1000, intervalMs = 70) { els ->
                    els.firstOrNull { el ->
                        val l = labelOf(el)
                        (l == "accounts" || l == "users") && hasClickableOrAncestor(el.node, 3)
                    }
                }
                if (accountsTab != null) {
                    service.tapNodeSingleTouch(accountsTab.node)
                    steps.add("Switched to Accounts tab")
                    delay(400)
                }
                matchedAccount = pollElement(service, timeoutMs = 1700, intervalMs = 75) { elements ->
                    findBestPersonMatch(
                        elements, target, searchBottom, screenHeight, screenWidth,
                        requireStrictTopInbox = false, mode = PersonMatchMode.PUBLIC_ACCOUNT
                    )
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

    private enum class PersonMatchMode { DM_PERSON, DM_GROUP, PUBLIC_ACCOUNT }

    private fun findClickableRowRoot(node: AccessibilityNodeInfo?, maxUp: Int = 4): AccessibilityNodeInfo? {
        var cur = node
        var depth = 0
        while (cur != null && depth <= maxUp) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return node
    }

    private fun hasRowBadgeKeyword(
        node: AccessibilityNodeInfo?,
        keywords: List<String>,
        maxDepth: Int = 4
    ): Boolean {
        val root = findClickableRowRoot(node) ?: return false
        fun walk(n: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (n == null || depth > maxDepth) return false
            val cd = n.contentDescription?.toString()?.lowercase() ?: ""
            val res = n.viewIdResourceName?.substringAfterLast('/')?.lowercase() ?: ""
            if (keywords.any { kw -> cd.contains(kw) || res.contains(kw) }) return true
            for (i in 0 until n.childCount) {
                if (walk(n.getChild(i), depth + 1)) return true
            }
            return false
        }
        return walk(root, 0)
    }

    private fun findBestPersonMatch(
        elements: List<ScreenElement>,
        target: String,
        minTopY: Int,
        screenHeight: Int,
        screenWidth: Int,
        requireStrictTopInbox: Boolean,
        mode: PersonMatchMode = PersonMatchMode.DM_PERSON
    ): ScreenElement? {
        val targetLower = target.lowercase().trim()
        val compactTarget = targetLower.replace(Regex("[^a-z0-9]"), "")
        val tokens = targetLower.split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return null

        val forbidden = listOf(
            "meta ai", "ask meta", "see all", "invite", "create a group", "create group",
            "your note", "add note", "clear all", "recent searches", "suggestions", "send", "share",
            "camera", "video call", "audio call", "voice message", "more accounts", "unread"
        )

        // Dynamically detect the "Ask Meta AI" prompt band so web-prompt suggestions are never clicked
        val metaAiHeaderBottom = elements.firstOrNull { el ->
            val l = labelOf(el)
            (l == "ask meta ai" || resIdOf(el).contains("meta_ai_header")) && el.bounds.centerY() > minTopY
        }?.bounds?.bottom
        val postMetaAiSectionTop = if (metaAiHeaderBottom != null) {
            elements.filter { el ->
                val l = labelOf(el)
                el.bounds.top > metaAiHeaderBottom &&
                    (l == "more accounts" || l == "see all" || l == "channels" || l == "messages")
            }.minOfOrNull { it.bounds.top } ?: screenHeight
        } else null

        val fakeWords = listOf(
            "fanpage", "fan page", "fan_page", "fanclub", "fan club", "fan_club", "fandom",
            "parody", "updates", "army", "edits", "lovers", "unofficial", "backup", "stan",
            "_fc", ".fc", "fc_"
        ).filter { !targetLower.contains(it) }

        var bestEl: ScreenElement? = null
        var bestScore = Int.MIN_VALUE
        var bestTop = Int.MAX_VALUE

        for (el in elements) {
            if (el.isEditable || el.bounds.centerY() <= minTopY + 2 || el.bounds.top >= screenHeight * 0.89) continue
            if (!hasClickableOrAncestor(el.node, maxDepth = 4)) continue

            // Ignore rows inside the live "Ask Meta AI" web-prompt section
            if (metaAiHeaderBottom != null && postMetaAiSectionTop != null &&
                el.bounds.centerY() in metaAiHeaderBottom until postMetaAiSectionTop
            ) continue

            val res = resIdOf(el)
            if (res.contains("meta_ai")) continue

            val raw = el.text.ifBlank { el.contentDescription }.trim()
            val label = raw.lowercase()
            if (label.isBlank() || label.startsWith("#")) continue
            if (forbidden.any { label.contains(it) }) continue
            if (label.startsWith("search \"") || label.startsWith("search for")) continue
            // Allow short display names >= 50px; filter tiny unlabeled icons
            if (el.bounds.width() < 50 || (el.text.isBlank() && el.bounds.width() < (screenWidth * 0.14f).toInt())) continue

            // Split structural separators WITHOUT splitting commas so multi-member group subtitles stay detectable
            val rawSegs = label.split("|", "\n", "·", "•").map { it.trim() }.filter { it.isNotEmpty() }
            val isVerified = label.contains("verified") ||
                hasRowBadgeKeyword(el.node, listOf("verified", "blue_check", "verified_badge"))
            val cleanSegs = rawSegs.filter { !it.equals("verified", ignoreCase = true) }
                .map { it.replace(Regex("\\bverified\\b"), "").trim() }
                .filter { it.isNotEmpty() }

            val primarySeg = cleanSegs.firstOrNull() ?: label
            val secondarySegs = cleanSegs.drop(1)
            val handleSeg = cleanSegs.firstOrNull { !it.contains(" ") && !it.contains(",") }?.removePrefix("@") ?: ""

            // Group-chat vs personal-DM protection
            val hasCommaMemberList = secondarySegs.any { sec ->
                sec.contains(",") && !sec.contains("follow") && !sec.contains("mutual") &&
                    !sec.contains("active") && !sec.contains("hour") && !sec.contains("min")
            }
            val looksLikeGroup = hasCommaMemberList || label.contains("members") ||
                label.contains("participants") || res.contains("group") || res.contains("multi_avatar")

            if (mode == PersonMatchMode.DM_PERSON && looksLikeGroup) continue
            if (mode == PersonMatchMode.DM_PERSON && cleanSegs.size >= 2) {
                val primaryOrHandleMatch = tokens.any { primarySeg.contains(it) } ||
                    (handleSeg.isNotBlank() && tokens.any { handleSeg.contains(it) })
                if (!primaryOrHandleMatch) continue
            }

            // On Explore Search, ignore bare magnifying-glass query suggestions lacking account/avatar signals
            if (mode == PersonMatchMode.PUBLIC_ACCOUNT) {
                val isKeywordQuery = res.contains("keyword") || res.contains("search_text") || res.contains("query")
                if (isKeywordQuery) continue
                val hasAccountSignals = cleanSegs.size >= 2 || isVerified || label.contains("@") ||
                    label.contains("follower") || hasRowBadgeKeyword(el.node, listOf("avatar", "profile_pic", "row_user", "user_row"))
                if (!hasAccountSignals) continue
            }

            // Compute semantic relevance score
            var score = 0
            val compactPrimary = primarySeg.replace(Regex("[^a-z0-9]"), "")
            val compactHandle = handleSeg.replace(Regex("[^a-z0-9]"), "")

            if (compactHandle.isNotBlank() && compactHandle == compactTarget) {
                score += 180
            }
            if (primarySeg == targetLower || (compactPrimary.isNotBlank() && compactPrimary == compactTarget)) {
                score += 150
            } else if (primarySeg.startsWith(targetLower)) {
                score += 110
            } else if (compactHandle.startsWith(compactTarget) && compactTarget.length >= 3) {
                score += 90
            } else if (tokens.all { tok -> cleanSegs.any { seg -> Regex("\\b${Regex.escape(tok)}\\b").containsMatchIn(seg) } }) {
                score += 75
            } else if (tokens.all { label.contains(it) }) {
                score += 45
            } else if (!requireStrictTopInbox && tokens.size == 1 && label.contains(tokens[0])) {
                score += 25
            }

            if (score <= 0) continue
            if (requireStrictTopInbox && score < 75) continue

            if (mode == PersonMatchMode.PUBLIC_ACCOUNT) {
                if (isVerified) score += 220
                if (label.contains("m followers")) score += 90
                else if (label.contains("k followers")) score += 40
                else if (label.contains("followers")) score += 15

                val hasFakeWord = fakeWords.any { label.contains(it) } ||
                    (!targetLower.contains("fan") && Regex("\\bfan\\b").containsMatchIn(label)) ||
                    (!targetLower.contains("fc") && Regex("\\bfc\\b").containsMatchIn(label))
                if (hasFakeWord) score -= 320
            } else if (mode == PersonMatchMode.DM_PERSON) {
                // Prefer already-connected DM threads above the "Ask Meta AI" / "More accounts" separator
                if (metaAiHeaderBottom != null && el.bounds.bottom < metaAiHeaderBottom) {
                    score += 60
                }
            }

            if (score > bestScore || (score == bestScore && el.bounds.top < bestTop)) {
                bestScore = score
                bestTop = el.bounds.top
                bestEl = el
            }
        }
        return if (bestScore > 0) bestEl else null
    }

    private fun computeChatViewportBounds(elements: List<ScreenElement>, screenHeight: Int): Pair<Int, Int> {
        val composerTop = elements.firstOrNull { isChatComposerElement(it, screenHeight) }?.bounds?.top
            ?: (screenHeight * 0.85f).toInt()
        val headerBottom = (elements.filter {
            it.bounds.top < screenHeight * 0.17 && it.bounds.height() < screenHeight * 0.14
        }.maxOfOrNull { it.bounds.bottom } ?: (screenHeight * 0.13f).toInt())
            .coerceIn((screenHeight * 0.08f).toInt(), (screenHeight * 0.19f).toInt())
        return Pair(headerBottom, composerTop)
    }

    private suspend fun openMediaCardViewer(
        service: JarvisAccessibilityService,
        mediaCard: ScreenElement,
        screenHeight: Int
    ) {
        service.tapNodeSingleTouch(mediaCard.node)
        val opened = pollCondition(timeoutMs = 1100, intervalMs = 80) {
            service.dumpScreenElements().none { isChatComposerElement(it, screenHeight) }
        }
        if (!opened) {
            service.clickNode(mediaCard.node)
            delay(350)
        }
    }

    private enum class ReelWaitOutcome { COMPLETED, USER_EXITED_APP }

    private suspend fun waitForReelPlaybackStep(
        service: JarvisAccessibilityService,
        screenHeight: Int
    ): ReelWaitOutcome {
        val start = System.currentTimeMillis()
        var maxSeekRatio = 0f
        delay(900)

        while (System.currentTimeMillis() - start < 17_500L) {
            val activePkg = service.rootInActiveWindow?.packageName?.toString()?.lowercase() ?: ""
            if (activePkg.isNotBlank() && !activePkg.contains("instagram")) {
                return ReelWaitOutcome.USER_EXITED_APP
            }

            val els = service.dumpScreenElements()
            // Check if replay prompt appeared or if user manually navigated back to chat
            if (els.any { isChatComposerElement(it, screenHeight) } && System.currentTimeMillis() - start > 2000L) {
                return ReelWaitOutcome.COMPLETED
            }
            if (els.any { labelOf(it).contains("watch again") || labelOf(it).contains("replay") }) {
                return ReelWaitOutcome.COMPLETED
            }

            // Detect live SeekBar completion / loop wrap dynamically when exposed
            val seekBar = els.firstOrNull { it.className.contains("SeekBar", ignoreCase = true) }
            val range = seekBar?.node?.rangeInfo
            if (range != null && range.max > range.min) {
                val ratio = ((range.current - range.min) / (range.max - range.min)).coerceIn(0f, 1f)
                if ((maxSeekRatio >= 0.84f && ratio < 0.16f) || (ratio >= 0.94f && System.currentTimeMillis() - start >= 3500L)) {
                    return ReelWaitOutcome.COMPLETED
                }
                if (ratio > maxSeekRatio) maxSeekRatio = ratio
            }

            delay(250)
        }
        return ReelWaitOutcome.COMPLETED
    }

    private fun buildMediaCardSignature(el: ScreenElement, fallbackIndex: Int): String {
        val raw = labelOf(el).replace(Regex("\\s+"), " ").take(60)
        val res = resIdOf(el)
        return if (raw.isNotBlank()) "$raw|$res" else "unlabeled_${fallbackIndex}_${el.bounds.width()}x${el.bounds.height()}|$res"
    }

    private fun viewportMessagesSignature(
        elements: List<ScreenElement>,
        headerBottom: Int,
        composerTop: Int
    ): String {
        return elements.filter { it.bounds.centerY() in headerBottom..composerTop }
            .joinToString("|") { labelOf(it).take(25) }
    }

    private fun hasMediaDescendant(
        node: AccessibilityNodeInfo?,
        minMediaW: Int,
        minMediaH: Int,
        depth: Int = 0
    ): Boolean {
        if (node == null || depth > 5) return false
        val cls = node.className?.toString()?.substringAfterLast('.')?.lowercase() ?: ""
        val res = node.viewIdResourceName?.substringAfterLast('/')?.lowercase() ?: ""
        val isVisualNode = cls == "imageview" || cls == "videoview" || cls == "textureview" || cls == "surfaceview" ||
            res.contains("image") || res.contains("video") || res.contains("reel") || res.contains("clip") ||
            res.contains("xma") || res.contains("thumbnail") || res.contains("media") || res.contains("cover")
        if (depth > 0 && isVisualNode) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() >= minMediaW && r.height() >= minMediaH) return true
        }
        for (i in 0 until node.childCount) {
            if (hasMediaDescendant(node.getChild(i), minMediaW, minMediaH, depth + 1)) return true
        }
        return false
    }

    private fun findSharedMediaCardsInChat(
        elements: List<ScreenElement>,
        screenWidth: Int,
        screenHeight: Int,
        headerBottom: Int,
        composerTop: Int
    ): List<ScreenElement> {
        val viewportHeight = (composerTop - headerBottom).coerceAtLeast((screenHeight * 0.25f).toInt())
        val minW = (screenWidth * 0.24f).toInt()
        val minH = (screenHeight * 0.085f).toInt()
        val minMediaDescW = (screenWidth * 0.16f).toInt()
        val minMediaDescH = (screenHeight * 0.08f).toInt()
        val maxContainerH = (viewportHeight * 0.86f).toInt()
        val maxContainerW = (screenWidth * 0.94f).toInt()

        val excludedTerms = listOf(
            "voice", "mic", "audio", "record", "camera", "gallery", "sticker", "emoji",
            "send", "video call", "audio call", "react", "reply", "seen", "delivered", "message..."
        )

        data class MediaCandidate(
            val el: ScreenElement,
            val isReelOrVideo: Boolean,
            val isFromFriend: Boolean,
            val score: Int
        )

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

            // Catch reel/video containers even when aggregated caption omits the word "reel"
            val hasVisualPreviewChild = !isOtherMedia &&
                el.bounds.height() >= (screenHeight * 0.14f).toInt() &&
                el.bounds.width() >= (screenWidth * 0.26f).toInt() &&
                (el.node.text.isNullOrBlank() || hasMediaDescendant(el.node, minMediaDescW, minMediaDescH))

            if (!isOtherMedia && !hasVisualPreviewChild) continue

            val isFromFriend = !label.contains("you sent") && !label.startsWith("you:") &&
                el.bounds.left < (screenWidth * 0.28f).toInt()

            val score = when {
                isReelKeyword -> 100
                isVideoOrXma -> 70
                isOtherMedia -> 45
                else -> 30
            }
            candidates.add(MediaCandidate(el, isVideoOrXma, isFromFriend, score))
        }

        if (candidates.isEmpty()) return emptyList()
        // Prefer friend-sent cards and reel/video cards if available; return bottom-to-top (latest first)
        val friendPool = candidates.filter { it.isFromFriend }.ifEmpty { candidates }
        val pool = friendPool.filter { it.isReelOrVideo }.ifEmpty { friendPool }

        val clustered = mutableListOf<ScreenElement>()
        val sortedDesc = pool.sortedWith(compareByDescending<MediaCandidate> { it.el.bounds.bottom }.thenByDescending { it.score })
        for (cand in sortedDesc) {
            val overlapsExisting = clustered.any { kotlin.math.abs(it.bounds.centerY() - cand.el.bounds.centerY()) < (screenHeight * 0.05f).toInt() }
            if (!overlapsExisting) clustered.add(cand.el)
        }
        return clustered
    }

    private fun extractLatestMessageInChat(
        elements: List<ScreenElement>,
        screenWidth: Int,
        screenHeight: Int,
        target: String
    ): String? {
        val (headerBottom, composerTop) = computeChatViewportBounds(elements, screenHeight)
        val ignoreExact = setOf(
            "seen", "seen just now", "delivered", "sending", "sent", "today", "yesterday",
            "active now", "message...", "type a message", "reply", "unsend", "forward",
            "react", "double tap to like", "double tap to react", "tap to react", "hold to react",
            "view profile", "follow", "following", "voice", "camera", "gallery", "like", "audio call", "video call"
        )
        val timeRegex = Regex("^(\\d{1,2}:\\d{2}(\\s?[ap]m)?|(mon|tue|wed|thu|fri|sat|sun)[,\\s]+\\d{1,2}:\\d{2}.*)$")
        val targetLower = target.lowercase().trim()

        data class MsgItem(val el: ScreenElement, val text: String, val isFromFriend: Boolean)

        val items = mutableListOf<MsgItem>()
        for (el in elements) {
            if (el.isEditable) continue
            val cy = el.bounds.centerY()
            if (cy <= headerBottom || cy >= composerTop) continue
            if (el.bounds.height() < 16 || el.bounds.width() < 24) continue

            val raw = el.text.ifBlank { el.contentDescription }.trim()
            val lower = raw.lowercase()
            if (raw.isBlank() || lower == targetLower) continue
            if (lower in ignoreExact || timeRegex.matches(lower)) continue
            if (lower.startsWith("seen ") || lower.startsWith("active ") || lower.contains("you both follow")) continue
            // Skip tiny reaction counter pills on corners while keeping real short words like "Ok", "Ha"
            if (raw.none { it.isLetterOrDigit() } && el.bounds.height() < (screenHeight * 0.045f).toInt() &&
                el.bounds.width() < (screenWidth * 0.12f).toInt()) continue

            val cleaned = cleanChatMessageLabel(raw)
            if (cleaned.isBlank()) continue

            val isFromMe = lower.startsWith("you sent") || lower.startsWith("you:") || lower.startsWith("you replied") ||
                (el.bounds.right >= (screenWidth * 0.87f).toInt() && el.bounds.left > (screenWidth * 0.26f).toInt())
            items.add(MsgItem(el, cleaned, !isFromMe))
        }

        if (items.isEmpty()) return null
        val chosen = items.filter { it.isFromFriend }.maxByOrNull { it.el.bounds.bottom }
            ?: items.maxByOrNull { it.el.bounds.bottom }
        return chosen?.text
    }

    private fun cleanChatMessageLabel(raw: String): String {
        var cleaned = raw
            .replace(Regex("(?i),?\\s*(double tap|tap|hold)\\s+to\\s+(like|react|reply).*"), "")
            .replace(Regex("\\s*\\|\\s*(double tap|tap)\\s+to.*$", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleaned.endsWith("|")) cleaned = cleaned.dropLast(1).trim()
        return cleaned
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

    private fun findMatchingStoryElement(
        elements: List<ScreenElement>,
        target: String,
        screenHeight: Int = Int.MAX_VALUE
    ): ScreenElement? {
        val tokens = target.lowercase().trim().split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return null

        // Pass 1: Strict match (all spoken name tokens match)
        val strict = elements.firstOrNull { el ->
            val label = labelOf(el)
            if (!label.contains("story")) return@firstOrNull false
            if (label.contains("your story") && !tokens.contains("your") && !tokens.contains("my")) return@firstOrNull false
            tokens.all { label.contains(it) } && hasClickableOrAncestor(el.node, maxDepth = 4)
        }
        if (strict != null) return strict

        // Pass 2: Handle multi-word name vs Story tray handle or primary first name
        if (tokens.size > 1) {
            val joined = tokens.joinToString("")
            val primary = tokens[0]
            return elements.firstOrNull { el ->
                if (el.bounds.top >= screenHeight * 0.44) return@firstOrNull false
                val label = labelOf(el)
                if (!label.contains("story")) return@firstOrNull false
                if (label.contains("your story") && !tokens.contains("your") && !tokens.contains("my")) return@firstOrNull false
                val matches = label.contains(joined) || (primary.length >= 3 && label.contains(primary))
                matches && hasClickableOrAncestor(el.node, maxDepth = 4)
            }
        }
        return null
    }

    private fun storyTraySignature(elements: List<ScreenElement>, screenHeight: Int): String {
        return elements.filter { el ->
            el.bounds.top < screenHeight * 0.44 && labelOf(el).contains("story")
        }.sortedBy { it.bounds.left }.joinToString("|") { labelOf(it) }
    }

    private fun findStoryTrayRect(elements: List<ScreenElement>, screenWidth: Int, screenHeight: Int): Rect? {
        val stories = elements.filter { el ->
            el.bounds.top < screenHeight * 0.44 && labelOf(el).contains("story")
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
