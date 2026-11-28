package com.jarvis.assistant.engine

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.JarvisAccessibilityService
import com.jarvis.assistant.ScreenElement
import com.jarvis.assistant.brain.SocialIntent
import com.jarvis.assistant.debug.JarvisLogStore
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
    private const val ALIAS_PREFS = "jarvis_insta_aliases"

    suspend fun execute(
        service: JarvisAccessibilityService,
        intent: SocialIntent,
        rawInstruction: String
    ): FastNavResult {
        val steps = mutableListOf<String>()
        val initialTree = service.dumpScreenElements()
        learnAliasesFromTree(service, initialTree)
        JarvisLogStore.log(
            "INSTA_NAV",
            "START action=${intent.actionType} target='${intent.target}' count=${intent.count} fromMe=${intent.fromMe} msgLen=${intent.message.length}"
        )
        JarvisLogStore.logScreenTree("INSTA_ENTRY", initialTree)
        dismissBlockingPopupIfAny(service)?.let { steps.add(it) }

        val lower = rawInstruction.lowercase()
        val resolvedCount = resolveRequestedMediaCount(intent, lower)
        val senderFilter = resolveMediaSenderFilter(intent, lower)

        val res = when (intent.actionType) {
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
                    executeChatMedia(service, intent.target, requestedCount = -1, senderFilter = senderFilter, steps = steps)
                }
            }
            "chat_media" -> {
                if (intent.target.isBlank()) {
                    FastNavResult(handled = false, stepsTaken = steps)
                } else {
                    executeChatMedia(service, intent.target, requestedCount = resolvedCount, senderFilter = senderFilter, steps = steps)
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
            "profile_info" -> {
                if (intent.target.isBlank()) {
                    FastNavResult(handled = false, stepsTaken = steps)
                } else {
                    executeProfileInfo(service, intent.target, steps)
                }
            }
            "profile_post" -> {
                if (intent.target.isBlank()) {
                    FastNavResult(handled = false, stepsTaken = steps)
                } else {
                    executeProfilePost(service, intent.target, lower, steps)
                }
            }
            "reels" -> {
                val looksLikeFriendSharedReel = intent.target.isNotBlank() &&
                    (lower.contains("bhej") || lower.contains("sent") || lower.contains("share") ||
                        lower.contains("chat") || lower.contains("dm"))
                if (looksLikeFriendSharedReel) {
                    executeChatMedia(service, intent.target, requestedCount = resolvedCount, senderFilter = senderFilter, steps = steps)
                } else {
                    executeReels(service, rawInstruction, steps)
                }
            }
            else -> FastNavResult(handled = false, stepsTaken = steps)
        }
        JarvisLogStore.log(
            "INSTA_NAV",
            "END handled=${res.handled} success=${res.success} msg='${res.message}' steps=${res.stepsTaken}"
        )
        return res
    }

    private fun resolveMediaSenderFilter(intent: SocialIntent, lower: String): MediaSenderFilter {
        if (intent.fromMe) return MediaSenderFilter.FROM_ME
        val explicitFromMe = lower.contains("meri bheji") || lower.contains("maine bheji") ||
            lower.contains("maine jo bheji") || lower.contains("meri wali") ||
            lower.contains("meri reels") || lower.contains("sent by me") || lower.contains("my sent")
        return if (explicitFromMe) MediaSenderFilter.FROM_ME else MediaSenderFilter.FROM_FRIEND
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
        senderFilter: MediaSenderFilter,
        steps: MutableList<String>
    ): FastNavResult {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        if (!openDirectChatThread(service, target, w, h, steps)) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        // Brief initial settle inside open chat thread
        delay(220)

        val ownerPhrase = if (senderFilter == MediaSenderFilter.FROM_ME) "aapki bheji hui" else "$target ki bheji hui"
        val playedSignatures = mutableSetOf<String>()
        val playedViewportRects = mutableSetOf<String>()
        var playedCount = 0
        var emptyScrolls = 0
        var sameScreenScrolls = 0
        var scrollEpoch = 0
        var retriedInitialRender = false
        val maxReelsCap = if (requestedCount > 0) requestedCount else 20

        while (playedCount < maxReelsCap) {
            val els = service.dumpScreenElements()
            val (headerBottom, composerTop) = computeChatViewportBounds(els, h)
            val scanReasons = mutableListOf<String>()
            val cards = findSharedMediaCardsInChat(els, w, h, headerBottom, composerTop, senderFilter, scanReasons)
            val seenBaseOnScreen = mutableMapOf<String, Int>()
            val unplayed = mutableListOf<Triple<ScreenElement, String, String>>()
            for (card in cards) {
                val rk = "${card.bounds.left},${card.bounds.top},${card.bounds.right},${card.bounds.bottom}"
                val baseKey = "${labelOf(card).take(40)}|${resIdOf(card)}|${card.bounds.width()}"
                val occIdx = seenBaseOnScreen.getOrDefault(baseKey, 0)
                seenBaseOnScreen[baseKey] = occIdx + 1
                if (rk in playedViewportRects) continue
                val sig = buildMediaCardSignature(card, els, occIdx, headerBottom, composerTop, scrollEpoch)
                if (sig !in playedSignatures) {
                    unplayed.add(Triple(card, sig, rk))
                }
            }
            // If only a top-clipped unlabeled card remains at headerBottom, scroll it down into full view first so its full creator attribution is read before playing
            val candidate = unplayed.firstOrNull()
            val shouldScrollTopClippedFirst = candidate != null &&
                unplayed.size == 1 &&
                candidate.second.startsWith("unlabeled_") &&
                candidate.first.bounds.top <= headerBottom + 2 &&
                emptyScrolls < 16 &&
                sameScreenScrolls == 0
            val pickedTriple = if (shouldScrollTopClippedFirst) null else candidate
            val nextCard = pickedTriple?.first
            val nextCardSig = pickedTriple?.second
            val nextCardRect = pickedTriple?.third
            JarvisLogStore.log(
                "DM_MEDIA_SCAN",
                "filter=$senderFilter played=$playedCount epoch=$scrollEpoch emptyScrolls=$emptyScrolls headerB=$headerBottom composerT=$composerTop foundCards=${cards.size} picked=${JarvisLogStore.describeElement(nextCard)} sig='$nextCardSig' cands=$scanReasons"
            )
            if (nextCard == null && emptyScrolls <= 2) {
                JarvisLogStore.logScreenTree("DM_MEDIA_TREE", els)
            }

            if (nextCard == null) {
                if (emptyScrolls == 0 && playedCount == 0 && !retriedInitialRender) {
                    retriedInitialRender = true
                    delay(300)
                    continue
                }
                // Climb upward deeply so sender's older reels above recent texts/own messages are still found
                if (emptyScrolls >= 16 || sameScreenScrolls >= 2) break
                val beforeSig = viewportMessagesSignature(els, headerBottom, composerTop)
                service.scrollExact("up", upStartFraction = 0.25f, upEndFraction = 0.76f)
                delay(140)
                val afterEls = service.dumpScreenElements()
                val afterSig = viewportMessagesSignature(afterEls, headerBottom, composerTop)
                if (beforeSig == afterSig) {
                    sameScreenScrolls++
                } else {
                    playedViewportRects.clear()
                    scrollEpoch++
                    sameScreenScrolls = 0
                    emptyScrolls++
                }
                continue
            }

            emptyScrolls = 0
            sameScreenScrolls = 0
            if (nextCardRect != null) playedViewportRects.add(nextCardRect)
            if (nextCardSig != null) playedSignatures.add(nextCardSig)
            val openedViewer = openMediaCardViewer(service, nextCard, els, headerBottom, composerTop, h)
            if (!openedViewer) {
                JarvisLogStore.log("DM_MEDIA_OPEN_MISS", "Card failed to open fullscreen viewer; skipping ${JarvisLogStore.describeElement(nextCard)}")
                continue
            }
            playedCount++
            steps.add("Playing shared reel #$playedCount ($ownerPhrase)")

            // For single latest reel OR the final requested Nth reel, keep it open playing on screen!
            val isFinalFixedTarget = requestedCount > 0 && playedCount >= requestedCount
            if (isFinalFixedTarget) {
                val msg = if (requestedCount == 1) {
                    "Instagram par $ownerPhrase latest reel chala di hai Sir."
                } else {
                    "$target ki chat mein $ownerPhrase last $playedCount reels chala di hain Sir."
                }
                return FastNavResult(handled = true, success = true, message = msg, stepsTaken = steps)
            }

            val waitOutcome = waitForReelPlaybackStep(service, h)
            JarvisLogStore.log("DM_MEDIA_WAIT", "reel#$playedCount outcome=$waitOutcome")
            if (waitOutcome == ReelWaitOutcome.USER_EXITED_APP) {
                return FastNavResult(
                    handled = true,
                    success = true,
                    message = "$ownerPhrase $playedCount reels play ki hain Sir.",
                    stepsTaken = steps
                )
            }

            // Return cleanly to friend's chat thread WITHOUT double-backing out of the thread
            if (!isBackInDmChat(service.dumpScreenElements(), h)) {
                service.performBack()
                var backInChat = pollCondition(timeoutMs = 2000, intervalMs = 75) {
                    isBackInDmChat(service.dumpScreenElements(), h)
                }
                if (!backInChat && isInReelOrMediaViewer(service.dumpScreenElements())) {
                    service.performBack()
                    backInChat = pollCondition(timeoutMs = 1400, intervalMs = 75) {
                        isBackInDmChat(service.dumpScreenElements(), h)
                    }
                }
                if (!backInChat) break
                delay(260)
            }
        }

        return if (playedCount > 0) {
            val summary = if (requestedCount > 1) {
                "$target ki chat mein $ownerPhrase saari $playedCount reels play kar di hain Sir."
            } else {
                "$target ki chat mein $ownerPhrase saari ($playedCount) reels play kar di hain Sir."
            }
            FastNavResult(handled = true, success = true, message = summary, stepsTaken = steps)
        } else {
            FastNavResult(
                handled = true,
                success = false,
                message = "$target ki chat mein $ownerPhrase koi reel nahi mili Sir.",
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

        // Opening thread marks messages as Seen automatically; poll cleanly until chat message bubbles settle
        var lastMsg: String? = null
        val deadline = System.currentTimeMillis() + 1600
        while (System.currentTimeMillis() < deadline) {
            lastMsg = extractLatestMessageInChat(service.dumpScreenElements(), w, h, target)
            if (!lastMsg.isNullOrBlank()) break
            delay(75)
        }
        val finalChatEls = service.dumpScreenElements()
        JarvisLogStore.log("READ_DM_RESULT", "target='$target' extracted='$lastMsg'")
        if (lastMsg.isNullOrBlank()) {
            JarvisLogStore.logScreenTree("READ_DM_MISS", finalChatEls)
            return FastNavResult(handled = false, stepsTaken = steps)
        }

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
        // If stuck inside top-right "New message" compose screen from an earlier turn, press Back first
        if (isInNewMessageComposeScreen(service.dumpScreenElements())) {
            JarvisLogStore.log("INSTA_INBOX", "Detected New-Message Compose screen; pressing Back")
            service.performBack()
            delay(320)
        }
        if (isInMessagesInbox(service, screenHeight)) {
            JarvisLogStore.log("INSTA_INBOX", "Already in Messages Inbox")
            return true
        }

        var inboxBtn = pollElement(service, timeoutMs = 950, intervalMs = 60) { findInboxButton(it, screenHeight) }
        if (inboxBtn == null) {
            for (backStep in 1..4) {
                val elsNow = service.dumpScreenElements()
                if (isInMessagesInbox(service, screenHeight)) return true
                inboxBtn = findInboxButton(elsNow, screenHeight)
                if (inboxBtn != null) break

                val homeTab = findBottomNavTab(elsNow, screenHeight, listOf("home", "feed"), listOf("feed_tab", "home_tab"))
                if (homeTab != null && !homeTab.node.isSelected) {
                    JarvisLogStore.log("INSTA_INBOX", "Switching to Home tab: ${JarvisLogStore.describeElement(homeTab)}")
                    service.tapNodeSingleTouch(homeTab.node)
                    steps.add("Tapped Home tab to access Messages")
                    inboxBtn = pollElement(service, timeoutMs = 1100, intervalMs = 60) { findInboxButton(it, screenHeight) }
                    if (inboxBtn != null) break
                } else {
                    val explicitBack = elsNow.firstOrNull { el ->
                        el.bounds.top < screenHeight * 0.18 &&
                            (labelOf(el) == "back" || resIdOf(el).contains("back")) &&
                            hasClickableOrAncestor(el.node, 3)
                    }
                    if (explicitBack != null) service.tapNodeSingleTouch(explicitBack.node) else service.performBack()
                    delay(320)
                    if (isInNewMessageComposeScreen(service.dumpScreenElements())) {
                        service.performBack()
                        delay(280)
                    }
                }
            }
            if (isInMessagesInbox(service, screenHeight)) return true
            if (inboxBtn == null) {
                inboxBtn = pollElement(service, timeoutMs = 700, intervalMs = 60) { findInboxButton(it, screenHeight) }
            }
        }

        if (inboxBtn == null) {
            JarvisLogStore.log("INSTA_INBOX_MISS", "Messages icon not found in tree")
            JarvisLogStore.logScreenTree("INBOX_BTN_MISS", service.dumpScreenElements())
            return false
        }

        JarvisLogStore.log("INSTA_INBOX_PICK", "inboxBtn=${JarvisLogStore.describeElement(inboxBtn)}")
        service.tapNodeSingleTouch(inboxBtn.node)
        steps.add("Opened Messages/Inbox")

        val inboxLoaded = pollCondition(timeoutMs = 1600, intervalMs = 55) {
            isInMessagesInbox(service, screenHeight)
        }
        if (!inboxLoaded) {
            dismissBlockingPopupIfAny(service)?.let { steps.add(it) }
            val loadedRetry = isInMessagesInbox(service, screenHeight)
            JarvisLogStore.log("INSTA_INBOX_WAIT", "inboxLoaded=$loadedRetry after popup check")
            if (!loadedRetry) JarvisLogStore.logScreenTree("INBOX_NOT_LOADED", service.dumpScreenElements())
            return loadedRetry
        }
        JarvisLogStore.log("INSTA_INBOX", "Messages Inbox loaded cleanly")
        learnAliasesFromTree(service, service.dumpScreenElements())
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
                JarvisLogStore.log("INSTA_CHAT", "Already inside matching chat thread with '$target'")
                steps.add("Already inside chat with $target")
                return true
            }
            // Exit unrelated chat back to Inbox (handle soft keyboard consuming the first Back press!)
            JarvisLogStore.log("INSTA_CHAT", "Inside another chat; backing out to Inbox")
            service.performBack()
            delay(300)
            if (findChatComposer(service, screenHeight) != null) {
                service.performBack()
                delay(300)
            }
        }

        if (!openMessagesInbox(service, screenHeight, steps)) return false

        // Search directly via "Search or ask Meta AI" bar (NO IME_ENTER) for reliable handle/name disambiguation
        val searchBottom = activateAndTypeInSearchBar(service, screenWidth, screenHeight, target, steps, pressEnter = false)
            ?: return false

        // Wait up to 4.5s for network "Searching..." and connected DM rows to settle before picking
        val searchDeadline = System.currentTimeMillis() + 4500L
        var firstMatchTime = 0L
        while (System.currentTimeMillis() < searchDeadline) {
            val els = service.dumpScreenElements()
            learnAliasesFromTree(service, els)
            val candidate = findBestPersonMatch(els, target, searchBottom, screenHeight, screenWidth, mode = matchMode)
            if (candidate != null) {
                if (firstMatchTime == 0L) firstMatchTime = System.currentTimeMillis()
                val lbl = labelOf(candidate)
                val isSearching = els.any { el ->
                    val l = labelOf(el)
                    el.bounds.top > searchBottom && (l == "searching…" || l == "searching...")
                }
                val looksSuggested = matchMode == PersonMatchMode.DM_PERSON &&
                    (lbl.contains("you both follow") || lbl.contains("followed by") || lbl.contains("follows you"))
                if (!isSearching && (!looksSuggested || System.currentTimeMillis() - firstMatchTime >= 1400L)) break
            }
            delay(80)
        }
        val finalSearchEls = service.dumpScreenElements()
        learnAliasesFromTree(service, finalSearchEls)
        val searchReasons = mutableListOf<String>()
        val chosenRow = findBestPersonMatch(
            finalSearchEls, target, searchBottom, screenHeight, screenWidth,
            mode = matchMode, debugReasons = searchReasons
        )
        JarvisLogStore.log("DM_SEARCH_MATCH", "mode=$matchMode minTopY=$searchBottom reasons=$searchReasons -> picked=${JarvisLogStore.describeElement(chosenRow)}")
        if (chosenRow == null) {
            JarvisLogStore.logScreenTree("DM_SEARCH_MISS", finalSearchEls)
            return false
        }

        service.tapNodeSingleTouch(chosenRow.node)
        steps.add("Selected chat '${chosenRow.text.ifBlank { target }}'")

        var composer = pollElement(service, timeoutMs = 1300, intervalMs = 55) { elements ->
            elements.firstOrNull { isChatComposerElement(it, screenHeight) }
        }
        if (composer == null) {
            val midEls = service.dumpScreenElements()
            val retryRow = findBestPersonMatch(midEls, target, searchBottom, screenHeight, screenWidth, mode = matchMode)
            if (retryRow != null) {
                val clicked = service.clickNode(retryRow.node)
                if (!clicked) service.tapNodeSingleTouch(retryRow.node)
                steps.add("Retried chat '${retryRow.text.ifBlank { target }}'")
                composer = pollElement(service, timeoutMs = 1500, intervalMs = 55) { elements ->
                    elements.firstOrNull { isChatComposerElement(it, screenHeight) }
                }
            }
        }
        JarvisLogStore.log("DM_COMPOSER", "Loaded composer=${JarvisLogStore.describeElement(composer)}")
        if (composer != null) {
            learnAliasesFromTree(service, service.dumpScreenElements())
        } else {
            JarvisLogStore.logScreenTree("DM_COMPOSER_MISS", service.dumpScreenElements())
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
        JarvisLogStore.log("DM_TYPE", "typed=$typed enteredOk=$enteredOk textLen=${freshComposer.node.text?.length ?: 0}")
        if (!enteredOk) {
            service.tapNodeSingleTouch(freshComposer.node)
            delay(85)
            freshComposer = service.dumpScreenElements().firstOrNull { isChatComposerElement(it, screenHeight) } ?: freshComposer
            typed = service.typeText(freshComposer.node, message, pressEnter = false)
        }

        if (!typed) {
            JarvisLogStore.log("DM_TYPE_FAIL", "Unable to type into composer; handing off")
            return FastNavResult(handled = false, stepsTaken = steps)
        }
        steps.add("Typed message into chat")

        val sendBtn = pollElement(service, timeoutMs = 1400, intervalMs = 50) { elements ->
            findGenuineChatSendButton(elements, screenHeight)
        }
        JarvisLogStore.log("DM_SEND_BTN", "picked=${JarvisLogStore.describeElement(sendBtn)}")
        if (sendBtn == null) {
            JarvisLogStore.logScreenTree("DM_SEND_BTN_MISS", service.dumpScreenElements())
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        service.tapNodeSingleTouch(sendBtn.node)
        delay(240)

        // Confirm send: if Send button is genuinely still present, retry once via clickNode
        val stillUnsent = findGenuineChatSendButton(service.dumpScreenElements(), screenHeight)
        if (stillUnsent != null) {
            JarvisLogStore.log("DM_SEND_RETRY", "Send still visible; retrying clickNode on ${JarvisLogStore.describeElement(stillUnsent)}")
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
        var aliases = lookupAliases(service, target)

        // 1. Check immediately only when Home Story tray is already visible on screen
        val initialEls = service.dumpScreenElements()
        var trayRect = findStoryTrayRect(initialEls, w, h)
        if (trayRect != null) {
            val directMatch = findMatchingStoryElement(initialEls, target, w, h, aliases)
            if (directMatch != null) {
                service.tapNodeSingleTouch(directMatch.node)
                delay(350)
                steps.add("Opened $target's story directly")
                return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
            }
        }

        // 2. Back out cleanly from Reels/Chat/Search/Inbox until Home tab appears, then reveal Story Tray at top
        if (trayRect == null) {
            for (attempt in 1..6) {
                val elsNow = service.dumpScreenElements()
                learnAliasesFromTree(service, elsNow)
                aliases = lookupAliases(service, target)
                trayRect = findStoryTrayRect(elsNow, w, h)
                if (trayRect != null) break

                val homeTab = findBottomNavTab(elsNow, h, listOf("home", "feed"), listOf("feed_tab", "home_tab"))
                if (homeTab != null) {
                    service.tapNodeSingleTouch(homeTab.node)
                    steps.add("Tapped Home tab for Story tray")
                    val ready = pollCondition(timeoutMs = 1100, intervalMs = 50) {
                        findStoryTrayRect(service.dumpScreenElements(), w, h) != null
                    }
                    if (!ready) {
                        for (scrollTry in 1..4) {
                            service.scrollExact("up")
                            delay(120)
                            if (findStoryTrayRect(service.dumpScreenElements(), w, h) != null) break
                        }
                    }
                    trayRect = findStoryTrayRect(service.dumpScreenElements(), w, h)
                    if (trayRect != null) break
                } else {
                    val explicitBack = elsNow.firstOrNull { el ->
                        el.bounds.top < h * 0.18 &&
                            (labelOf(el) == "back" || resIdOf(el).contains("back")) &&
                            hasClickableOrAncestor(el.node, 3)
                    }
                    if (explicitBack != null) service.tapNodeSingleTouch(explicitBack.node) else service.performBack()
                    delay(340)
                }
            }
        }

        val homeEls = service.dumpScreenElements()
        trayRect = findStoryTrayRect(homeEls, w, h)
        if (trayRect == null) {
            JarvisLogStore.log("STORY_HOME_MISS", "Could not reach Home Story tray")
            JarvisLogStore.logScreenTree("STORY_HOME_MISS", homeEls)
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        // 3. Check Story Tray before sliding (supports both spoken name and learned username handles)
        var storyEl = findMatchingStoryElement(homeEls, target, w, h, aliases)
        if (storyEl != null) {
            service.tapNodeSingleTouch(storyEl.node)
            delay(350)
            steps.add("Opened $target's story on Home tray")
            return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
        }

        // 4. Slide Home Story Tray left horizontally until target's story appears or tray ends
        var stagnantCount = 0
        for (swipeAttempt in 1..12) {
            val currentEls = service.dumpScreenElements()
            val curTrayRect = findStoryTrayRect(currentEls, w, h) ?: break
            val beforeSig = storyTraySignature(currentEls, h)

            service.swipeHorizontalInRect(curTrayRect, toLeft = true)
            delay(260)

            val afterEls = service.dumpScreenElements()
            storyEl = findMatchingStoryElement(afterEls, target, w, h, aliases)
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

        // 5. Fallback if username handle differs from display name and wasn't cached yet
        if (openInboxStoryFallback(service, target, w, h, steps)) {
            return FastNavResult(true, true, "Instagram par $target ki story chala di hai Sir.", steps)
        }

        steps.add("Scanned Home Story tray without Search; $target not found")
        return FastNavResult(
            handled = true,
            success = false,
            message = "Homepage story tray mein $target ki story nahi mili Sir.",
            stepsTaken = steps
        )
    }

    private suspend fun openInboxStoryFallback(
        service: JarvisAccessibilityService,
        target: String,
        screenWidth: Int,
        screenHeight: Int,
        steps: MutableList<String>
    ): Boolean {
        if (!openMessagesInbox(service, screenHeight, steps)) return false
        val tokens = target.lowercase().trim().split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return false

        val inboxEls = service.dumpScreenElements()
        learnAliasesFromTree(service, inboxEls)
        val directAvatar = inboxEls.firstOrNull { av ->
            labelOf(av).contains("open story of ") &&
                inboxEls.any { row ->
                    row !== av && !labelOf(row).contains("open story of ") &&
                        row.bounds.top <= av.bounds.bottom && row.bounds.bottom >= av.bounds.top &&
                        tokens.all { labelOf(row).contains(it) }
                }
        }
        if (directAvatar != null) {
            service.tapNodeSingleTouch(directAvatar.node)
            delay(350)
            steps.add("Opened $target's story from Inbox")
            return true
        }

        val searchBottom = activateAndTypeInSearchBar(service, screenWidth, screenHeight, target, steps, pressEnter = false)
            ?: return false
        val matched = pollElement(service, timeoutMs = 2600, intervalMs = 80) { els ->
            findBestPersonMatch(els, target, searchBottom, screenHeight, screenWidth, mode = PersonMatchMode.DM_PERSON)
        } ?: return false
        learnAliasesFromTree(service, service.dumpScreenElements())
        service.tapNodeSingleTouch(matched.node)
        val loaded = pollCondition(timeoutMs = 1500, intervalMs = 60) {
            service.dumpScreenElements().any { isChatComposerElement(it, screenHeight) }
        }
        if (!loaded) return false
        val chatEls = service.dumpScreenElements()
        learnAliasesFromTree(service, chatEls)
        val headerStory = chatEls.firstOrNull {
            resIdOf(it) == "header_avatar" && labelOf(it).contains("open story of")
        } ?: return false
        service.tapNodeSingleTouch(headerStory.node)
        delay(350)
        steps.add("Opened $target's story from chat header")
        return true
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

    // ═════════════════════════════════════════════════════════════════
    // 3B. PROFILE INFO (FOLLOWERS / FOLLOWING / POSTS) & LATEST POST
    // ═════════════════════════════════════════════════════════════════
    private suspend fun executeProfileInfo(
        service: JarvisAccessibilityService,
        target: String,
        steps: MutableList<String>
    ): FastNavResult {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        val openedProfile = navigateToUserProfileViaSearch(service, w, h, target, steps)
        if (!openedProfile) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        val ready = pollCondition(timeoutMs = 1500, intervalMs = 60) {
            val els = service.dumpScreenElements()
            els.any { resIdOf(it).contains("profile_header_followers") || labelOf(it).contains("followers") }
        }
        val els = service.dumpScreenElements()
        val displayName = els.firstOrNull {
            val r = resIdOf(it)
            r == "profile_header_full_name_above_vanity" || r.contains("profile_header_full_name") || r == "action_bar_title"
        }?.text?.trim().orEmpty().ifBlank { target }

        fun formatStat(raw: String): String = raw
            .replace(Regex("(\\d)\\s*(posts?|followers?|following)", RegexOption.IGNORE_CASE), "$1 $2")
            .replace(Regex("\\s+"), " ")
            .trim()

        val followersRaw = els.firstOrNull {
            resIdOf(it).contains("profile_header_followers") ||
                (labelOf(it).contains("followers") && it.bounds.top < bottomNavBarTop(els, h))
        }?.let { it.text.ifBlank { it.contentDescription } }?.trim().orEmpty()

        val followingRaw = els.firstOrNull {
            resIdOf(it).contains("profile_header_following") ||
                (labelOf(it).contains("following") && !labelOf(it).equals("following", true) && it.bounds.top < bottomNavBarTop(els, h))
        }?.let { it.text.ifBlank { it.contentDescription } }?.trim().orEmpty()

        val postsRaw = els.firstOrNull {
            resIdOf(it).contains("profile_header_post_count") ||
                (Regex("\\d+.*posts?", RegexOption.IGNORE_CASE).containsMatchIn(labelOf(it)) && it.bounds.top < bottomNavBarTop(els, h))
        }?.let { it.text.ifBlank { it.contentDescription } }?.trim().orEmpty()

        JarvisLogStore.log("INSTA_PROFILE_INFO", "ready=$ready name='$displayName' followers='$followersRaw' following='$followingRaw' posts='$postsRaw'")
        if (followersRaw.isBlank() && postsRaw.isBlank()) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        val parts = listOfNotNull(
            followersRaw.takeIf { it.isNotBlank() }?.let { formatStat(it) },
            followingRaw.takeIf { it.isNotBlank() }?.let { formatStat(it) },
            postsRaw.takeIf { it.isNotBlank() }?.let { formatStat(it) }
        )
        steps.add("Read profile stats for $displayName")
        return FastNavResult(
            handled = true,
            success = true,
            message = "Instagram par $displayName ke ${parts.joinToString(", ")} hain Sir.",
            stepsTaken = steps
        )
    }

    private suspend fun executeProfilePost(
        service: JarvisAccessibilityService,
        target: String,
        lowerInstruction: String,
        steps: MutableList<String>
    ): FastNavResult {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        val openedProfile = navigateToUserProfileViaSearch(service, w, h, target, steps)
        if (!openedProfile) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        pollCondition(timeoutMs = 1500, intervalMs = 60) {
            val els = service.dumpScreenElements()
            findProfileGridTiles(els, h).isNotEmpty()
        }

        val wantsReelsTab = lowerInstruction.contains("reel") && !lowerInstruction.contains("post")
        if (wantsReelsTab) {
            val reelsTab = service.dumpScreenElements().firstOrNull { el ->
                val r = resIdOf(el)
                val l = labelOf(el)
                (r == "profile_tab_icon_view" && l.contains("reel")) || l == "reels"
            }
            if (reelsTab != null) {
                service.tapNodeSingleTouch(reelsTab.node)
                steps.add("Switched to Reels tab on $target profile")
                delay(320)
            }
        }

        var tiles = findProfileGridTiles(service.dumpScreenElements(), h)
        if (tiles.isEmpty()) {
            service.scrollExact("down")
            delay(220)
            tiles = findProfileGridTiles(service.dumpScreenElements(), h)
        }
        val latestTile = tiles.firstOrNull { !labelOf(it).contains("pinned") } ?: tiles.firstOrNull()
        JarvisLogStore.log("INSTA_PROFILE_POST", "tiles=${tiles.size} picked=${JarvisLogStore.describeElement(latestTile)}")
        if (latestTile == null) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }

        service.tapNodeSingleTouch(latestTile.node)
        steps.add("Opened latest post '${latestTile.contentDescription.ifBlank { target }}'")
        var leftProfile = pollCondition(timeoutMs = 1400, intervalMs = 60) {
            !isOnProfileScreen(service.dumpScreenElements(), h)
        }
        if (!leftProfile) {
            val liveTile = findProfileGridTiles(service.dumpScreenElements(), h)
                .let { list -> list.firstOrNull { !labelOf(it).contains("pinned") } ?: list.firstOrNull() }
            if (liveTile != null) {
                val clicked = service.clickNode(liveTile.node)
                if (!clicked) service.tapNodeSingleTouch(liveTile.node)
                leftProfile = pollCondition(timeoutMs = 1200, intervalMs = 60) {
                    !isOnProfileScreen(service.dumpScreenElements(), h)
                }
            }
        }
        if (!leftProfile) {
            return FastNavResult(handled = false, stepsTaken = steps)
        }
        return FastNavResult(
            handled = true,
            success = true,
            message = "Instagram par $target ki latest post khol di hai Sir.",
            stepsTaken = steps
        )
    }

    private fun isOnProfileScreen(elements: List<ScreenElement>, screenHeight: Int): Boolean {
        val hasProfileHeader = elements.any { resIdOf(it).startsWith("profile_header_") }
        val hasProfileTabs = elements.any { resIdOf(it) == "profile_tab_icon_view" }
        return hasProfileHeader || (hasProfileTabs && findFollowOrFollowingButton(elements, screenHeight) != null)
    }

    private fun isOnProfileForTarget(elements: List<ScreenElement>, target: String, screenHeight: Int): Boolean {
        if (!isOnProfileScreen(elements, screenHeight)) return false
        val tokens = target.lowercase().trim().split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return false
        val compactTarget = target.lowercase().replace(Regex("[^a-z0-9]"), "")
        val headerNodes = elements.filter { el ->
            val r = resIdOf(el)
            r.startsWith("profile_header_full_name") || r == "action_bar_title" || r.contains("vanity")
        }
        return headerNodes.any { el ->
            val l = labelOf(el)
            val compactL = l.replace(Regex("[^a-z0-9]"), "")
            tokens.all { l.contains(it) } || (compactTarget.length >= 3 && compactL.contains(compactTarget))
        }
    }

    private fun findProfileGridTiles(elements: List<ScreenElement>, screenHeight: Int): List<ScreenElement> {
        val navTop = bottomNavBarTop(elements, screenHeight)
        val tabBottom = elements.filter { resIdOf(it) == "profile_tab_icon_view" }
            .maxOfOrNull { it.bounds.bottom }
            ?: elements.filter { resIdOf(it).startsWith("profile_header_") }
                .maxOfOrNull { it.bounds.bottom }
            ?: 0
        return elements.filter { el ->
            if (el.isEditable) return@filter false
            val cy = el.bounds.centerY()
            if (cy <= tabBottom || el.bounds.top >= navTop) return@filter false
            val res = resIdOf(el)
            val lbl = labelOf(el)
            val looksLikeGridItem = res == "image_button" || lbl.contains("at row ") ||
                res.contains("grid_card") || res.contains("media_set") || res.contains("clips_grid")
            looksLikeGridItem && hasClickableOrAncestor(el.node, maxDepth = 4)
        }.sortedWith(compareBy<ScreenElement> { it.bounds.top }.thenBy { it.bounds.left })
    }

    private suspend fun navigateToUserProfileViaSearch(
        service: JarvisAccessibilityService,
        screenWidth: Int,
        screenHeight: Int,
        target: String,
        steps: MutableList<String>
    ): Boolean {
        if (isOnProfileForTarget(service.dumpScreenElements(), target, screenHeight)) {
            return true
        }
        // Check if Search tab is already selected or Search bar is already at top of Explore
        val searchBar = findSearchTriggerOrInput(service.dumpScreenElements(), screenWidth, screenHeight)
        val isSearchTabSelected = findBottomNavTab(service, screenHeight, listOf("search"), listOf("search_tab"))?.node?.isSelected == true
        val inDmInbox = isInMessagesInbox(service, screenHeight)

        if (inDmInbox || (!isSearchTabSelected && searchBar == null)) {
            var searchTab = findBottomNavTab(service, screenHeight, listOf("search and explore", "search"), listOf("search_tab", "explore_tab"))
            if (searchTab == null) {
                for (backTry in 1..4) {
                    service.performBack()
                    delay(320)
                    searchTab = findBottomNavTab(service.dumpScreenElements(), screenHeight, listOf("search and explore", "search"), listOf("search_tab", "explore_tab"))
                    if (searchTab != null) break
                }
            }
            if (searchTab == null) return false
            service.tapNodeSingleTouch(searchTab.node)
            steps.add("Tapped Search tab")
            delay(240)
        }

        // Activate & type account query without IME Enter so live verified account autocomplete stays open
        val searchBottom = activateAndTypeInSearchBar(service, screenWidth, screenHeight, target, steps, pressEnter = false)
            ?: return false

        var matchedAccount = pollElement(service, timeoutMs = 2600, intervalMs = 75) { elements ->
            findBestPersonMatch(elements, target, searchBottom, screenHeight, screenWidth, mode = PersonMatchMode.PUBLIC_ACCOUNT)
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
                matchedAccount = pollElement(service, timeoutMs = 2200, intervalMs = 75) { elements ->
                    findBestPersonMatch(elements, target, searchBottom, screenHeight, screenWidth, mode = PersonMatchMode.PUBLIC_ACCOUNT)
                }
            }
        }

        if (matchedAccount == null) return false
        service.tapNodeSingleTouch(matchedAccount.node)
        steps.add("Opened profile '${matchedAccount.text.ifBlank { target }}'")

        var onProfile = pollCondition(timeoutMs = 2000, intervalMs = 75) {
            isOnProfileScreen(service.dumpScreenElements(), screenHeight)
        }
        // If tapping autocomplete landed on Search Results tab instead of opening Profile directly, tap top account row
        if (!onProfile) {
            val midEls = service.dumpScreenElements()
            val accountsTab = midEls.firstOrNull { el ->
                val l = labelOf(el)
                (l == "accounts" || l == "users") && hasClickableOrAncestor(el.node, 3) && !el.node.isSelected
            }
            if (accountsTab != null) {
                service.tapNodeSingleTouch(accountsTab.node)
                delay(380)
            }
            val secondMatch = pollElement(service, timeoutMs = 1500, intervalMs = 75) { elements ->
                findBestPersonMatch(elements, target, searchBottom, screenHeight, screenWidth, mode = PersonMatchMode.PUBLIC_ACCOUNT)
            }
            if (secondMatch != null) {
                val clicked = service.clickNode(secondMatch.node)
                if (!clicked) service.tapNodeSingleTouch(secondMatch.node)
                steps.add("Confirmed profile '${secondMatch.text.ifBlank { target }}'")
                onProfile = pollCondition(timeoutMs = 2200, intervalMs = 75) {
                    isOnProfileScreen(service.dumpScreenElements(), screenHeight)
                }
            }
        }
        return onProfile
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
            val alreadyInReels = reelsTab.node.isSelected
            if (!alreadyInReels) {
                service.tapNodeSingleTouch(reelsTab.node)
                steps.add("Opened Reels tab")
                delay(500)
            }
            if (wantsNextScroll) {
                service.scroll("down")
                steps.add("Scrolled to next Reel")
                if (alreadyInReels) {
                    return FastNavResult(true, true, "", steps)
                }
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
        screenWidth: Int,
        screenHeight: Int,
        timeoutMs: Long,
        intervalMs: Long
    ): Pair<AccessibilityNodeInfo, Rect>? {
        val start = System.currentTimeMillis()
        var clickedFallback = false
        while (System.currentTimeMillis() - start < timeoutMs) {
            val els = service.dumpScreenElements()
            val inDump = els.firstOrNull { el ->
                (el.isEditable || el.className.contains("EditText", ignoreCase = true)) &&
                    el.bounds.top < screenHeight * 0.52
            }
            if (inDump != null) return Pair(inDump.node, inDump.bounds)

            val crossWindow = service.findNodeAcrossWindows(resolveClickableParent = false) { node ->
                val cls = node.className?.toString() ?: ""
                if (!node.isEditable && !cls.contains("EditText", ignoreCase = true)) return@findNodeAcrossWindows false
                val r = Rect()
                node.getBoundsInScreen(r)
                r.top in 0 until (screenHeight * 0.52f).toInt() && r.height() > (screenHeight * 0.008f).toInt()
            }
            if (crossWindow != null) {
                val r = Rect()
                crossWindow.getBoundsInScreen(r)
                return Pair(crossWindow, r)
            }

            // Structural guard: if an unexpected back-header subscreen opened without EditText, press Back once; only retry tap if Search trigger still sits intact
            val hasBackSubscreenHeader = els.any { el ->
                el.bounds.top < screenHeight * 0.16 &&
                    (resIdOf(el) == "action_bar_button_back" || labelOf(el) == "back") &&
                    findSearchTriggerOrInput(els, screenWidth, screenHeight) == null
            }
            if (hasBackSubscreenHeader) {
                JarvisLogStore.log("INSTA_SEARCH", "Sub-screen opened without Search EditText; backing out once")
                service.performBack()
                delay(240)
                continue
            }

            if (!clickedFallback && System.currentTimeMillis() - start > 850) {
                clickedFallback = true
                val liveTrigger = findSearchTriggerOrInput(els, screenWidth, screenHeight)
                if (liveTrigger != null && !liveTrigger.isEditable) {
                    service.clickNode(liveTrigger.node)
                }
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
        var searchEl = pollElement(service, timeoutMs = 650, intervalMs = 40) { elements ->
            findSearchTriggerOrInput(elements, screenWidth, screenHeight)
        }
        // If inside scrolled Inbox hiding top Search pill, jump to top via selected Inbox tab or exact upward scroll
        if (searchEl == null && isInMessagesInbox(service, screenHeight)) {
            for (revealTry in 1..4) {
                val curEls = service.dumpScreenElements()
                val inboxTab = if (revealTry == 1) {
                    findInboxButton(curEls, screenHeight)?.takeIf { it.node.isSelected && resIdOf(it).contains("tab") }
                } else null
                if (inboxTab != null) {
                    JarvisLogStore.log("INSTA_SEARCH", "Search bar hidden in scrolled Inbox; tapping Inbox tab to jump to top")
                    service.tapNodeSingleTouch(inboxTab.node)
                    delay(220)
                } else {
                    JarvisLogStore.log("INSTA_SEARCH", "Search bar not visible in Inbox (try $revealTry); scrolling up")
                    service.scrollExact("up")
                    delay(120)
                }
                searchEl = pollElement(service, timeoutMs = 450, intervalMs = 45) { elements ->
                    findSearchTriggerOrInput(elements, screenWidth, screenHeight)
                }
                if (searchEl != null) break
            }
        }
        if (searchEl == null) {
            JarvisLogStore.log("INSTA_SEARCH_MISS", "findSearchTriggerOrInput returned null")
            JarvisLogStore.logScreenTree("SEARCH_MISS", service.dumpScreenElements())
            return null
        }

        JarvisLogStore.log("INSTA_SEARCH_PICK", "Trigger=${JarvisLogStore.describeElement(searchEl)}")
        val editableNode: AccessibilityNodeInfo
        val editableBounds = Rect()

        if (!searchEl.isEditable) {
            service.tapNodeSingleTouch(searchEl.node)
            steps.add("Activated Search bar")
            val opened = pollEditableSearchNode(service, searchEl, screenWidth, screenHeight, timeoutMs = 2100, intervalMs = 55)
            if (opened == null) {
                JarvisLogStore.log("INSTA_SEARCH_MISS", "Editable search EditText did not appear after tapping searchEl")
                JarvisLogStore.logScreenTree("EDITABLE_SEARCH_TIMEOUT", service.dumpScreenElements())
                return null
            }
            editableNode = opened.first
            editableBounds.set(opened.second)
            JarvisLogStore.log(
                "INSTA_SEARCH_EDITABLE",
                "Opened EditText cls=${editableNode.className?.toString()?.substringAfterLast('.') ?: ""} res='${editableNode.viewIdResourceName?.substringAfterLast('/') ?: ""}' bounds=[${editableBounds.left},${editableBounds.top},${editableBounds.right},${editableBounds.bottom}] focused=${editableNode.isFocused}"
            )
        } else {
            editableNode = searchEl.node
            editableBounds.set(searchEl.bounds)
            JarvisLogStore.log("INSTA_SEARCH_EDITABLE", "Directly editable field bounds=[${editableBounds.left},${editableBounds.top},${editableBounds.right},${editableBounds.bottom}]")
        }

        if (!editableNode.isFocused) {
            service.clickNode(editableNode)
            delay(45)
        }

        var typed = service.typeText(editableNode, query, pressEnter = pressEnter)
        delay(55)
        editableNode.refresh()

        val afterTxt = editableNode.text?.toString() ?: ""
        val enteredOk = typed && afterTxt.contains(query.take(5), ignoreCase = true)
        JarvisLogStore.log("INSTA_SEARCH_TYPE", "typed=$typed enteredOk=$enteredOk currentText='${afterTxt.take(40)}'")
        if (!enteredOk) {
            // Ensure IME InputConnection binds on freshly opened overlay, then retry typing once
            val retryPair = pollEditableSearchNode(service, searchEl, screenWidth, screenHeight, timeoutMs = 550, intervalMs = 55)
            val retryNode = retryPair?.first ?: editableNode
            service.tapNodeSingleTouch(retryNode)
            delay(85)
            typed = service.typeText(retryNode, query, pressEnter = pressEnter)
            retryNode.getBoundsInScreen(editableBounds)
            JarvisLogStore.log("INSTA_SEARCH_RETRY", "retry typed=$typed text='${retryNode.text?.toString()?.take(40) ?: ""}'")
        }

        if (!typed) return null

        steps.add("Typed '$query' in search")
        return editableBounds.bottom
    }

    private fun isNavTabResId(res: String): Boolean {
        return res == "feed_tab" || res == "home_tab" || res == "clips_tab" || res == "reels_tab" ||
            res == "direct_tab" || res == "messages_tab" || res == "search_tab" || res == "explore_tab" ||
            res == "profile_tab"
    }

    private fun bottomNavBarTop(elements: List<ScreenElement>, screenHeight: Int): Int {
        val navTop = elements.filter { isNavTabResId(resIdOf(it)) }.minOfOrNull { it.bounds.top }
        return navTop ?: screenHeight
    }

    private fun findInboxButton(elements: List<ScreenElement>, screenHeight: Int): ScreenElement? {
        val navTop = bottomNavBarTop(elements, screenHeight)
        val headerLimit = elements.filter { resIdOf(it).contains("action_bar") }.maxOfOrNull { it.bounds.bottom }
        return elements.filter { el ->
            if (el.isEditable) return@filter false
            val res = resIdOf(el)
            val label = labelOf(el)
            val isBottomTab = res == "direct_tab" || res.contains("messages_tab") ||
                (navTop < screenHeight && el.bounds.top >= navTop)
            val isTopBarIcon = headerLimit != null && el.bounds.top <= headerLimit
            val resMatch = res == "direct_tab" || res.contains("messages_tab") ||
                res.endsWith("_inbox") || res == "direct"
            val labelWords = listOf("direct", "message", "messages", "messaging", "chats")
            val descMatch = label.length <= 24 && (isBottomTab || isTopBarIcon || navTop == screenHeight) &&
                res != "row_inbox_container" &&
                labelWords.any { w -> label == w || label.startsWith("$w ") || label.contains("$w,") || label.contains("unread $w") }
            (resMatch || descMatch) && hasClickableOrAncestor(el.node, maxDepth = 4)
        }.minByOrNull { el ->
            val res = resIdOf(el)
            val label = labelOf(el)
            when {
                res == "direct_tab" || res.contains("messages_tab") -> 0
                res.contains("inbox") -> 1
                label == "message" || label == "messages" || label == "direct" -> 2
                else -> 3
            }
        }
    }

    private fun isInNewMessageComposeScreen(elements: List<ScreenElement>): Boolean {
        val resSummary = elements.joinToString(" | ") { resIdOf(it) }
        // Structural check: Compose has NO bottom nav bar and DOES expose new-thread/recipient IDs or "To:" field
        if (resSummary.contains("direct_tab") || resSummary.contains("feed_tab")) return false
        val summary = elements.joinToString(" | ") { labelOf(it) }
        return resSummary.contains("direct_new_chat") ||
            resSummary.contains("direct_ff_group_chat") ||
            resSummary.contains("recipients_container") ||
            (summary.contains("to:") && summary.contains("new message"))
    }

    private fun isInMessagesInbox(service: JarvisAccessibilityService, screenHeight: Int): Boolean {
        val elements = service.dumpScreenElements()
        if (elements.any { isChatComposerElement(it, screenHeight) }) return false
        if (isInNewMessageComposeScreen(elements)) return false

        val resSummary = elements.joinToString(" | ") { resIdOf(it) }
        val homeTabSelected = findBottomNavTab(elements, screenHeight, listOf("home", "feed"), listOf("feed_tab", "home_tab"))?.node?.isSelected == true
        if (homeTabSelected) return false

        // Pure Accessibility state: Messages/Direct tab selected or Inbox container IDs present
        val hasSelectedDirectTab = elements.any { el ->
            (resIdOf(el) == "direct_tab" || resIdOf(el) == "messages_tab") && el.node.isSelected
        }
        val hasInboxIds = resSummary.contains("direct_inbox") || resSummary.contains("inbox_recycler") ||
            resSummary.contains("direct_search") || resSummary.contains("thread_list") ||
            resSummary.contains("notes_tray") || resSummary.contains("row_inbox") ||
            resSummary.contains("direct_quick_snap") || resSummary.contains("igds_action_bar_title")

        return hasSelectedDirectTab || hasInboxIds || findInboxButton(elements, screenHeight)?.node?.isSelected == true
    }

    private fun findChatComposer(service: JarvisAccessibilityService, screenHeight: Int): ScreenElement? {
        return service.dumpScreenElements().firstOrNull { isChatComposerElement(it, screenHeight) }
    }

    private fun isChatComposerElement(el: ScreenElement, screenHeight: Int): Boolean {
        if (!el.isEditable || el.bounds.bottom < screenHeight * 0.38) return false
        val label = labelOf(el)
        val res = resIdOf(el)
        if (res.contains("search") || res.contains("comment") || label == "search" ||
            label.contains("comment") || label.contains("reply to")) return false
        return res.contains("entry") || res.contains("message") || res.contains("composer") ||
            res.contains("row_thread") || label.contains("message") || el.bounds.top > screenHeight * 0.38
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
            if (el.bounds.top < screenHeight * 0.35 || el.isEditable || el.bounds.height() > screenHeight * 0.14) return@firstOrNull false
            val res = resIdOf(el)
            val t = el.text.trim().lowercase()
            val c = el.contentDescription.trim().lowercase()
            val isSend = res == "row_thread_composer_send_button_container" ||
                res.contains("send_button") || res.contains("send_btn") ||
                res.contains("composer_send") || res.endsWith(":id/send") || res == "send" ||
                t == "send" || c == "send" || c == "send message" || c == "भेजें"
            isSend && hasClickableOrAncestor(el.node, maxDepth = 3)
        }
    }

    private fun findSearchTriggerOrInput(
        elements: List<ScreenElement>,
        screenWidth: Int,
        screenHeight: Int
    ): ScreenElement? {
        val navTop = bottomNavBarTop(elements, screenHeight)
        return elements.filter { el ->
            val res = resIdOf(el)
            val label = labelOf(el)
            if (isNavTabResId(res) || el.bounds.top >= navTop) return@filter false
            if (isChatComposerElement(el, screenHeight)) return@filter false
            if (res == "row_inbox_container" || res.contains("action_bar_title") || res.contains("button_back")) return@filter false
            val matchesRole = el.isEditable || res.contains("search") || label.contains("search")
            matchesRole && (el.isEditable || hasClickableOrAncestor(el.node, maxDepth = 4))
        }.sortedWith(
            compareBy<ScreenElement>(
                { el ->
                    val res = resIdOf(el)
                    when {
                        el.isEditable -> 0
                        res.contains("search_bar") || res.contains("direct_search") || res.contains("action_bar_search") -> 1
                        else -> 2
                    }
                },
                { el -> -el.bounds.width() }
            )
        ).firstOrNull()
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
        val navTop = bottomNavBarTop(elements, screenHeight)
        return elements.firstOrNull { el ->
            val res = resIdOf(el)
            val label = labelOf(el)
            val matchesRes = resKeywords.any { res.contains(it) }
            val matchesLabelInBottomBar = (navTop < screenHeight && el.bounds.top >= navTop) &&
                labelKeywords.any { label.contains(it) }
            (matchesRes || matchesLabelInBottomBar) && hasClickableOrAncestor(el.node, maxDepth = 3)
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
        mode: PersonMatchMode = PersonMatchMode.DM_PERSON,
        debugReasons: MutableList<String>? = null
    ): ScreenElement? {
        val targetLower = target.lowercase().trim()
        val compactTarget = targetLower.replace(Regex("[^a-z0-9]"), "")
        val tokens = targetLower.split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return null

        // Structural section header boundary: connected DM rows always sit BEFORE RecyclerView section dividers ("More accounts" / "Ask Meta AI" / "See All")
        val firstSectionHeaderTop = elements.filter { el ->
            val r = resIdOf(el)
            val l = labelOf(el)
            el.bounds.top > minTopY && (
                r == "header_container" || r == "header_text" || r == "header_action_button" ||
                    r.contains("meta_ai_header") ||
                    l == "more accounts" || l == "ask meta ai" || l == "see all" ||
                    l.startsWith("more accounts,")
                )
        }.minOfOrNull { it.bounds.top }

        val fakeWords = listOf(
            "fanpage", "fan page", "fan_page", "fanclub", "fan club", "fan_club", "fandom",
            "parody", "updates", "army", "edits", "lovers", "unofficial", "backup", "stan",
            "_fc", ".fc", "fc_"
        ).filter { !targetLower.contains(it) }

        var bestEl: ScreenElement? = null
        var bestScore = Int.MIN_VALUE
        var bestTop = Int.MAX_VALUE

        val navTop = bottomNavBarTop(elements, screenHeight)
        for (el in elements) {
            if (el.isEditable || el.bounds.centerY() <= minTopY + 2 || el.bounds.top >= navTop) continue
            if (!hasClickableOrAncestor(el.node, maxDepth = 4)) continue

            val raw = el.text.ifBlank { el.contentDescription }.trim()
            val label = raw.lowercase()
            val res = resIdOf(el)
            if (isNavTabResId(res) || res == "header_container" || res == "header_text" || res.contains("meta_ai")) continue
            if (label.isBlank() || label.startsWith("#")) continue

            // Split structural separators WITHOUT splitting commas so multi-member group subtitles stay detectable
            val rawSegs = label.split("|", "\n", "·", "•").map { it.trim() }.filter { it.isNotEmpty() }
            val isVerified = label.contains("verified") ||
                hasRowBadgeKeyword(el.node, listOf("verified", "blue_check", "verified_badge"))
            val cleanSegs = rawSegs.filter { !it.equals("verified", ignoreCase = true) }
                .map { it.replace(Regex("\\bverified\\b"), "").trim() }
                .filter { it.isNotEmpty() }

            val primarySeg = cleanSegs.firstOrNull() ?: label
            val secondarySegs = cleanSegs.drop(1)
            val handleSeg = secondarySegs.firstOrNull {
                !it.contains(" ") && !it.contains(",") && !Regex("^\\d+[smhdwy]$").matches(it)
            }?.removePrefix("@")
                ?: cleanSegs.firstOrNull { it.startsWith("@") }?.removePrefix("@")
                ?: if (!primarySeg.contains(" ") && !primarySeg.contains(",")) primarySeg.removePrefix("@") else ""

            // Group-chat vs personal-DM protection (ignore relative timestamps like ", 10m" / ", 2h" and status subtitles)
            val hasCommaMemberList = secondarySegs.any { sec ->
                val trimmed = sec.trim(',', ' ')
                if (trimmed.isEmpty() || !trimmed.contains(",")) return@any false
                val looksLikeRelativeTime = Regex("^\\d+\\s*[smhdwy]\\b").containsMatchIn(trimmed)
                val looksLikePreviewStatus = trimmed.contains("follow") || trimmed.contains("mutual") ||
                    trimmed.contains("active") || trimmed.contains("hour") || trimmed.contains("min") ||
                    trimmed.contains("sent ") || trimmed.contains("reacted") || trimmed.contains("replied")
                !looksLikeRelativeTime && !looksLikePreviewStatus
            }
            val looksLikeGroup = hasCommaMemberList || label.contains("members") ||
                label.contains("participants") || res.contains("group") || res.contains("multi_avatar")

            if (mode == PersonMatchMode.DM_PERSON && looksLikeGroup) {
                debugReasons?.add("SKIP(group): '${raw.take(45)}'")
                continue
            }
            if (mode == PersonMatchMode.DM_PERSON && cleanSegs.size >= 2) {
                val primaryOrHandleMatch = tokens.any { primarySeg.contains(it) } ||
                    (handleSeg.isNotBlank() && tokens.any { handleSeg.contains(it) })
                if (!primaryOrHandleMatch) {
                    debugReasons?.add("SKIP(non_primary_subtitle): '${raw.take(45)}'")
                    continue
                }
            }

            // Pure structural account signal check via UI subtree & left-half avatar sibling (zero screen % thresholds)
            val hasSiblingAvatar = elements.any { other ->
                other !== el &&
                    other.bounds.centerY() in el.bounds.top..el.bounds.bottom &&
                    (
                        resIdOf(other).let { it.contains("avatar") || it.contains("profile_pic") } ||
                            (other.bounds.right <= el.bounds.centerX() &&
                                other.bounds.top >= el.bounds.top &&
                                other.bounds.bottom <= el.bounds.bottom &&
                                other.bounds.width() < el.bounds.width() / 3)
                        )
            }
            val hasAccountSignals = cleanSegs.size >= 2 || isVerified || label.contains("@") ||
                label.contains("follower") || hasSiblingAvatar ||
                hasRowBadgeKeyword(el.node, listOf("avatar", "profile_pic", "row_user", "user_row"))
            if (!hasAccountSignals || res.contains("keyword") || res.contains("search_text") || res.contains("query")) {
                debugReasons?.add("SKIP(no_account_signals): '${raw.take(35)}'")
                continue
            }

            // Compute semantic relevance score
            var score = 0
            val cleanPrimaryText = primarySeg.replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val compactPrimary = primarySeg.replace(Regex("[^a-z0-9]"), "")
            val compactHandle = handleSeg.replace(Regex("[^a-z0-9]"), "")

            if (compactHandle.isNotBlank() && compactHandle == compactTarget) {
                score += 180
            }
            if (primarySeg == targetLower || cleanPrimaryText == targetLower ||
                (compactPrimary.isNotBlank() && compactPrimary == compactTarget)
            ) {
                score += 150
            } else if (primarySeg.startsWith(targetLower) || cleanPrimaryText.startsWith(targetLower)) {
                score += 110
            } else if (compactHandle.length >= 4 && compactTarget.length >= 4 && compactHandle.contains(compactTarget)) {
                score += 135
            } else if (compactHandle.startsWith(compactTarget) && compactTarget.length >= 3) {
                score += 90
            } else if (compactPrimary.length >= 4 && compactTarget.length >= 4 && compactPrimary.contains(compactTarget)) {
                score += 105
            } else if (tokens.all { tok -> cleanSegs.any { seg -> Regex("\\b${Regex.escape(tok)}\\b").containsMatchIn(seg) } }) {
                score += 75
            } else if (tokens.all { label.contains(it) }) {
                score += 45
            } else if (tokens.size == 1 && label.contains(tokens[0])) {
                score += 25
            }

            if (score <= 0) continue

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
                val isSuggestedStranger = label.contains("you both follow") ||
                    label.contains("followed by") ||
                    label.contains("follows you") ||
                    (firstSectionHeaderTop != null && el.bounds.top >= firstSectionHeaderTop)
                val isChannelRow = label.contains("channel ·") || label.contains("channel •")
                if (isSuggestedStranger) {
                    score -= 90
                } else if (!isChannelRow) {
                    score += 220
                }
            }

            debugReasons?.add("CAND(score=$score ver=$isVerified top=${el.bounds.top}): '${raw.take(45)}'")
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

    private enum class MediaSenderFilter { FROM_FRIEND, FROM_ME }

    private fun isBackInDmChat(elements: List<ScreenElement>, screenHeight: Int): Boolean {
        return elements.any { el ->
            val r = resIdOf(el)
            isChatComposerElement(el, screenHeight) ||
                r == "row_thread_composer_edittext" ||
                r == "forwarding_shortcut_button" ||
                r == "header_title"
        }
    }

    private fun isInReelOrMediaViewer(elements: List<ScreenElement>): Boolean {
        return elements.any { el ->
            val r = resIdOf(el)
            r.contains("clips_media") || r.contains("clips_video") || r.contains("reply_bar_edittext")
        }
    }

    private suspend fun openMediaCardViewer(
        service: JarvisAccessibilityService,
        mediaCard: ScreenElement,
        elements: List<ScreenElement>,
        headerBottom: Int,
        composerTop: Int,
        screenHeight: Int
    ): Boolean {
        val safeTop = maxOf(mediaCard.bounds.top, headerBottom + (screenHeight * 0.005f).toInt())
        val safeBottom = minOf(mediaCard.bounds.bottom, composerTop - (screenHeight * 0.008f).toInt())
        if (safeBottom > safeTop + (screenHeight * 0.01f).toInt()) {
            service.tapCoordinatesAwait(mediaCard.bounds.centerX().toFloat(), (safeTop + safeBottom) / 2f, 55L)
        } else {
            service.tapNodeSingleTouch(mediaCard.node)
        }
        if (pollCondition(timeoutMs = 2000, intervalMs = 75) {
                service.dumpScreenElements().none { isChatComposerElement(it, screenHeight) }
            }
        ) return true

        val captionTarget = elements.firstOrNull { other ->
            other !== mediaCard &&
                other.bounds.centerY() in (mediaCard.bounds.top - (screenHeight * 0.015f).toInt())..(composerTop - (screenHeight * 0.006f).toInt()) &&
                (resIdOf(other).contains("caption_title") || resIdOf(other) == "title_text" || resIdOf(other) == "title")
        }
        if (captionTarget != null) {
            service.tapNodeSingleTouch(captionTarget.node)
            if (pollCondition(timeoutMs = 1400, intervalMs = 75) {
                    service.dumpScreenElements().none { isChatComposerElement(it, screenHeight) }
                }
            ) return true
        }

        service.clickNode(mediaCard.node)
        return pollCondition(timeoutMs = 1100, intervalMs = 75) {
            service.dumpScreenElements().none { isChatComposerElement(it, screenHeight) }
        }
    }

    private enum class ReelWaitOutcome { COMPLETED, USER_EXITED_APP }

    private suspend fun waitForReelPlaybackStep(
        service: JarvisAccessibilityService,
        screenHeight: Int
    ): ReelWaitOutcome {
        val start = System.currentTimeMillis()
        var maxSeekRatio = 0f
        var sawSeekProgress = false
        var seekSamples = 0
        var maxTimerSec = -1
        var prevTimerSec = -1
        var loggedPlayerTree = false
        val timerRegex = Regex("^-?(\\d{1,2}):(\\d{2})$")
        val dualTimeRegex = Regex("(\\d{1,2}):(\\d{2})\\s*(?:/|of)\\s*(\\d{1,2}):(\\d{2})", RegexOption.IGNORE_CASE)

        delay(800)

        while (System.currentTimeMillis() - start < 45_000L) {
            val elapsed = System.currentTimeMillis() - start
            val activePkg = service.rootInActiveWindow?.packageName?.toString()?.lowercase() ?: ""
            if (activePkg.isNotBlank() && !activePkg.contains("instagram")) {
                return ReelWaitOutcome.USER_EXITED_APP
            }

            val els = service.dumpScreenElements()
            if (elapsed > 2400L && els.any { isChatComposerElement(it, screenHeight) }) {
                return ReelWaitOutcome.COMPLETED
            }

            // Walk live Accessibility tree directly so non-clickable SeekBar / ProgressBar / timer nodes inside fullscreen player are never hidden
            var ratioNow: Float? = null
            var curSecNow: Int? = null
            var totalSecNow: Int? = null
            var hasReplay = false
            val captureDump = if (!loggedPlayerTree && elapsed >= 1800L) mutableListOf<String>() else null

            fun walkPlayer(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 16 || !node.isVisibleToUser) return
                val t = node.text?.toString()?.trim() ?: ""
                val c = node.contentDescription?.toString()?.trim() ?: ""
                val r = node.rangeInfo
                if (captureDump != null && captureDump.size < 35 && (r != null || t.isNotBlank() || c.isNotBlank())) {
                    val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
                    val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
                    captureDump.add("$cls#$id(t='${t.take(20)}' cd='${c.take(20)}' rng=${r?.let { "${it.min}..${it.max}@${it.current}" }})")
                }
                if (t.equals("watch again", true) || c.equals("watch again", true) ||
                    t.equals("replay", true) || c.equals("replay", true)
                ) {
                    hasReplay = true
                }
                if (ratioNow == null && r != null && r.max > r.min) {
                    ratioNow = ((r.current - r.min) / (r.max - r.min)).coerceIn(0f, 1f)
                }
                if (curSecNow == null) {
                    for (s in listOf(t, c)) {
                        if (s.isBlank()) continue
                        dualTimeRegex.find(s)?.let { m ->
                            curSecNow = (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
                            totalSecNow = (m.groupValues[3].toIntOrNull() ?: 0) * 60 + (m.groupValues[4].toIntOrNull() ?: 0)
                        } ?: timerRegex.matchEntire(s)?.let { m ->
                            curSecNow = (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
                        }
                        if (curSecNow != null) break
                    }
                }
                for (i in 0 until node.childCount) walkPlayer(node.getChild(i), depth + 1)
            }

            walkPlayer(service.rootInActiveWindow, 0)
            if (captureDump != null) {
                loggedPlayerTree = true
                if (ratioNow == null && curSecNow == null) {
                    JarvisLogStore.log("DM_MEDIA_PLAYER_TREE", "nodes=$captureDump")
                }
            }

            if (hasReplay && elapsed > 1800L) return ReelWaitOutcome.COMPLETED

            ratioNow?.let { ratio ->
                if (ratio in 0.03f..0.88f) {
                    seekSamples++
                    if (seekSamples >= 2) sawSeekProgress = true
                }
                if (sawSeekProgress) {
                    if ((maxSeekRatio >= 0.72f && ratio < 0.20f) || (ratio >= 0.93f && elapsed >= 2800L)) {
                        JarvisLogStore.log("DM_MEDIA_DONE_BY", "reason=seek maxR=$maxSeekRatio curR=$ratio elapsed=$elapsed")
                        return ReelWaitOutcome.COMPLETED
                    }
                }
                if (ratio > maxSeekRatio) maxSeekRatio = ratio
            }

            curSecNow?.let { curSec ->
                val tot = totalSecNow
                if (tot != null && tot >= 3 && elapsed >= 2000L && curSec >= tot - 1) {
                    JarvisLogStore.log("DM_MEDIA_DONE_BY", "reason=dualTimer cur=$curSec total=$tot elapsed=$elapsed")
                    return ReelWaitOutcome.COMPLETED
                }
                if (curSec > maxTimerSec) maxTimerSec = curSec
                if (elapsed >= 2800L && prevTimerSec >= 0 && maxTimerSec >= 4) {
                    val wrapped = (prevTimerSec >= 4 && curSec <= 1) || (prevTimerSec - curSec >= 4) || (curSec - prevTimerSec >= 4)
                    if (wrapped) {
                        JarvisLogStore.log("DM_MEDIA_DONE_BY", "reason=timer prev=$prevTimerSec cur=$curSec max=$maxTimerSec elapsed=$elapsed")
                        return ReelWaitOutcome.COMPLETED
                    }
                }
                prevTimerSec = curSec
            }

            if (!sawSeekProgress && maxTimerSec < 0 && elapsed >= 22_000L) {
                return ReelWaitOutcome.COMPLETED
            }

            delay(200)
        }
        return ReelWaitOutcome.COMPLETED
    }

    private fun buildMediaCardSignature(
        el: ScreenElement,
        elements: List<ScreenElement>,
        occurrenceIdx: Int,
        headerBottom: Int,
        composerTop: Int,
        scrollEpoch: Int
    ): String {
        val raw = labelOf(el).replace(Regex("\\s+"), " ").take(60)
        val res = resIdOf(el)
        if (raw.isNotBlank()) return "lbl:$raw|${el.bounds.width()}|$res"

        val clipTop = maxOf(headerBottom + 1, el.bounds.top)
        val clipBottom = minOf(composerTop - 1, el.bounds.bottom)
        val timeRe = Regex("^\\d{1,2}:\\d{2}(\\s?[ap]m)?$")
        val attrText = elements.filter { other ->
            other !== el &&
                other.bounds.centerY() in clipTop..clipBottom &&
                other.bounds.centerX() in el.bounds.left..el.bounds.right &&
                (other.text.isNotBlank() || other.contentDescription.isNotBlank()) &&
                resIdOf(other).let {
                    it == "title_text" || it == "caption_title" || it == "subtitle_text" ||
                        (other.bounds.height() < el.bounds.height() / 3 &&
                            it != "sender_avatar" &&
                            it != "profile_attribution_picture")
                }
        }.map {
            it.text.ifBlank { it.contentDescription }.replace(Regex("\\s+"), " ").trim()
        }.filter { txt ->
            val lower = txt.lowercase()
            txt.isNotBlank() && !timeRe.matches(lower) &&
                lower != "seen" && lower != "sent" && lower != "delivered" &&
                lower != "reel" && lower != "clip"
        }.distinct().joinToString(",").take(60)

        return if (attrText.isNotBlank()) {
            "attr:$attrText|${el.bounds.width()}|$res"
        } else {
            "unlabeled_${scrollEpoch}_${occurrenceIdx}_${el.bounds.width()}|$res"
        }
    }

    private fun viewportMessagesSignature(
        elements: List<ScreenElement>,
        headerBottom: Int,
        composerTop: Int
    ): String {
        return elements.filter { it.bounds.centerY() in headerBottom..composerTop }
            .joinToString("|") { labelOf(it).take(25) }
    }

    private fun findInnerVisualMediaBounds(
        node: AccessibilityNodeInfo?,
        minMediaW: Int,
        minMediaH: Int,
        depth: Int = 0
    ): Rect? {
        if (node == null || depth > 7) return null
        val cls = node.className?.toString()?.substringAfterLast('.')?.lowercase() ?: ""
        val res = node.viewIdResourceName?.substringAfterLast('/')?.lowercase() ?: ""
        val isVisualNode = cls.contains("imageview") || cls.contains("videoview") ||
            cls.contains("textureview") || cls.contains("surfaceview") || cls.contains("draweeview") ||
            res.contains("image") || res.contains("video") || res.contains("reel") || res.contains("clip") ||
            res.contains("xma") || res.contains("thumbnail") || res.contains("media") || res.contains("cover")
        var bestRect: Rect? = null
        if (depth > 0 && isVisualNode) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() >= minMediaW && r.height() >= minMediaH) {
                bestRect = r
            }
        }
        for (i in 0 until node.childCount) {
            val childRect = findInnerVisualMediaBounds(node.getChild(i), minMediaW, minMediaH, depth + 1)
            if (childRect != null) {
                val bestArea = bestRect?.let { it.width().toLong() * it.height() } ?: 0L
                val childArea = childRect.width().toLong() * childRect.height()
                if (childArea > bestArea) bestRect = childRect
            }
        }
        return bestRect
    }

    private fun isCardAlignedOrLabeledFromMe(
        el: ScreenElement,
        visualRect: Rect,
        allElements: List<ScreenElement>,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean? {
        val label = labelOf(el)
        if (label.contains("you sent") || label.contains("you shared") || label.startsWith("you:")) return true

        // 1. Structural DM card tree signals: Friend cards carry sender_avatar on LEFT + forwarding button on RIGHT; user cards put forwarding button on LEFT
        val hasLeftSenderAvatar = allElements.any { other ->
            resIdOf(other) == "sender_avatar" &&
                other.bounds.centerY() in visualRect.top..(visualRect.bottom + other.bounds.height()) &&
                other.bounds.centerX() < visualRect.centerX()
        }
        if (hasLeftSenderAvatar) return false

        val forwardSibling = allElements.firstOrNull { other ->
            resIdOf(other) == "forwarding_shortcut_button" &&
                other.bounds.centerY() in visualRect.top..visualRect.bottom
        }
        if (forwardSibling != null) {
            return forwardSibling.bounds.centerX() < visualRect.centerX()
        }

        // 2. Inspect small sender caption immediately above visual card
        val tagBandTop = visualRect.top - (visualRect.height() / 5).coerceAtLeast(1)
        val aboveTag = allElements.any { other ->
            other.bounds.centerY() in tagBandTop..visualRect.bottom &&
                other.bounds.height() < visualRect.height() / 3 &&
                (labelOf(other).contains("you sent") || labelOf(other).contains("you shared"))
        }
        if (aboveTag) return true

        // 3. Relative horizontal midpoint derived from live chat thread elements on screen
        val chatItems = allElements.filter { it.bounds.top >= visualRect.top / 4 && it.bounds.width() > 0 }
        val threadLeft = chatItems.minOfOrNull { it.bounds.left } ?: 0
        val threadRight = chatItems.maxOfOrNull { it.bounds.right } ?: screenWidth
        val midX = (threadLeft + threadRight) / 2f
        val cx = visualRect.centerX()
        val margin = (threadRight - threadLeft) * 0.04f
        return when {
            cx > midX + margin -> true
            cx < midX - margin -> false
            else -> null
        }
    }

    private fun findSharedMediaCardsInChat(
        elements: List<ScreenElement>,
        screenWidth: Int,
        screenHeight: Int,
        headerBottom: Int,
        composerTop: Int,
        senderFilter: MediaSenderFilter = MediaSenderFilter.FROM_FRIEND,
        debugReasons: MutableList<String>? = null
    ): List<ScreenElement> {
        val viewportHeight = (composerTop - headerBottom).coerceAtLeast(1)
        // Measure live avatar/icon size directly from current screen instead of fixed size guesses
        val liveIconRefH = elements.filter {
            val r = resIdOf(it)
            r == "sender_avatar" || r == "profile_attribution_picture" || r == "header_left_button"
        }.maxOfOrNull { it.bounds.height() } ?: (viewportHeight / 16)
        val minMediaSize = (liveIconRefH * 1.4f).toInt()

        val hardExcluded = listOf("voice", "video call", "audio call", "audio message", "view profile")
        val nonVisualExcluded = listOf("mic", "record", "camera", "gallery", "sticker", "message...", "seen", "delivered")

        data class MediaCandidate(
            val el: ScreenElement,
            val isReelOrVideo: Boolean,
            val score: Int
        )

        val spanW = (elements.maxOfOrNull { it.bounds.right } ?: screenWidth) - (elements.minOfOrNull { it.bounds.left } ?: 0)
        val candidates = mutableListOf<MediaCandidate>()
        for (el in elements) {
            if (el.isEditable) continue
            val cy = el.bounds.centerY()
            if (cy <= headerBottom || cy >= composerTop) continue
            val cls = el.className.lowercase()
            if (cls.contains("recyclerview") || cls.contains("listview") || cls.contains("scrollview")) continue
            if (el.bounds.height() >= viewportHeight && el.bounds.width() >= spanW) continue

            val label = labelOf(el)
            val res = resIdOf(el)
            // Never treat inline avatars/attributions/shortcut icons or standalone attribution labels themselves as playable Reel cards
            if (res == "sender_avatar" || res == "profile_attribution_picture" ||
                res == "forwarding_shortcut_button" || res == "message_riff_cutout_shortcut_button" ||
                res == "title_text" || res == "caption_title" || res == "subtitle_text") continue

            val innerVisualRect = findInnerVisualMediaBounds(el.node, minMediaSize, minMediaSize)
            val isVisualCls = cls.contains("imageview") || cls.contains("textureview") ||
                cls.contains("videoview") || cls.contains("surfaceview") || cls.contains("draweeview")
            val hasMediaCompanion = elements.any { other ->
                other !== el &&
                    other.bounds.centerY() in el.bounds.top..el.bounds.bottom &&
                    resIdOf(other).let {
                        it == "forwarding_shortcut_button" || it == "profile_attribution_picture" ||
                            it == "title_text" || it == "caption_title"
                    }
            }
            val isMediaCardHeight = el.bounds.height() > minMediaSize && el.bounds.width() > minMediaSize
            if (!isMediaCardHeight && innerVisualRect == null) continue

            if (!hasClickableOrAncestor(el.node, maxDepth = 5) && !isVisualCls && innerVisualRect == null && !hasMediaCompanion) {
                debugReasons?.add("SKIP(not_click ${el.bounds.width()}x${el.bounds.height()}): '${label.take(25)}'")
                continue
            }

            val isReelKeyword = label.contains("reel") || label.contains("clip") || res.contains("reel") || res.contains("clip")
            val isVideoOrXma = isReelKeyword || label.contains("video") || label.contains("watch") ||
                label.contains("play") || label.contains("audio") || res.contains("video") || res.contains("xma")
            val isOtherMedia = isVideoOrXma || isVisualCls || hasMediaCompanion || label.contains("post") || label.contains("photo") ||
                label.contains("shared") || res.contains("media") || res.contains("attachment") ||
                res.contains("thumbnail") || res.contains("portrait")

            val isHardExcl = hardExcluded.any { label.contains(it) || res.contains(it) }
            val isNonVisualExcl = innerVisualRect == null && !isReelKeyword && !isVisualCls && !hasMediaCompanion &&
                nonVisualExcluded.any { label.contains(it) || res.contains(it) }
            if (isHardExcl || isNonVisualExcl) {
                debugReasons?.add("SKIP(excl): '${label.take(30)}'")
                continue
            }

            if (!isOtherMedia && (el.node.text?.isNotBlank() == true && innerVisualRect == null)) {
                debugReasons?.add("SKIP(not_media ${el.bounds.width()}x${el.bounds.height()}): '${label.take(28)}'")
                continue
            }

            val effectiveVisualRect = innerVisualRect ?: el.bounds
            val fromMeFlag = isCardAlignedOrLabeledFromMe(el, effectiveVisualRect, elements, screenWidth, screenHeight)

            val matchesRequestedSender = when (senderFilter) {
                MediaSenderFilter.FROM_FRIEND -> fromMeFlag != true
                MediaSenderFilter.FROM_ME -> fromMeFlag == true
            }
            if (!matchesRequestedSender) {
                debugReasons?.add("SKIP(sender=$fromMeFlag need=$senderFilter): '${label.take(28)}'")
                continue
            }

            val sideBonus = when {
                senderFilter == MediaSenderFilter.FROM_FRIEND && fromMeFlag == false -> 40
                senderFilter == MediaSenderFilter.FROM_ME && fromMeFlag == true -> 40
                else -> 0
            }
            val score = sideBonus + when {
                isReelKeyword -> 100
                isVideoOrXma -> 70
                isOtherMedia -> 45
                else -> 30
            }
            debugReasons?.add("CAND(sc=$score me=$fromMeFlag ${el.bounds.width()}x${el.bounds.height()} vis=${innerVisualRect != null}): '${label.take(28)}'")
            candidates.add(MediaCandidate(el, isVideoOrXma || isVisualCls || innerVisualRect != null, score))
        }

        if (candidates.isEmpty()) return emptyList()
        // Prefer reel/video cards if available; return bottom-to-top (latest first) WITHOUT cross-sender fallback
        val pool = candidates.filter { it.isReelOrVideo }.ifEmpty { candidates }

        val clustered = mutableListOf<ScreenElement>()
        val sortedDesc = pool.sortedWith(compareByDescending<MediaCandidate> { it.el.bounds.bottom }.thenByDescending { it.score })
        for (cand in sortedDesc) {
            val overlapsExisting = clustered.any { existing ->
                val ovTop = maxOf(existing.bounds.top, cand.el.bounds.top)
                val ovBot = minOf(existing.bounds.bottom, cand.el.bounds.bottom)
                val minH = minOf(existing.bounds.height(), cand.el.bounds.height()).coerceAtLeast(1)
                ovBot > ovTop && (ovBot - ovTop) > minH / 2
            }
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
            "active now", "just now", "now", "message...", "type a message", "reply", "unsend", "forward",
            "react", "double tap to like", "double tap to react", "tap to react", "hold to react",
            "view profile", "follow", "following", "voice", "camera", "gallery", "like", "audio call", "video call"
        )
        val timeRegex = Regex("^(\\d{1,2}[mhd](\\s?ago)?|\\d{1,2}:\\d{2}(\\s?[ap]m)?|(mon|tue|wed|thu|fri|sat|sun)[,\\s]+\\d{1,2}:\\d{2}.*)$")
        val targetLower = target.lowercase().trim()

        data class MsgItem(val el: ScreenElement, val text: String, val isFromFriend: Boolean)

        val items = mutableListOf<MsgItem>()
        for (el in elements) {
            if (el.isEditable) continue
            val cy = el.bounds.centerY()
            if (cy <= headerBottom || cy >= composerTop) continue
            if (el.bounds.height() < (screenHeight * 0.007f).toInt() || el.bounds.width() < (screenWidth * 0.022f).toInt()) continue

            val raw = el.text.ifBlank { el.contentDescription }.trim()
            val lower = raw.lowercase()
            if (raw.isBlank() || lower == targetLower) continue
            if (lower in ignoreExact || timeRegex.matches(lower)) continue
            if (lower.startsWith("seen") || lower.startsWith("sent ") || lower.startsWith("delivered") ||
                lower.startsWith("active ") || lower.contains("typing") || lower.contains("you both follow") ||
                lower.contains("follow each other") || lower.contains("following each other") ||
                lower.contains("you're both") || lower == "view profile" ||
                Regex("\\b\\d+\\s+followers?\\b").containsMatchIn(lower)
            ) continue

            // Skip tiny emoji reaction corner pills while preserving real short replies
            if (raw.none { it.isLetterOrDigit() } && el.bounds.height() < (screenHeight * 0.045f).toInt() &&
                el.bounds.width() < (screenWidth * 0.12f).toInt()) continue

            val cleaned = cleanChatMessageLabel(raw)
            if (cleaned.isBlank()) continue

            val isFromMe = lower.startsWith("you sent") || lower.startsWith("you shared") ||
                lower.startsWith("you:") || lower.startsWith("you replied") ||
                (el.bounds.width() < (screenWidth * 0.85f).toInt() &&
                    el.bounds.right >= (screenWidth * 0.86f).toInt() &&
                    el.bounds.left > (screenWidth * 0.26f).toInt())
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
        screenWidth: Int = Int.MAX_VALUE,
        screenHeight: Int = Int.MAX_VALUE,
        aliases: List<String> = emptyList()
    ): ScreenElement? {
        val marginX = if (screenWidth == Int.MAX_VALUE) 0 else (screenWidth * 0.018f).toInt()
        val maxCenterX = if (screenWidth == Int.MAX_VALUE) Int.MAX_VALUE else screenWidth - marginX

        fun matchStoryTokens(tokens: List<String>): ScreenElement? {
            if (tokens.isEmpty()) return null
            val strict = elements.firstOrNull { el ->
                if (el.bounds.top >= screenHeight * 0.44 || el.bounds.centerX() !in marginX..maxCenterX) return@firstOrNull false
                val label = labelOf(el)
                if (!label.contains("story")) return@firstOrNull false
                if (label.contains("your story") && !tokens.contains("your") && !tokens.contains("my")) return@firstOrNull false
                tokens.all { label.contains(it) } && hasClickableOrAncestor(el.node, maxDepth = 4)
            }
            if (strict != null) return strict

            if (tokens.size > 1) {
                val joined = tokens.joinToString("")
                val primary = tokens[0]
                return elements.firstOrNull { el ->
                    if (el.bounds.top >= screenHeight * 0.44 || el.bounds.centerX() !in marginX..maxCenterX) return@firstOrNull false
                    val label = labelOf(el)
                    if (!label.contains("story")) return@firstOrNull false
                    if (label.contains("your story") && !tokens.contains("your") && !tokens.contains("my")) return@firstOrNull false
                    val matches = label.contains(joined) || (primary.length >= 3 && label.contains(primary))
                    matches && hasClickableOrAncestor(el.node, maxDepth = 4)
                }
            }
            return null
        }

        val targetTokens = target.lowercase().trim().split(Regex("\\s+")).filter { it.length >= 2 }
        matchStoryTokens(targetTokens)?.let { return it }

        for (alias in aliases) {
            val cleanAlias = alias.lowercase().trim().removePrefix("@")
            if (cleanAlias.length >= 3) {
                matchStoryTokens(listOf(cleanAlias))?.let { return it }
            }
        }
        return null
    }

    private fun isHomeStoryRingElement(el: ScreenElement, screenHeight: Int): Boolean {
        if (el.bounds.top >= screenHeight * 0.44) return false
        val res = resIdOf(el)
        if (res == "header_avatar" || res.contains("row_inbox")) return false
        val label = labelOf(el)
        if (label.contains("open story of")) return false
        return label.contains("'s story") || label.contains("your story") || res == "outer_container"
    }

    private fun storyTraySignature(elements: List<ScreenElement>, screenHeight: Int): String {
        return elements.filter { isHomeStoryRingElement(it, screenHeight) }
            .sortedBy { it.bounds.left }
            .joinToString("|") { labelOf(it) }
    }

    private fun findStoryTrayRect(elements: List<ScreenElement>, screenWidth: Int, screenHeight: Int): Rect? {
        // Strictly ensure we are NOT inside DM Chat, Inbox, Explore, Reels, or Profile when scanning Home Story tray
        if (elements.any { isChatComposerElement(it, screenHeight) }) return null
        val directSelected = findBottomNavTab(elements, screenHeight, listOf("message", "direct"), listOf("direct_tab", "messages_tab"))?.node?.isSelected == true
        val searchSelected = findBottomNavTab(elements, screenHeight, listOf("search"), listOf("search_tab", "explore_tab"))?.node?.isSelected == true
        val reelsSelected = findBottomNavTab(elements, screenHeight, listOf("reels"), listOf("clips_tab", "reels_tab"))?.node?.isSelected == true
        if (directSelected || searchSelected || reelsSelected) return null
        if (elements.any { resIdOf(it) == "igds_action_bar_title" }) return null

        val trayContainer = elements.firstOrNull {
            it.contentDescription.contains("reels tray", ignoreCase = true)
        }
        val stories = elements.filter { isHomeStoryRingElement(it, screenHeight) }
        val singleRingW = stories.minOfOrNull { it.bounds.width() } ?: 1
        if (trayContainer != null && trayContainer.bounds.width() > singleRingW) {
            return Rect(trayContainer.bounds)
        }
        if (stories.size < 2) return null
        // Structural geometry check relative to ring size: tray rings spread wider horizontally than one ring
        val horizontalSpan = stories.maxOf { it.bounds.centerX() } - stories.minOf { it.bounds.centerX() }
        if (horizontalSpan < singleRingW * 1.2f) return null
        val minLeft = stories.minOf { it.bounds.left }.coerceAtLeast(0)
        val minTop = stories.minOf { it.bounds.top }.coerceAtLeast(0)
        val maxBottom = stories.maxOf { it.bounds.bottom }
        return Rect(minLeft, minTop, screenWidth, maxBottom)
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

    private fun learnAliasesFromTree(service: JarvisAccessibilityService, elements: List<ScreenElement>) {
        val pairs = mutableListOf<Pair<String, String>>()
        val handleRe = Regex("^[a-z0-9._]{3,32}$")
        val timeRe = Regex("^\\d+[smhdwy]$")

        fun validHandle(h: String): Boolean {
            val clean = h.lowercase().trim().removePrefix("@")
            return handleRe.matches(clean) && !timeRe.matches(clean) &&
                clean != "messages" && clean != "requests" && clean != "active" && clean != "posts"
        }

        val chatTitle = elements.firstOrNull { resIdOf(it) == "header_title" }?.text?.trim() ?: ""
        val chatSub = elements.firstOrNull { resIdOf(it) == "header_subtitle" }?.text?.trim() ?: ""
        if (chatTitle.isNotBlank() && validHandle(chatSub)) pairs.add(chatTitle to chatSub)

        val headerAv = elements.firstOrNull { resIdOf(it) == "header_avatar" }?.let { labelOf(it) } ?: ""
        if (chatTitle.isNotBlank() && headerAv.contains("open story of ")) {
            val h = headerAv.substringAfter("open story of ").trim().substringBefore(" ")
            if (validHandle(h)) pairs.add(chatTitle to h)
        }

        val profName = elements.firstOrNull { resIdOf(it).contains("profile_header_full_name") }?.text?.trim() ?: ""
        val profUser = elements.firstOrNull { resIdOf(it).contains("action_bar_username") }?.text?.trim() ?: ""
        if (profName.isNotBlank() && validHandle(profUser)) pairs.add(profName to profUser)

        for (el in elements) {
            val lbl = labelOf(el)
            if (lbl.contains("open story of ")) {
                val h = lbl.substringAfter("open story of ").trim().substringBefore(" ")
                if (validHandle(h)) {
                    val row = elements.firstOrNull { other ->
                        other !== el && !labelOf(other).contains("open story of ") &&
                            other.bounds.top <= el.bounds.bottom && other.bounds.bottom >= el.bounds.top &&
                            (other.text.isNotBlank() || other.contentDescription.isNotBlank())
                    }
                    val display = row?.let { it.text.ifBlank { it.contentDescription } }
                        ?.substringBefore("|")?.substringBefore(",")?.trim() ?: ""
                    if (display.isNotBlank()) pairs.add(display to h)
                }
            }
            val raw = el.text.ifBlank { el.contentDescription }.trim()
            val low = raw.lowercase()
            if (raw.contains("|") && !low.contains("you both follow") && !low.contains("followed by") && !low.contains("channel ·")) {
                val segs = raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (segs.size == 2 && validHandle(segs[1])) pairs.add(segs[0] to segs[1])
            }
        }

        if (pairs.isEmpty()) return
        val prefs = service.getSharedPreferences(ALIAS_PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for ((displayName, handleRaw) in pairs) {
            val handle = handleRaw.lowercase().trim().removePrefix("@")
            val normName = displayName.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
            if (normName.length < 3 || normName == handle) continue
            val existing = prefs.getString(normName, "")?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
            existing.remove(handle)
            existing.add(0, handle)
            editor.putString(normName, existing.take(4).joinToString(","))
        }
        editor.apply()
    }

    private fun lookupAliases(service: JarvisAccessibilityService, target: String): List<String> {
        val normTarget = target.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        val tokens = normTarget.split(" ").filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()
        val prefs = service.getSharedPreferences(ALIAS_PREFS, Context.MODE_PRIVATE)
        val exact = mutableListOf<String>()
        val partial = mutableListOf<String>()
        for ((savedName, rawHandles) in prefs.all) {
            val handles = (rawHandles as? String)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
            if (handles.isEmpty()) continue
            if (savedName == normTarget) exact.addAll(handles)
            else if (tokens.all { tok -> Regex("\\b${Regex.escape(tok)}\\b").containsMatchIn(savedName) }) partial.addAll(handles)
        }
        return (exact + partial).distinct()
    }

    private fun labelOf(el: ScreenElement): String =
        "${el.text} ${el.contentDescription}".trim().lowercase()

    private fun resIdOf(el: ScreenElement): String =
        el.node.viewIdResourceName?.substringAfterLast('/')?.lowercase() ?: ""
}
