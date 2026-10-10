"""
Google Gemini Brain using Google GenAI SDK.
Loads credentials automatically from config/secrets.env.
Primary model: gemini-3.1-flash-lite.
"""
import os
import sys
from pathlib import Path
from brain.tool_registry import JARVIS_TOOL_DECLARATIONS

# Load environment variables from config/secrets.env
try:
    from dotenv import load_dotenv
    env_path = Path(__file__).resolve().parent.parent / "config" / "secrets.env"
    if env_path.exists():
        load_dotenv(dotenv_path=env_path)
except Exception:
    pass

class UniversalGeminiBrain:
    def __init__(self, api_key: str = None, model_name: str = "gemini-3.1-flash-lite"):
        self.api_key = api_key or os.getenv("GEMINI_API_KEY")
        self.model_name = model_name
        self.is_connected = False
        self.client = None
        self._init_client()

    def _init_client(self):
        if not self.api_key or self.api_key.startswith("your_"):
            print("[Gemini Brain] No valid GEMINI_API_KEY detected. Running in Simulation Mode.")
            self.is_connected = False
            return

        try:
            from google import genai
            self.client = genai.Client(api_key=self.api_key)
            self.is_connected = True
            print(f"[Gemini Brain] Connected to Google Gemini SDK (Active Model: {self.model_name})")
        except Exception as e:
            print(f"[Gemini Brain Warning] Failed to initialize Google GenAI SDK: {e}")
            self.is_connected = False

    def process_natural_input(self, user_speech_text: str, context: dict = None) -> dict:
        """
        Processes any input (Hindi, Hinglish, English, Slang).
        Returns a dict: {'speech_text': str, 'tool_calls': list, 'model_used': str}
        """
        context = context or {}

        if not self.is_connected:
            return self._emulate_intent(user_speech_text, context)

        from google.genai import types

        system_instruction = """
        You are J.A.R.V.I.S., the legendary British butler AI companion to 'Sir'.
        You have full autonomous authority over Sir's Windows laptop, browser, and personal schedule.
        You understand Sir whether he speaks in English, Hindi, Hinglish, or casual slang.

        Core Capabilities:
        - For web searches, YouTube video search/playback, opening websites, or browser tab controls (new_tab, close_tab, next_tab, prev_tab, refresh, scroll_down, scroll_up):
          Call `browser_and_web_action(action="open_url"|"search_google"|"search_youtube"|"play_youtube"|"new_tab"|"close_tab"|"next_tab"|"prev_tab"|"reopen_tab"|"refresh"|"scroll_down"|"scroll_up", query="...")`.
        - For desktop windows, volume adjustments, and media controls:
          Call `window_and_system_action(action="maximize"|"minimize"|"restore"|"close_window"|"show_desktop"|"switch_window"|"media_play_pause"|"media_next"|"media_prev"|"media_stop"|"volume_up"|"volume_down", value=...)`.
        - For calling or messaging someone on ANY application (Teams, WhatsApp, Telegram, Discord, Slack, etc.):
          Call `universal_communicate(app_name="...", action="call"|"message", contact_name="...", message="...", call_type="audio"|"video")`.
        - For general OS/computer tasks (launching apps, clicking buttons, typing text):
          Call `universal_computer_action(action="open_app_or_url"|"click_button"|"type_text"|"press_keys", target="...", text="...")`.
        - For hardware, mute/unmute, lock screen, battery check: Call `control_hardware_and_audio`.
        - For sleep, rest, DND: Call `activate_rest_or_dnd_mode`.
        - For wake up or resuming normal mode: Call `resume_or_wake_up`.
        - For reviewing quarantined disturbances/missed alerts: Call `review_quarantined_disturbances`.
        - For proactive time-based reminders: Call `schedule_proactive_reminder`.
        - For saving/updating personal facts, family, friends, relationships: Call `update_personal_knowledge`.
          (Crucial rule: If Sir mentions a family member, VIP, or important contact e.g. 'mere family member hai', 'important hai', 'VIP hai', pass `importance=3` or `2`).
        - For asking who someone is or inquiring about a role/relationship: Call `query_personal_knowledge`.
        - For viewing all saved relationships/contacts: Call `list_personal_knowledge`.

        CRITICAL SPOKEN LANGUAGE RULE:
        Always speak to Sir in CASUAL, NATURAL HINGLISH (everyday conversational Hindi written in Roman script mixed naturally with English words, exactly how modern urban friends and colleagues speak in India).
        - NEVER reply in pure English.
        - NEVER use difficult, formal or bookish Shuddh Hindi words (strictly avoid words like 'shubh prabhat', 'vishram', 'kripya', 'sandesh', 'tathya').
        - Speak smoothly, respectfully, and casually (e.g., 'Sir aapke rest ke time par Teams par Dhruv ka call aaya tha, kya main open karke dikhaun?', 'Sir aap 2 ghante se lagatar lage hue ho, thoda paani pee lijiye ya 5 minute ka rest le lo', 'Sir maine YouTube par gaana chala diya hai').

        Respond verbally with utmost courtesy, elegance, and brevity in natural Hinglish.
        """

        tools = [types.Tool(function_declarations=JARVIS_TOOL_DECLARATIONS)]
        prompt = f"Context: {context}\nSir says: \"{user_speech_text}\""

        # Candidate models list with requested model as primary
        candidate_models = [self.model_name, "gemini-3.1-flash-lite", "gemini-2.5-flash", "gemini-2.0-flash"]
        last_error = None

        for model in candidate_models:
            try:
                response = self.client.models.generate_content(
                    model=model,
                    contents=prompt,
                    config=types.GenerateContentConfig(
                        system_instruction=system_instruction,
                        tools=tools,
                        temperature=0.6,
                    )
                )

                tool_calls = []
                if response.function_calls:
                    for call in response.function_calls:
                        tool_calls.append({
                            "name": call.name,
                            "args": dict(call.args)
                        })

                return {
                    "speech_text": response.text.strip() if response.text else "Right away, Sir.",
                    "tool_calls": tool_calls,
                    "model_used": model
                }
            except Exception as e:
                last_error = e
                continue

        print(f"[Gemini Brain Warning] Error during generation: {last_error}")
        return self._emulate_intent(user_speech_text, context)

    def _emulate_intent(self, text: str, context: dict) -> dict:
        text_lower = text.lower()
        tool_calls = []
        speech = "As you wish, Sir."

        if any(w in text_lower for w in ["so raha", "sleep", "aankh lag rahi", "disturb mat", "shanti"]):
            tool_calls.append({
                "name": "activate_rest_or_dnd_mode",
                "args": {
                    "wake_time": "17:00" if "5" in text_lower else None,
                    "block_all_disturbances": True,
                    "custom_note": "User requested rest/focus"
                }
            })
            speech = "Understood, Sir. Silencing all audio and holding incoming communications."

        elif any(w in text_lower for w in ["jag gaya", "wake up", "uth gaya", "resume normal", "chalu karo normal", "stop rest", "stop sleep"]):
            tool_calls.append({
                "name": "resume_or_wake_up",
                "args": {
                    "user_confirmation": text
                }
            })
            speech = "Welcome back, Sir. Restoring all systems and compiling your post-rest briefing."

        elif any(w in text_lower for w in ["quarantine", "missed call", "koi message aaya", "koi disturbance", "check quarantine"]):
            tool_calls.append({
                "name": "review_quarantined_disturbances",
                "args": {
                    "limit": 10
                }
            })
            speech = "Allow me to review your quarantined communications, Sir."

        elif any(w in text_lower for w in ["kaun hai", "who is", "kya jaante ho", "tell me about"]):
            target = "Contact"
            for candidate in ["dhruv", "vishal", "manish", "anuj", "tractor", "shailender", "manager", "colleague", "friend", "brother", "papa"]:
                if candidate in text_lower:
                    target = candidate.capitalize()
                    break
            tool_calls.append({
                "name": "query_personal_knowledge",
                "args": {
                    "query": target
                }
            })
            speech = f"Checking personal knowledge records for {target}, Sir."

        elif any(w in text_lower for w in ["kisko kisko", "contacts", "list memory", "list knowledge", "known people"]):
            tool_calls.append({
                "name": "list_personal_knowledge",
                "args": {}
            })
            speech = "Accessing your personal knowledge directory, Sir."

        elif any(w in text_lower for w in ["family", "vip", "important", "zaroori", "khas"]):
            person = "Contact"
            for candidate in ["dhruv", "vishal", "manish", "anuj", "tractor", "shailender", "rahul", "papa", "mummy"]:
                if candidate in text_lower:
                    person = candidate.capitalize()
                    break
            rel = "Family Member" if any(w in text_lower for w in ["family", "parivar", "papa", "mummy", "bhai"]) else "VIP Contact"
            importance = 3 if any(w in text_lower for w in ["family", "parivar", "vip"]) else 2
            tool_calls.append({
                "name": "update_personal_knowledge",
                "args": {
                    "person_name": person,
                    "relationship": rel,
                    "importance": importance,
                    "notes": f"Marked as {rel} ({text})"
                }
            })
            speech = f"Bilkul Sir, maine note kar liya hai ki {person} aapke {rel} hain aur inko priority list me add kar diya hai."

        elif any(w in text_lower for w in ["mera dost", "mera bhai", "mera colleague", "mera manager", "is my", "dost hai", "bhai hai", "colleague hai"]):
            person = "Contact"
            for candidate in ["dhruv", "vishal", "manish", "anuj", "tractor", "shailender"]:
                if candidate in text_lower:
                    person = candidate.capitalize()
                    break
            rel = "Contact"
            for r in ["colleague", "manager", "friend", "dost", "brother", "bhai", "father", "papa"]:
                if r in text_lower:
                    rel = "Friend" if r in ("friend", "dost") else ("Brother" if r in ("brother", "bhai") else r.capitalize())
                    break
            tool_calls.append({
                "name": "update_personal_knowledge",
                "args": {
                    "person_name": person,
                    "relationship": rel,
                    "notes": text
                }
            })
            speech = f"Understood, Sir. I have recorded that {person} is your {rel}."

        elif any(w in text_lower for w in ["remind", "yaad dila"]):
            tool_calls.append({
                "name": "schedule_proactive_reminder",
                "args": {
                    "target_time": "15:00" if "3" in text_lower else "12:00",
                    "topic_or_person": "Contact",
                    "proactive_speech": "Sir, it is time for your scheduled call."
                }
            })
            speech = "I have noted that, Sir. I will remind you precisely on schedule."

        elif any(w in text_lower for w in ["call", "message", "msg", "bhejo", "likho", "likhna"]):
            # Detect app
            app = "WhatsApp" if any(w in text_lower for w in ["whatsapp", "whtsapp", "wa"]) else "Teams"
            is_call = any(w in text_lower for w in ["call", "dial"])
            call_type = "video" if "video" in text_lower else "audio"

            # Detect person
            person = "Contact"
            for candidate in ["dhruv", "vishal", "manish", "anuj", "tractor", "shailender", "abhh"]:
                if candidate in text_lower:
                    person = candidate.capitalize()
                    break

            # Detect message text
            msg_text = "..."
            if "hii" in text_lower or "hi" in text_lower:
                msg_text = "hii"
            elif "dot dot dot" in text_lower or "..." in text_lower:
                msg_text = "..."

            if is_call:
                tool_calls.append({
                    "name": "universal_communicate",
                    "args": {
                        "app_name": app,
                        "action": "call",
                        "contact_name": person,
                        "call_type": call_type
                    }
                })
                speech = f"Right away, Sir. Initiating {call_type} call to {person} on {app}."
            else:
                tool_calls.append({
                    "name": "universal_communicate",
                    "args": {
                        "app_name": app,
                        "action": "message",
                        "contact_name": person,
                        "message": msg_text
                    }
                })
                speech = f"Right away, Sir. Sending message to {person} on {app}."

        elif any(w in text_lower for w in ["youtube", "gaana", "gana", "song", "video", "play"]):
            query = text
            for strip_w in ["play", "on youtube", "youtube par", "chalao", "sunao", "laga do", "gaana", "song"]:
                query = query.lower().replace(strip_w, "").strip()
            tool_calls.append({
                "name": "browser_and_web_action",
                "args": {
                    "action": "play_youtube",
                    "query": query or "music"
                }
            })
            speech = f"Haan Sir, YouTube par '{query or 'music'}' play kar raha hoon."

        elif any(w in text_lower for w in ["google", "search", "dhoondo", "pata karo"]):
            query = text
            for strip_w in ["search", "google", "on google", "google par", "dhoondo", "karo"]:
                query = query.lower().replace(strip_w, "").strip()
            tool_calls.append({
                "name": "browser_and_web_action",
                "args": {
                    "action": "search_google",
                    "query": query or text
                }
            })
            speech = f"Sir, Google par '{query or text}' search kar diya hai."

        elif "tab" in text_lower:
            action = "new_tab"
            if any(w in text_lower for w in ["band", "close", "hatao"]):
                action = "close_tab"
            elif any(w in text_lower for w in ["next", "agla", "switch"]):
                action = "next_tab"
            elif any(w in text_lower for w in ["refresh", "reload"]):
                action = "refresh"
            tool_calls.append({
                "name": "browser_and_web_action",
                "args": {
                    "action": action
                }
            })
            speech = f"Sir, browser me {action.replace('_', ' ')} execute kar diya hai."

        elif any(w in text_lower for w in ["volume", "awaz", "awaaz"]):
            if any(w in text_lower for w in ["badhao", "badha", "up", "increase", "tez"]):
                tool_calls.append({
                    "name": "window_and_system_action",
                    "args": {"action": "volume_up", "value": 10}
                })
                speech = "Sir, master volume badha diya hai."
            elif any(w in text_lower for w in ["kam", "down", "decrease", "slow"]):
                tool_calls.append({
                    "name": "window_and_system_action",
                    "args": {"action": "volume_down", "value": 10}
                })
                speech = "Sir, volume thoda kam kar diya hai."
            elif "mute" in text_lower:
                tool_calls.append({
                    "name": "control_hardware_and_audio",
                    "args": {"action": "mute"}
                })
                speech = "Sir, audio mute kar diya hai."
            else:
                tool_calls.append({
                    "name": "control_hardware_and_audio",
                    "args": {"action": "set_volume", "value": 50}
                })
                speech = "Sir, volume adjust kar diya hai."

        elif any(w in text_lower for w in ["maximize", "minimize", "desktop", "window"]):
            if "maximize" in text_lower or "bada karo" in text_lower:
                tool_calls.append({"name": "window_and_system_action", "args": {"action": "maximize"}})
                speech = "Window maximize kar di hai, Sir."
            elif "minimize" in text_lower or "chhota karo" in text_lower:
                tool_calls.append({"name": "window_and_system_action", "args": {"action": "minimize"}})
                speech = "Window minimize kar di hai, Sir."
            elif "desktop" in text_lower:
                tool_calls.append({"name": "window_and_system_action", "args": {"action": "show_desktop"}})
                speech = "Desktop show kar diya hai, Sir."
            elif any(w in text_lower for w in ["band", "close"]):
                tool_calls.append({"name": "window_and_system_action", "args": {"action": "close_window"}})
                speech = "Active window close kar di hai, Sir."

        elif any(w in text_lower for w in ["pause", "resume", "media", "gaana roko"]):
            tool_calls.append({"name": "window_and_system_action", "args": {"action": "media_play_pause"}})
            speech = "Sir, media playback toggle kar diya hai."

        return {
            "speech_text": speech,
            "tool_calls": tool_calls,
            "model_used": "simulation"
        }

    def synthesize_wake_up_briefing(self, quarantined_messages: list, user_name: str = "Sir") -> str:
        """
        Dynamically synthesizes an aristocratic, conversational Hinglish wake-up briefing
        via Gemini 3.1 Flash Lite. Never returns a static or robotic string.
        """
        if not self.is_connected:
            return self._fallback_wake_up_briefing(quarantined_messages)

        prompt = f"""
        You are J.A.R.V.I.S., the AI companion to {user_name}.
        Sir has just concluded a rest or sleep period and all systems are restored.

        Quarantined communications while Sir was resting:
        {quarantined_messages}

        Task:
        Synthesize a completely dynamic, warm, courteous wake-up briefing in CASUAL EVERYDAY HINGLISH (as casually spoken in India).

        CRITICAL LINGUISTIC RULES:
        1. MUST BE IN CASUAL HINGLISH (Hindi words written in Roman script mixed naturally with English words, like friends/colleagues speak in real life).
        2. STRICTLY FORBIDDEN: DO NOT generate pure English!
        3. STRICTLY FORBIDDEN: DO NOT use formal, bookish, or Sanskritized Shuddh Hindi (avoid 'shubh prabhat', 'vishram', 'kripya', 'sandesh', 'suchit', 'taro-taza').
        4. Example of the desired vibe: "Good morning Sir! Aapka rest time complete ho gaya hai. Jab aap so rahe the tab Teams par Dhruv ka ek call aaya tha, kya main unko aapko screen par show karun ya baad me check karenge?"
        5. Keep it concise (2-3 sentences max). Speak naturally and smoothly. Never sound like a template.
        """
        candidate_models = [self.model_name, "gemini-3.1-flash-lite", "gemini-2.5-flash", "gemini-2.0-flash"]
        for model in candidate_models:
            try:
                response = self.client.models.generate_content(
                    model=model,
                    contents=prompt
                )
                if response.text:
                    return response.text.strip().replace("*", "")
            except Exception:
                continue

        return self._fallback_wake_up_briefing(quarantined_messages)

    def _fallback_wake_up_briefing(self, quarantined_messages: list) -> str:
        """Graceful casual everyday Hinglish fallback if offline."""
        if quarantined_messages:
            count = len(quarantined_messages)
            senders = list(dict.fromkeys(q["sender"] for q in quarantined_messages))
            apps = list(dict.fromkeys(q["source_app"] for q in quarantined_messages))
            senders_str = ", ".join(senders)
            apps_str = ", ".join(apps)
            return (
                f"Good morning Sir! Aapka rest time complete ho gaya hai. "
                f"Aapke rest ke time par {apps_str} par {senders_str} ka call ya message aaya tha jo maine hold kar liya tha. "
                f"Kya main inko aapko abhi show karun?"
            )
        else:
            return (
                "Good morning Sir! Aapka rest time complete ho chuka hai, sabhi systems online hain aur rest ke dauran koi disturbance nahi aayi. "
                "Bataiye, ab aage kya plan hai?"
            )

    def evaluate_ambient_activity(
        self,
        active_window: str,
        active_process: str,
        continuous_work_minutes: int,
        user_state: str = "normal"
    ) -> dict:
        """
        Autonomous Ambient Laptop Co-Pilot (Iron Man style):
        Evaluates Sir's screen activity, continuous work duration, and decides autonomously
        whether to suggest a break, rest, hydration, or coding advice in casual natural Hinglish.
        """
        if not self.is_connected:
            if continuous_work_minutes >= 90:
                return {
                    "should_speak": True,
                    "speech_text": f"Sir, aap lagbhag {continuous_work_minutes} minute se bina break ke {active_process} par lage hue ho. Thoda 5 minute ka rest le lijiye aur paani pee lo.",
                    "reason": "Fatigue prevention"
                }
            return {"should_speak": False, "speech_text": "", "reason": "Normal focus"}

        prompt = f"""
        You are J.A.R.V.I.S., the autonomous AI companion observing Sir's laptop activity in the background.
        Current Live Telemetry:
        - Foreground Application: {active_process}
        - Window Title: "{active_window}"
        - Continuous Work Duration: {continuous_work_minutes} minutes without a break
        - System State: {user_state}

        Task:
        Autonomously decide whether Sir needs a proactive, polite, caring suggestion, rest/break reminder, or hydration check.

        CRITICAL LINGUISTIC RULES:
        1. If should_speak is true, speech_text MUST be in CASUAL NATURAL HINGLISH (Roman Hindi + English, casual tone like Iron Man JARVIS speaking to Tony Stark in modern India).
        2. STRICTLY FORBIDDEN: DO NOT speak in pure English.
        3. STRICTLY FORBIDDEN: DO NOT use formal bookish Shuddh Hindi (avoid 'shubh prabhat', 'vishram', 'kripya', 'aahar').
        4. Example vibe: "Sir, aap lagbhag 2 ghante se lagatar screen par lage hue ho, thoda 5 minute ka break le lijiye aur paani pee lo, suit ko bhi cooling period chahiye hota hai!"
        5. If Sir does not need an interruption, return should_speak: false so Sir is never annoyed.

        Respond with ONLY valid JSON:
        {{
            "should_speak": true or false,
            "speech_text": "casual natural Hinglish speech if true, else empty string",
            "reason": "brief internal rationale"
        }}
        """
        candidate_models = [self.model_name, "gemini-3.1-flash-lite", "gemini-2.5-flash"]
        for model in candidate_models:
            try:
                response = self.client.models.generate_content(
                    model=model,
                    contents=prompt
                )
                if response.text:
                    import json
                    raw = response.text.strip()
                    if "```json" in raw:
                        raw = raw.split("```json")[1].split("```")[0].strip()
                    elif "```" in raw:
                        raw = raw.split("```")[1].split("```")[0].strip()
                    data = json.loads(raw)
                    return {
                        "should_speak": bool(data.get("should_speak", False)),
                        "speech_text": data.get("speech_text", "").replace("*", ""),
                        "reason": data.get("reason", "")
                    }
            except Exception:
                continue

        return {"should_speak": False, "speech_text": "", "reason": "Failed to parse"}

