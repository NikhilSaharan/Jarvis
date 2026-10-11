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
        - For checking emails, verifying if an email arrived from someone (e.g. 'Air Studio', 'AI Studio', 'Google', 'PayU', 'boss', 'college'), or checking what emails are in the inbox:
          Call `inspect_inbox_or_emails(sender_filter="...", open_if_closed=True)`.
          CRITICAL: NEVER hallucinate, guess, or make up whether an email exists or what is inside the inbox! ALWAYS call `inspect_inbox_or_emails`.
        - For accepting task changes in Antigravity IDE, clicking buttons, approving, or general computer actions:
          If Sir asks to accept changes in Antigravity or any app (e.g. 'antigravity me task ke changes accept kar do', 'accept all changes', 'accept karo', 'proceed'):
          Call `universal_computer_action(action="click_button", target="Accept All", app_name="Antigravity")`.
          CRITICAL: NEVER call `close_application` or `close_window` when Sir asks to accept changes or approve tasks!
        - For general OS/computer tasks (launching apps, clicking buttons, typing text):
          Call `universal_computer_action(action="open_app_or_url"|"click_button"|"type_text"|"press_keys", target="...", text="...", app_name="...")`.
        - For hardware, mute/unmute, lock screen, battery check: Call `control_hardware_and_audio`.
        - For sleep, rest, DND: Call `activate_rest_or_dnd_mode`.
        - For wake up or resuming normal mode: Call `resume_or_wake_up`.
        - For reviewing quarantined disturbances/missed alerts: Call `review_quarantined_disturbances`.
        - For proactive time-based reminders: Call `schedule_proactive_reminder`.
        - For saving/updating personal facts, family, friends, relationships: Call `update_personal_knowledge`.
          (Crucial rule: If Sir mentions a family member, VIP, or important contact e.g. 'mere family member hai', 'important hai', 'VIP hai', pass `importance=3` or `2`).
        - For asking who someone is or inquiring about a role/relationship: Call `query_personal_knowledge`.
        - For viewing all saved relationships/contacts: Call `list_personal_knowledge`.
        - For Detective Mode / laptop surveillance (e.g. 'detective mode on', 'activate detective mode', 'sentry mode on'):
          Call `activate_detective_mode()`.
        - For deactivating Detective Mode manually (e.g. 'detective mode off', 'stop detective mode'):
          Call `deactivate_detective_mode()`.
        - For checking what happened on the laptop, what was opened, or if someone touched the laptop (e.g. 'kya hua tha', 'kya kya open hua tha', 'pichhle 2 minute mein kya open hua', 'kisne chhua tha', 'mere piche se kya hua', 'activity timeline'):
          Call `get_laptop_activity_timeline(minutes=2)`.
          CRITICAL: This automatically debriefs Sir with real chronological facts and turns Detective Mode OFF. NEVER guess or hallucinate!
        - For monitoring, watching, or checking for incoming messages from ANY contact on ANY communication app (WhatsApp Web, Teams, Telegram, Slack, etc.):
          Call `monitor_incoming_messages(contact_name=..., app_name=...)` with the contact name and application name specified by Sir.

        CRITICAL SPOKEN LANGUAGE & LATENCY RULES:
        1. MANDATORY: ALWAYS speak to Sir in CASUAL, NATURAL HINGLISH (everyday conversational Hindi written in Roman script mixed naturally with English words).
        2. NEVER speak pure English, and NEVER use theatrical, cheesy, or weird metaphors (strictly avoid 'hawk-eye', 'coffee gets cold', 'IRS audit', 'flying suit'). Speak normally, practically, and respectfully: e.g. 'Theek hai Sir, main WhatsApp par Mummy ke messages check karta rahunga, jaise hi message aayega turant inform kar dunga.'
        3. KEEP REPLIES SHORT AND CRISP (maximum 1-2 short sentences, 10-15 words max) so responses feel instantaneous (< 2 seconds).
        4. NEVER reply with repetitive robotic phrases like "Right away, Sir." or "As you wish, Sir." - Be natural, dynamic, and polite like Iron Man's JARVIS.
        5. If Sir asks you to send a message or perform an action at a future time (e.g., '2:42 par', '242 per', '5 minute baad', '10 baje'), DO NOT send it immediately! Set the `schedule_time` or `delay_minutes` parameter in `universal_communicate` or use `schedule_proactive_reminder`.
        6. If Sir mentions resting, headache, sleep, or says 'utha dena' at a specific time or in N minutes, call `activate_rest_or_dnd_mode(wake_time=..., duration_minutes=..., block_all_disturbances=True)`.
        7. NEVER use formal, difficult, or bookish Shuddh Hindi words (strictly avoid words like 'shubh prabhat', 'vishram', 'kripya', 'sandesh', 'tathya').
        8. Always reply with dynamic conversational Hinglish text alongside function calls!
        """

        tools = [types.Tool(function_declarations=JARVIS_TOOL_DECLARATIONS)]
        prompt = f"Context: {context}\nSir says: \"{user_speech_text}\""

        # High-speed model candidates (sub-second to ~1.4s response time)
        candidate_models = [self.model_name, "gemini-2.5-flash"]
        last_error = None

        for model in candidate_models:
            try:
                response = self.client.models.generate_content(
                    model=model,
                    contents=prompt,
                    config=types.GenerateContentConfig(
                        system_instruction=system_instruction,
                        tools=tools,
                        temperature=0.4,
                    )
                )

                tool_calls = []
                if response.function_calls:
                    for call in response.function_calls:
                        tool_calls.append({
                            "name": call.name,
                            "args": dict(call.args)
                        })

                # Extract any text generated alongside tool calls
                speech_text = ""
                if response.candidates and response.candidates[0].content and response.candidates[0].content.parts:
                    for part in response.candidates[0].content.parts:
                        if hasattr(part, "text") and part.text and part.text.strip():
                            speech_text = part.text.strip()
                            break

                # If no text was returned alongside tool calls, synthesize instant natural Hinglish speech (0ms)
                # This completely eliminates the slow secondary network roundtrip (<1ms latency)
                if not speech_text:
                    if tool_calls:
                        speech_text = self._synthesize_instant_tool_speech(tool_calls, user_speech_text)
                    else:
                        speech_text = self._generate_dynamic_speech(user_speech_text, tool_calls=tool_calls, model=model)

                return {
                    "speech_text": speech_text or "Done, Sir.",
                    "tool_calls": tool_calls,
                    "model_used": model
                }
            except Exception as e:
                last_error = e
                continue

        print(f"[Gemini Brain Warning] Error during generation: {last_error}")
        return self._emulate_intent(user_speech_text, context)

    def _synthesize_instant_tool_speech(self, tool_calls: list, user_speech_text: str = "") -> str:
        """
        Synthesizes instantaneous zero-latency (<1ms) natural Hinglish speech for tool executions.
        Completely eliminates secondary network round-trips for instantaneous voice replies.
        """
        if not tool_calls:
            return "Done, Sir."

        tc = tool_calls[0]
        name = tc.get("name", "")
        args = tc.get("args", {})

        if name == "update_personal_knowledge":
            person = args.get("person_name", "Contact")
            rel = args.get("relationship", "Priority Contact")
            return f"{person} ko priority list mein update kar diya hai, Sir."

        elif name == "monitor_incoming_messages":
            contact = args.get("contact_name", "Contact")
            app = args.get("app_name", "WhatsApp")
            return f"Theek hai Sir, main {app} par {contact} ke messages check karta rahunga, aate hi bata dunga."

        elif name == "universal_communicate":
            contact = args.get("contact_name", "Contact")
            app = args.get("app_name", "Application")
            act = args.get("action", "message")
            return f"Done Sir, {app} par {contact} ko {act} bhej raha hoon."

        elif name == "activate_detective_mode":
            return "Detective mode active kar diya hai, Sir."

        elif name == "deactivate_detective_mode":
            return "Detective mode off kar diya hai, Sir."

        elif name == "activate_rest_or_dnd_mode":
            return "Rest mode engage kar diya hai, Sir. Shanti se aaram kijiye."

        elif name == "resume_or_wake_up":
            return "Welcome back Sir, saare systems active hain."

        elif name == "window_and_system_action":
            act = args.get("action", "")
            if "volume" in act:
                return "Volume adjust kar di hai, Sir."
            return f"Done Sir, {act} execute kar diya hai."

        elif name == "universal_computer_action":
            target = args.get("target") or args.get("action", "Action")
            return f"Theek hai Sir, {target} perform kar diya hai."

        elif name == "browser_and_web_action":
            action = args.get("action", "action")
            return f"Done Sir, {action} execute kar diya hai."

        elif name == "inspect_inbox_or_emails":
            return "Inbox check kar raha hoon, Sir."

        return "Done, Sir."

    def _generate_dynamic_speech(self, user_speech_text: str, tool_calls: list = None, model: str = None) -> str:
        """
        Dynamically synthesizes witty, natural Iron Man JARVIS Hinglish speech using Gemini.
        Zero hardcoded strings or static template matching.
        """
        if not self.is_connected or not self.client:
            return ""

        model = model or self.model_name
        prompt = (
            f"You are J.A.R.V.I.S., Tony Stark's personal AI butler.\n"
            f"Sir said: \"{user_speech_text}\".\n"
        )
        if tool_calls:
            prompt += f"You have executed or scheduled these autonomous actions: {tool_calls}.\n"

        prompt += (
            "Speak a single concise, witty, natural sentence to Sir in casual everyday conversational Hinglish "
            "(Roman Hindi mixed naturally with English, exactly as friends/colleagues speak in real life).\n"
            "STRICT RULES:\n"
            "1. NEVER speak pure English! ALWAYS speak in natural conversational Hinglish (Roman Hindi mixed naturally with normal English words).\n"
            "2. Keep it simple, respectful, and direct (no cheesy metaphors like 'hawk-eye', 'coffee gets cold', 'IRS audit').\n"
            "3. NEVER use formal/bookish Hindi (no 'shubh prabhat', 'kripya', 'sandesh').\n"
            "4. NEVER say robotic lines like 'Right away Sir' or 'Ji Sir'.\n"
            "5. Maximum 1 short sentence (10-15 words max).\n"
            "Spoken response:"
        )

        candidate_models = [model, "gemini-2.5-flash"]
        for m in candidate_models:
            try:
                resp = self.client.models.generate_content(
                    model=m,
                    contents=prompt,
                    config={"temperature": 0.5}
                )
                if resp.text and resp.text.strip():
                    cleaned = resp.text.strip().replace('"', '').replace("'", "").replace("*", "")
                    return cleaned
            except Exception:
                continue

        return ""

    def _emulate_intent(self, text: str, context: dict) -> dict:
        text_lower = text.lower()
        tool_calls = []
        speech = "As you wish, Sir."

        import re
        if any(w in text_lower for w in ["so raha", "sleep", "aankh lag rahi", "disturb mat", "shanti", "sir dard", "sar dard", "dard ho raha", "utha dena", "wake me"]):
            w_time = None
            dur = None
            time_match = re.search(r'(\d{1,2})[:.](\d{2})', text_lower)
            if time_match:
                w_time = f"{int(time_match.group(1)):02d}:{time_match.group(2)}"
            elif re.search(r'\b\d{3,4}\b', text_lower):
                m = re.search(r'\b(\d{1,2})(\d{2})\b', text_lower)
                if m:
                    w_time = f"{int(m.group(1)):02d}:{m.group(2)}"
            dur_match = re.search(r'(\d+)\s*(?:min|minute|minut)', text_lower)
            if dur_match:
                dur = int(dur_match.group(1))

            tool_calls.append({
                "name": "activate_rest_or_dnd_mode",
                "args": {
                    "wake_time": w_time,
                    "duration_minutes": dur,
                    "block_all_disturbances": True,
                    "custom_note": "User requested rest/focus"
                }
            })
            time_txt = f"{w_time}" if w_time else (f"{dur} minute baad" if dur else "aapke uthne tak")
            speech = f"Bilkul Sir, systems mute kar diye hain aur {time_txt} saare disturbances hold par rahenge. Aap befikr aaram kijiye."

        elif any(w in text_lower for w in ["jag gaya", "wake up", "uth gaya", "resume normal", "chalu karo normal", "stop rest", "stop sleep"]):
            tool_calls.append({
                "name": "resume_or_wake_up",
                "args": {
                    "user_confirmation": text
                }
            })
            speech = "Welcome back Sir! All systems restored. Post-rest briefing compile kar raha hoon."

        elif any(w in text_lower for w in ["quarantine", "missed call", "koi message aaya", "koi disturbance", "check quarantine"]):
            tool_calls.append({
                "name": "review_quarantined_disturbances",
                "args": {
                    "limit": 10
                }
            })
            speech = "Sir, quarantined communications check kar raha hoon."

        elif any(w in text_lower for w in ["detective mode on", "detective on", "detective chalu", "activate detective", "sentry on", "sentry mode on"]):
            tool_calls.append({
                "name": "activate_detective_mode",
                "args": {
                    "custom_note": text
                }
            })
            speech = "Detective mode online Sir. Ab laptop par har ek movement aur app switch secretly record hoga."

        elif any(w in text_lower for w in ["detective mode off", "detective off", "detective band", "deactivate detective", "sentry off"]):
            tool_calls.append({
                "name": "deactivate_detective_mode",
                "args": {}
            })
            speech = "Detective mode disengaged Sir. All systems back to normal."

        elif any(w in text_lower for w in ["kya kya open", "kya open hua", "kya hua tha", "kisne chhua", "piche se kaun", "kya chal raha tha", "activity check", "pichhle 2", "pichle 2", "pichle do"]):
            import re
            mins = 5
            min_match = re.search(r'(\d+)\s*(?:min|minute|minut)', text_lower)
            if min_match:
                mins = int(min_match.group(1))
            elif "2" in text_lower or "do" in text_lower:
                mins = 2
            tool_calls.append({
                "name": "get_laptop_activity_timeline",
                "args": {
                    "minutes": mins,
                    "query": text
                }
            })
            speech = f"Sir, activity timeline scan karke Detective Mode disengage kar raha hoon."

        elif any(w in text_lower for w in ["check karte rehna", "check karna", "message to nahin", "message to nahi", "jab bhi aaye", "message aaye to", "monitor", "nazar rakhna"]):
            # Extract App Dynamically (look for token before 'web per', 'per', 'par', 'pe', 'me', or after 'on', 'in')
            app_match = re.search(r'\b([a-zA-Z0-9_\-\.]+)\s+(?:web\s+)?(?:par|per|pe|me|mein)\b', text, re.IGNORECASE)
            if not app_match:
                app_match = re.search(r'\b(?:on|in)\s+([a-zA-Z0-9_\-\.]+)\b', text, re.IGNORECASE)
            
            extracted_app = app_match.group(1).capitalize() if app_match else "Application"
            if extracted_app.lower() in ["us", "is", "laptop", "screen", "computer", "system"]:
                extracted_app = "Application"

            # Extract Contact Dynamically (e.g. 'ki [Name] ka message', '[Name] ka koi message', 'from [Name]')
            contact_match = re.search(r'\b(?:ki|agar|jab)?\s*([a-zA-Z0-9_\-]+)\s+(?:ka|ke|ki)\s+(?:koi\s+)?(?:message|msg|call)', text, re.IGNORECASE)
            if not contact_match:
                contact_match = re.search(r'\b(?:from|check)\s+([a-zA-Z0-9_\-]+)(?:\'s)?\s+(?:message|msg)', text, re.IGNORECASE)
            if not contact_match:
                contact_match = re.search(r'\b([a-zA-Z0-9_\-]+)\s+(?:se\s+)?(?:message|msg)\b', text, re.IGNORECASE)

            extracted_contact = contact_match.group(1).capitalize() if contact_match else "Contact"

            tool_calls.append({
                "name": "monitor_incoming_messages",
                "args": {
                    "contact_name": extracted_contact,
                    "app_name": extracted_app,
                    "instruction": text
                }
            })
            speech = f"Theek hai Sir, main {extracted_app} par {extracted_contact} ke messages check karta rahunga, aate hi bata dunga."

        elif any(w in text_lower for w in ["kaun hai", "who is", "kya jaante ho", "tell me about"]):
            # Dynamically extract person name
            name_match = re.search(r'\b([a-zA-Z0-9_\-]+)\s+kaun\s+hai\b', text, re.IGNORECASE)
            if not name_match:
                name_match = re.search(r'\bwho\s+is\s+([a-zA-Z0-9_\-]+)\b', text, re.IGNORECASE)
            if not name_match:
                name_match = re.search(r'\btell\s+me\s+about\s+([a-zA-Z0-9_\-]+)\b', text, re.IGNORECASE)
            if not name_match:
                name_match = re.search(r'\b([a-zA-Z0-9_\-]+)\s+ke\s+baare\s+mein\b', text, re.IGNORECASE)
            
            target = name_match.group(1).capitalize() if name_match else "Contact"
            tool_calls.append({
                "name": "query_personal_knowledge",
                "args": {
                    "query": target
                }
            })
            speech = f"Sir, personal records mein {target} ki details check kar raha hoon."

        elif any(w in text_lower for w in ["kisko kisko", "contacts", "list memory", "list knowledge", "known people"]):
            tool_calls.append({
                "name": "list_personal_knowledge",
                "args": {}
            })
            speech = "Accessing your personal knowledge directory, Sir."

        elif any(w in text_lower for w in ["family", "vip", "important", "zaroori", "khas", "mera dost", "mera bhai", "mera colleague", "is my", "dost hai", "bhai hai", "colleague hai"]):
            # Dynamically extract person name
            name_match = re.search(r'\b([a-zA-Z0-9_\-]+)\s+(?:mere|mera|meri|ko|is\s+my)\b', text, re.IGNORECASE)
            if not name_match:
                name_match = re.search(r'\b(?:ki|agar)?\s*([a-zA-Z0-9_\-]+)\s+(?:ko|hai)\b', text, re.IGNORECASE)
            person = name_match.group(1).capitalize() if name_match else "Contact"

            # Dynamically extract relationship
            rel_match = re.search(r'\b(?:mera|meri|mere|is\s+my)\s+([a-zA-Z0-9_\-]+)\b', text, re.IGNORECASE)
            if rel_match and rel_match.group(1).lower() not in ["vip", "important", "zaroori"]:
                rel = rel_match.group(1).capitalize()
            elif any(w in text_lower for w in ["family", "parivar", "family member"]):
                rel = "Family Member"
            else:
                rel = "VIP Contact"

            importance = 3 if any(w in text_lower for w in ["family", "parivar", "vip", "important", "zaroori"]) else 2
            tool_calls.append({
                "name": "update_personal_knowledge",
                "args": {
                    "person_name": person,
                    "relationship": rel,
                    "importance": importance,
                    "notes": f"Marked as {rel} ({text})"
                }
            })
            speech = f"Theek hai Sir, maine note kar liya hai ki {person} aapke {rel} hain aur inko priority list me save kar diya hai."

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

        elif any(w in text_lower for w in ["call", "message", "msg", "bhejo", "likho", "likhna", "bol do"]):
            # Detect app dynamically
            app = "Teams"
            for a in ["whatsapp", "telegram", "slack", "discord", "skype", "teams"]:
                if a in text_lower:
                    app = "WhatsApp" if a == "whatsapp" else a.capitalize()
                    break

            is_call = any(w in text_lower for w in ["call", "dial"])
            call_type = "video" if "video" in text_lower else "audio"

            # Detect person dynamically
            person = "Contact"
            name_match = re.search(r'([a-zA-Z]+)\s+(?:ko|to)\b', text)
            if name_match and name_match.group(1).lower() not in ("teams", "whatsapp", "call", "msg", "message"):
                person = name_match.group(1).capitalize()
            else:
                for w in text.split():
                    w_clean = re.sub(r'[^a-zA-Z]', '', w)
                    if len(w_clean) > 2 and w_clean.lower() not in ("ek", "kam", "karo", "per", "par", "pe", "teams", "whatsapp", "hello", "call", "msg", "message", "likho", "likhna"):
                        person = w_clean.capitalize()
                        break

            # Detect message text dynamically
            msg_text = "hello"
            msg_match = re.search(r'(?:likh(?:o|na)?|bhej(?:o|na)?|say|text)\s+(?:ki\s+)?["\']?([^"\']+)["\']?$', text, re.IGNORECASE)
            if msg_match:
                extracted = msg_match.group(1).strip()
                if extracted:
                    msg_text = extracted
            elif "hello" in text_lower or "he;llo" in text_lower:
                msg_text = "hello"
            elif "subah uth" in text_lower:
                msg_text = "Subah uth jaaiye"
            elif "hii" in text_lower or "hi" in text_lower:
                msg_text = "hii"
            elif "dot dot dot" in text_lower or "..." in text_lower:
                msg_text = "..."

            # Detect scheduled time (e.g. 242 per, 2:42 per, 5 min baad)
            sched_time = None
            delay_min = None
            time_match = re.search(r'(\d{1,2})[:.](\d{2})', text_lower)
            if time_match:
                sched_time = f"{int(time_match.group(1)):02d}:{time_match.group(2)}"
            elif re.search(r'\b\d{3,4}\b', text_lower):
                m = re.search(r'\b(\d{1,2})(\d{2})\b', text_lower)
                if m:
                    sched_time = f"{int(m.group(1)):02d}:{m.group(2)}"
            delay_match = re.search(r'(\d+)\s*(?:min|minute|minut)', text_lower)
            if delay_match:
                delay_min = int(delay_match.group(1))

            comm_args = {
                "app_name": app,
                "action": "call" if is_call else "message",
                "contact_name": person,
                "message": msg_text,
                "call_type": call_type
            }
            if sched_time:
                comm_args["schedule_time"] = sched_time
            if delay_min:
                comm_args["delay_minutes"] = delay_min

            tool_calls.append({
                "name": "universal_communicate",
                "args": comm_args
            })

            if sched_time or delay_min:
                t_str = sched_time if sched_time else f"{delay_min} minute baad"
                speech = f"Theek hai Sir, {t_str} par {person} ko {app} par '{msg_text}' bhej diya jayega."
            elif is_call:
                speech = f"Ji Sir, {person} ko {app} par {call_type} call connect kar raha hoon."
            else:
                speech = f"Ji Sir, abhi {person} ko {app} par message bhej raha hoon."

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

        elif any(w in text_lower for w in ["mail", "mel", "email", "inbox"]):
            sender = ""
            for cand in ["air studio", "ai studio", "google", "payu", "anthropic", "manish", "dhruv", "oodles", "grover"]:
                if cand in text_lower:
                    sender = cand
                    break
            tool_calls.append({
                "name": "inspect_inbox_or_emails",
                "args": {
                    "sender_filter": sender or ("important" if "important" in text_lower else ""),
                    "open_if_closed": True
                }
            })
            speech = f"Sir, inbox check kar raha hoon."

        elif any(w in text_lower for w in ["accept", "approve", "task", "changes", "proceed", "keep"]):
            target_btn = "Accept All"
            app = "Antigravity" if "antigravity" in text_lower else ""
            tool_calls.append({
                "name": "universal_computer_action",
                "args": {
                    "action": "click_button",
                    "target": target_btn,
                    "app_name": app or "Antigravity"
                }
            })
            speech = f"Sir, {app or 'Antigravity'} me task changes accept kar raha hoon."

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
            elif any(w in text_lower for w in ["band", "close"]) and not any(w in text_lower for w in ["task", "change", "accept"]):
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

    def synthesize_observation_speech(self, user_prompt: str, observation_data: any) -> str:
        """
        Dynamically synthesizes a concise, 100% factual, natural Hinglish spoken answer
        based on real OS/email observations or chronological activity timeline. Maximum 1-2 short sentences.
        """
        if not self.is_connected or not self.client:
            if isinstance(observation_data, dict) and "chronological_activity_timeline" in observation_data:
                acts = observation_data.get("chronological_activity_timeline", [])
                if acts:
                    titles = [a.get("window", "") for a in acts if a.get("window")]
                    return f"Sir, pichhle time mein {', '.join(titles[:3])} open hua tha."
                return "Sir, pichhle time mein koi activity detect nahi hui."
            return f"Sir, observed data: {observation_data}"

        prompt = f"""
        You are J.A.R.V.I.S., Tony Stark's personal AI butler to Sir.
        Sir asked: "{user_prompt}"

        Real Data Actually Observed on Sir's System (Inbox, Screen, or Laptop Activity Timeline):
        {observation_data}

        Task:
        Answer Sir's question truthfully, accurately, and politely in casual conversational Hinglish (Roman script).
        STRICT RULES:
        1. Be 100% factual based ONLY on the observed data above. NEVER hallucinate, invent, or guess!
        2. If Sir is asking about laptop activity, what happened, or what was opened in the last few minutes / while he was away:
           - Clearly and chronologically mention the real applications, files/folders (e.g. File Explorer), and websites/media (e.g. Chrome, YouTube) that were opened.
           - If Detective Mode was active, mention that Detective Mode is now turned off / disengaged.
           - If nothing unusual happened or no events exist, truthfully state that the laptop was untouched.
        3. If Sir is asking about emails or messages:
           - Clearly state matching senders and subjects from the observed data.
        4. Keep it concise and punchy (1-2 short sentences max, under 15-20 words).
        5. Casual conversational Hinglish (natural, sharp, polite like Iron Man's JARVIS).
        Spoken answer:
        """
        try:
            resp = self.client.models.generate_content(
                model=self.model_name,
                contents=prompt,
                config={"temperature": 0.3}
            )
            if resp.text:
                return resp.text.strip().replace('"', '').replace('*', '')
        except Exception as e:
            print(f"[Gemini Brain Observation Speech Warning] {e}")

        return f"Sir, {observation_data}"

    def synthesize_wake_up_briefing(self, quarantined_messages: list, user_name: str = "Sir") -> str:
        """
        Dynamically synthesizes a concise, natural, single-sentence Hinglish wake-up greeting
        via Gemini 3.1 Flash Lite. Never returns a lengthy or repetitive string.
        """
        if not self.is_connected:
            return self._fallback_wake_up_briefing(quarantined_messages)

        prompt = f"""
        You are J.A.R.V.I.S., the AI companion to {user_name}.
        Sir has concluded a rest or sleep period and all systems are restored.

        Quarantined communications while Sir was resting:
        {quarantined_messages}

        Task:
        Synthesize EXACTLY ONE SHORT SENTENCE (10-15 words max) in casual everyday conversational Hinglish.
        If there was a call or message, mention it briefly in 1 line.
        If no disturbance, just say good morning and that it's time to wake up.

        RULES:
        1. MUST be casual conversational Hinglish written in Roman script.
        2. STRICTLY ONE SENTENCE ONLY (concise, polite, natural).
        3. No bookish Shuddh Hindi.
        Example with disturbance: "Good morning Sir, time ho gaya hai; rest ke dauran Teams par Dhruv ka call aaya tha."
        Example without disturbance: "Good morning Sir, time ho gaya hai, uth jaiye."
        Spoken sentence:
        """
        candidate_models = [self.model_name, "gemini-3.1-flash-lite", "gemini-2.5-flash", "gemini-2.0-flash"]
        for model in candidate_models:
            try:
                response = self.client.models.generate_content(
                    model=model,
                    contents=prompt
                )
                if response.text:
                    return response.text.strip().replace("*", "").replace('"', '')
            except Exception:
                continue

        return self._fallback_wake_up_briefing(quarantined_messages)

    def _fallback_wake_up_briefing(self, quarantined_messages: list) -> str:
        """Single crisp, casual everyday Hinglish sentence fallback."""
        if quarantined_messages:
            senders = list(dict.fromkeys(q["sender"] for q in quarantined_messages))
            apps = list(dict.fromkeys(q["source_app"] for q in quarantined_messages))
            senders_str = ", ".join(senders)
            apps_str = ", ".join(apps)
            return f"Good morning Sir, time ho gaya hai; aaraam ke waqt {apps_str} par {senders_str} ka call aaya tha."
        else:
            return "Good morning Sir, time ho gaya hai, uth jaiye."

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

