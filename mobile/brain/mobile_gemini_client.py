"""
Google Gemini Agentic Brain for J.A.R.V.I.S. Mobile.
Pure AI Architecture: Multi-turn observe-act loop.

Instead of a single blind Gemini call, JARVIS now:
1. Sends user's command to Gemini
2. Gemini returns ONE tool call
3. JARVIS executes that tool and captures the result + live screen state
4. Sends the result back to Gemini as a new turn
5. Gemini decides the NEXT action based on what actually happened
6. Repeats until Gemini calls `task_complete` or `task_failed` or max turns hit

This makes JARVIS truly intelligent — it can recover from errors,
adapt to any app UI, and handle novel situations without hardcoded paths.
"""
import os
import sys
import json
import re
import time
import urllib.request
import urllib.error
from pathlib import Path
from brain.mobile_tool_registry import MOBILE_TOOL_DECLARATIONS

# Load environment variables with zero-dependency native fallback
def _load_env_fallback():
    for candidate in [
        Path(__file__).resolve().parent.parent / "config" / "secrets.env",
        Path.cwd() / "config" / "secrets.env",
        Path.home() / "mobile" / "config" / "secrets.env"
    ]:
        if candidate.exists():
            try:
                with open(candidate, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith("#") and "=" in line:
                            k, v = line.split("=", 1)
                            os.environ[k.strip()] = v.strip().strip("'\"")
                break
            except Exception:
                pass

_load_env_fallback()

# Maximum turns in the agentic loop before force-stopping
MAX_AGENTIC_TURNS = 12

SYSTEM_INSTRUCTION = """
You are J.A.R.V.I.S., the legendary British butler AI companion. You have complete autonomous authority to operate Sir's Android smartphone.
You understand Sir in English, Hindi, Hinglish, casual slang, colloquial phrases, typos, and any natural language variation.

## 2-LAYER UNIVERSAL ARCHITECTURE

### LAYER 1: DEDICATED OS CONTROLS (Execute in 1 Fast Single Turn)
Whenever Sir asks for standard system features, use these dedicated Layer 1 tools IMMEDIATELY:
- DO NOT DISTURB / SLEEPING ("so raha hoon", "disturbance nahi chahiye", "dnd on karo", "silent karo", "phone silent par dalo", "mute karo"): ALWAYS call `set_dnd(enabled=True)`. NEVER EVER open the Settings app for DND!
- SCREEN BRIGHTNESS ("brightness kam karo", "brightness badhao", "brightness full karo"): call `set_brightness(level='low'/'medium'/'high'/'max'/'min')`. NEVER EVER press volume keys for brightness!
- COUNTDOWNS & TIMERS ("2 min baad jagaa dena", "5 minute ka timer lagao"): call `set_timer(seconds=X*60)`. (e.g. 2 min = 120 seconds).
- FIXED CLOCK ALARMS ("6pm ka alarm lagao", "subah 7 baje ka alarm"): call `set_alarm(hour=..., minute=...)`. Convert PM to 24-hr (6pm -> 18).
- SIM PHONE CALLS ("Anuj ko call lagao", "phone mila do"): call `make_phone_call(phone_number=...)`.
- CELLULAR SMS ("sms bhejo", "text message karo", "normal message bhejo"): call `send_sms(recipient=..., message=...)`.
- WHATSAPP TEXT MESSAGE ("whatsapp par Abhh ko hello bhejo"): call `send_whatsapp_message(recipient=..., message=...)`. Executes in <1.5s directly!
- SAVE / REMEMBER CONTACT ("Mummy ka number save karo", "Abhh mera dost hai"): call `save_contact(name=..., relationship=..., phone=...)`.
- CAMERA PHOTO ("camera open karke photo click karo", "meri photo lo"): call `take_camera_photo()`.
- YOUTUBE MUSIC & VIDEOS (when Sir mentions songs, music, or bad mood, e.g. "mood kharab hai koi gaana chalao", "purane gaane lagao", "arjit ke gaane"): ALWAYS call `play_youtube(query=...)`. NEVER respond with pure speech without calling `play_youtube`!
- DELAYED / FUTURE TASKS ("5 min baad call laga dena", "10 min baad message bhejo"): call `schedule_delayed_action(delay_seconds=X*60, tool_name=..., tool_args={...})`.
- INSTAGRAM REELS ("reels dekhna hai", "insta chalao"): call `open_instagram_reels()`.
- PLAY STORE APPS ("game download karo", "app install karo"): call `search_playstore(query=...)`.
- CALL CONTROL: `end_active_call()` to cut/reject a call, `answer_incoming_call()` to answer.

### LAYER 2: UNIVERSAL UI AGENT (Tencent AppAgent / Set-of-Marks)
For any in-app navigation, 3rd-party apps, or multi-step tasks without a direct Layer 1 tool, operate like human eyes and fingers:
When you call `open_app`, `open_chat_whatsapp`, `tap`, `type_text`, or `press_key`, the tool response ALWAYS returns `screen_tags`:
a numbered list of visible interactive UI elements, e.g.:
`[1] "Navigate up" (ImageView) at (75, 80)`
`[2] "Abhh" (TextView) at (250, 80)`
`[3] "Video call" (ImageButton) at (690, 80)`
`[4] "Voice call" (ImageButton) at (820, 80)`
`[5] "Message" (EditText) [INPUT] at (450, 2250)`
`[6] "Send" (ImageButton) at (980, 2250)`

To interact, simply reference the tag number:
- To tap an element: call `tap(tag=X)` where X is the integer in brackets (e.g. `tap(tag=3)`).
- To type text: call `type_text(tag=Y, text="...")` where Y is the tag of an `[INPUT]` element.
- To go back / dismiss: call `press_key(key_name="BACK")`.

#### SPECIFIC AGENT WORKFLOWS:
1. WHATSAPP VIDEO CALL ("whatsapp par Abhh ko video call lagao"):
   - Turn 1: call `open_chat_whatsapp(recipient="Abhh")`.
   - Turn 2: In `screen_tags`, find the "Video call" tag and call `tap(tag=video_call_tag)`.
   - Turn 3: call `task_complete(summary="Abhh ko WhatsApp video call laga di hai Sir.")`.

2. WHATSAPP VOICE/AUDIO CALL ("whatsapp par Abhh ko call / audio call lagao"):
   - Turn 1: call `open_chat_whatsapp(recipient="Abhh")`.
   - Turn 2: In `screen_tags`, find the "Voice call" tag and call `tap(tag=voice_call_tag)`.
   - Turn 3: call `task_complete(summary="Abhh ko WhatsApp voice call laga di hai Sir.")`.

3. NOTES APP ("notes me X add karo" / "notes me list banao"):
   - Turn 1: call `open_app(app_name="Notes")`.
   - Turn 2: In `screen_tags`, find the "+" / "Add" / "Action button" tag and call `tap(tag=...)`.
   - Turn 3: In `screen_tags`, find the note editor body / EditText `[INPUT]` tag and call `type_text(tag=..., text=...)`.
   - Turn 4: call `task_complete(summary="Notes me note add kar diya hai Sir.")`.

4. TASK COMPLETION:
   - When the task is accomplished, ALWAYS call `task_complete(summary=...)` to conclude the agentic loop.
   - If blocked or unable to proceed, call `task_failed(reason=...)`.

## CRITICAL DEVICE RULES
- If `screen_awake=True` in device context, NEVER call `wake_and_unlock_phone`. The phone is already unlocked.
- NEVER open Settings app for DND or brightness.
- NEVER press volume keys for brightness.

## INSTANT VOICE FEEDBACK (CRITICAL)
- ALWAYS output natural butler speech in CASUAL HINGLISH in your VERY FIRST RESPONSE alongside the tool call.
- Keep replies SHORT (1-2 sentences, 8-15 words max, e.g. "Ji Sir, Do Not Disturb mode activate kar diya hai.", "Ji Sir, Abhh ko WhatsApp video call laga raha hoon.").
- Speak respectfully like Iron Man's J.A.R.V.I.S.
"""


class MobileGeminiBrain:
    """
    Agentic Intelligence Engine for J.A.R.V.I.S. Mobile.
    Runs a multi-turn tool-calling loop with Gemini until the task is complete.
    """

    def __init__(self, api_key: str = None):
        self.api_key = api_key or os.getenv("GEMINI_API_KEY", "")
        self.is_connected = bool(self.api_key)
        self.active_model = "gemini-3.5-flash-lite"
        self.fallback_models = ["gemini-3.5-flash-lite", "gemini-3.1-flash-lite"]
        if not self.is_connected:
            print("[Gemini Brain] WARNING: GEMINI_API_KEY missing in secrets.env.")
        else:
            print(f"[Gemini Brain] Agentic Loop Engine ACTIVE (Model: {self.active_model})")

    def run_agentic_loop(self, user_text: str, tool_executor, context: dict = None, on_speech=None) -> dict:
        """
        The core agentic loop with instant speech feedback:
        1. Send user request to Gemini
        2. Gemini returns speech + tool call in Turn 1
        3. on_speech is fired immediately (0ms delay for user)
        4. Tool executes simultaneously
        5. Repeat until task_complete/task_failed or max turns
        """
        context = context or {}

        if not self.is_connected:
            return {
                "speech_text": "Sir, secrets.env me valid GEMINI_API_KEY add karein.",
                "tool_calls_executed": [],
                "turns": 0,
                "spoken_early": False
            }

        # Build initial conversation history
        conversation = []
        
        # Query live device time so Gemini accurately knows current clock time
        device_time = ""
        try:
            if hasattr(tool_executor, "orchestrator") and hasattr(tool_executor.orchestrator, "get_device_time"):
                device_time = tool_executor.orchestrator.get_device_time()
        except Exception:
            pass

        # First user message with context
        user_msg = f"Sir says: \"{user_text}\""
        ctx_parts = []
        if device_time:
            ctx_parts.append(f"current_device_time=\"{device_time}\"")
        if context:
            ctx_parts.append(f"screen_awake={context.get('screen_awake', True)}")
            ctx_parts.append(f"current_state={context.get('state', 'idle')}")
        if ctx_parts:
            user_msg = f"Device context: {', '.join(ctx_parts)}\n{user_msg}"
        
        conversation.append({
            "role": "user",
            "parts": [{"text": user_msg}]
        })

        all_tool_calls = []
        final_speech = ""
        spoken_early = False
        turns = 0

        for turn in range(MAX_AGENTIC_TURNS):
            turns = turn + 1
            print(f"\n[Agentic Loop Turn {turns}/{MAX_AGENTIC_TURNS}]")

            # Call Gemini
            response = self._call_gemini(conversation)
            if response is None:
                final_speech = "Sir, Gemini se response nahi aa raha. Network check karein."
                break

            speech_text = ""
            tool_calls = []

            for part in response.get("parts", []):
                if "text" in part:
                    speech_text += part["text"]
                if "functionCall" in part:
                    fc = part["functionCall"]
                    tool_calls.append({
                        "name": fc.get("name"),
                        "args": fc.get("args", {})
                    })

            # Add model response to conversation
            conversation.append({
                "role": "model",
                "parts": response.get("parts", [])
            })

            # Instant voice feedback: Speak to Sir immediately on first turn
            if speech_text:
                final_speech = speech_text.strip()
                if on_speech and not spoken_early:
                    print(f"[Agentic Loop] Instant Voice Response: \"{final_speech}\"")
                    on_speech(final_speech)
                    spoken_early = True

            # If no tool calls in this turn:
            if not tool_calls:
                # Check if user asked for an action (songs, calls, messages, photos, apps, etc.)
                action_keywords = [
                    "chala", "chla", "song", "gaana", "gana", "play", "sunao", "suno", "laga", "lga",
                    "bhejo", "msg", "message", "call", "phone", "photo", "camera", "click",
                    "timer", "alarm", "brightness", "download", "install", "open", "khol",
                    "dnd", "silent", "disturb", "so raha", "sone", "mood", "notes", "save", "video call"
                ]
                user_wants_action = any(k in user_text.lower() for k in action_keywords)
                if user_wants_action and turn == 0:
                    print(f"[Agentic Loop] Pure speech generated on action request. Nudging model to execute action tool immediately...")
                    conversation.append({
                        "role": "user",
                        "parts": [{"text": "System instruction: Sir requested an action. You gave polite speech, now call the appropriate tool function call immediately to execute Sir's request."}]
                    })
                    continue
                else:
                    print(f"[Agentic Loop] Pure speech response. Done.")
                    break

            # Execute tool calls
            function_responses = []
            for tc in tool_calls:
                name = tc["name"]
                args = tc["args"]
                
                print(f"[Agentic Loop] Tool: {name}({json.dumps(args, ensure_ascii=False)})")

                # Check for terminal tools
                if name == "task_complete":
                    summary = args.get("summary", "Task done.")
                    if not final_speech:
                        final_speech = self._generate_speech(name, args)
                    print(f"[Agentic Loop] TASK COMPLETE: {summary}")
                    all_tool_calls.append(tc)
                    return {
                        "speech_text": final_speech,
                        "tool_calls_executed": all_tool_calls,
                        "turns": turns,
                        "spoken_early": spoken_early
                    }

                if name == "task_failed":
                    reason = args.get("reason", "Task could not be completed.")
                    if not final_speech:
                        final_speech = f"Sir, task complete nahi ho paya: {reason}"
                    print(f"[Agentic Loop] TASK FAILED: {reason}")
                    all_tool_calls.append(tc)
                    return {
                        "speech_text": final_speech,
                        "tool_calls_executed": all_tool_calls,
                        "turns": turns,
                        "spoken_early": spoken_early
                    }

                # Execute the tool
                result = tool_executor.execute_tool_call(name, args)
                all_tool_calls.append(tc)

                # Standard Google function response
                function_responses.append({
                    "functionResponse": {
                        "name": name,
                        "response": {
                            "output": result
                        }
                    }
                })

            if function_responses:
                conversation.append({
                    "role": "user",
                    "parts": function_responses
                })

        # If we exhausted max turns
        if not final_speech:
            final_speech = "Ji Sir, task perform kar diya hai."

        return {
            "speech_text": final_speech,
            "tool_calls_executed": all_tool_calls,
            "turns": turns,
            "spoken_early": spoken_early
        }

    def _call_gemini(self, conversation: list) -> dict:
        """
        Makes a single Gemini API call with the full conversation history.
        Returns the model's response content or None on failure.
        """
        for model in self.fallback_models:
            try:
                url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={self.api_key}"
                payload = {
                    "system_instruction": {"parts": [{"text": SYSTEM_INSTRUCTION.strip()}]},
                    "contents": conversation,
                    "tools": [{"function_declarations": MOBILE_TOOL_DECLARATIONS}],
                    "generation_config": {
                        "temperature": 0.1,
                        "max_output_tokens": 200
                    }
                }

                headers = {
                    "Content-Type": "application/json",
                    "x-goog-api-key": self.api_key
                }

                req = urllib.request.Request(
                    url,
                    data=json.dumps(payload).encode("utf-8"),
                    headers=headers
                )

                with urllib.request.urlopen(req, timeout=25) as resp:
                    data = json.loads(resp.read().decode("utf-8"))

                candidate = data.get("candidates", [{}])[0]
                content = candidate.get("content", {})
                
                if content and content.get("parts"):
                    self.active_model = model
                    return content

            except urllib.error.HTTPError as e:
                err_body = ""
                try:
                    err_body = e.read().decode("utf-8")[:200]
                except Exception:
                    pass
                print(f"[Gemini Brain] {model} HTTP {e.code}: {err_body}")
                continue
            except Exception as e:
                print(f"[Gemini Brain] {model} error: {e}")
                continue

        return None

    def locate_element_with_vision(self, image_b64: str, target_desc: str, screen_width: int = 1080, screen_height: int = 2400) -> dict:
        """
        Multimodal Visual Grounding via Gemini Vision.
        Examines the live screenshot to dynamically locate a target element.
        Returns: {'x': int, 'y': int, 'element_name': str, 'confidence': float} or None
        """
        if not self.is_connected or not image_b64:
            return None

        prompt = f"""
You are J.A.R.V.I.S., analyzing Sir's Android phone screen ({screen_width}x{screen_height} pixels).
Find: "{target_desc}"

RULES:
- In video apps (YouTube): Differentiate between channel headers and playable video thumbnails. Pick the video thumbnail.
- In social apps (Instagram): Differentiate between story bubbles at top and navigation icons at bottom (Reels = clapperboard icon).
- In chat apps (WhatsApp): Differentiate between menu icons, search icons, and call buttons.
- Calculate the exact center pixel coordinates (x, y) of the target.

Return ONLY valid JSON:
{{
  "element_name": "<description>",
  "x": <int>,
  "y": <int>,
  "confidence": <float 0.0-1.0>
}}
"""
        for model in self.fallback_models:
            try:
                url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={self.api_key}"
                payload = {
                    "contents": [{
                        "parts": [
                            {"text": prompt.strip()},
                            {
                                "inline_data": {
                                    "mime_type": "image/png",
                                    "data": image_b64
                                }
                            }
                        ]
                    }],
                    "generation_config": {
                        "temperature": 0.1,
                        "response_mime_type": "application/json"
                    }
                }

                headers = {
                    "Content-Type": "application/json",
                    "x-goog-api-key": self.api_key
                }

                req = urllib.request.Request(
                    url,
                    data=json.dumps(payload).encode("utf-8"),
                    headers=headers
                )

                with urllib.request.urlopen(req, timeout=12) as resp:
                    data = json.loads(resp.read().decode("utf-8"))

                candidate = data.get("candidates", [{}])[0]
                text = candidate.get("content", {}).get("parts", [{}])[0].get("text", "")
                if text:
                    parsed = json.loads(text)
                    if "x" in parsed and "y" in parsed:
                        print(f"[Gemini Vision] Found '{parsed.get('element_name')}' at ({parsed['x']}, {parsed['y']}) [conf: {parsed.get('confidence', 1.0)}]")
                        return parsed
            except Exception:
                continue

        return None

    def _generate_speech(self, tool_name: str, args: dict) -> str:
        """Generates crisp natural butler speech for a given tool call."""
        if tool_name == "task_complete":
            return args.get("summary", "Ji Sir, ho gaya.")
        elif tool_name == "task_failed":
            return f"Sir, {args.get('reason', 'task complete nahi ho paya.')}."
        elif tool_name in ("set_dnd", "enable_dnd", "silent_mode"):
            return "Do Not Disturb mode activate kar diya hai Sir. Koi disturbance nahi hoga." if args.get("enabled", True) else "Do Not Disturb mode deactivate kar diya hai Sir."
        elif tool_name in ("open_app", "open_any_app"):
            return f"{args.get('app_name', 'App')} open kar diya hai Sir."
        elif tool_name == "open_chat_whatsapp":
            return f"WhatsApp par {args.get('recipient', 'contact')} ki chat open kar raha hoon Sir."
        elif tool_name == "send_whatsapp_message":
            return f"WhatsApp par {args.get('recipient', 'contact')} ko message bhej diya Sir."
        elif tool_name == "save_contact":
            return f"{args.get('name', 'Contact')} ko contacts memory me save kar liya hai Sir."
        elif tool_name == "play_youtube":
            return f"YouTube par {args.get('query', 'video')} play kar raha hoon Sir."
        elif tool_name in ("send_sms", "send_text_message"):
            return f"{args.get('recipient', 'contact')} ko SMS bhej diya hai Sir."
        elif tool_name in ("set_alarm", "create_alarm"):
            return f"{args.get('hour', 6):02d}:{args.get('minute', 0):02d} ka alarm laga diya hai Sir."
        elif tool_name in ("set_timer", "create_timer"):
            s = args.get('seconds', 60)
            m = s // 60
            return f"{m} minute ka timer laga diya hai Sir." if m else f"{s} second ka timer laga diya hai Sir."
        elif tool_name in ("set_brightness", "change_brightness"):
            return f"Brightness {args.get('level', 'adjust')} kar di hai Sir."
        elif tool_name in ("schedule_delayed_action", "delayed_action"):
            m = args.get('delay_seconds', 60) // 60
            return f"{m} minute baad {args.get('tool_name', 'action')} execute ho jayega Sir."
        elif tool_name in ("take_camera_photo", "capture_photo", "click_photo"):
            return "Photo capture kar li hai Sir."
        elif tool_name in ("search_playstore", "install_app"):
            return f"Play Store par {args.get('query', 'app')} search kar diya hai Sir."
        elif tool_name in ("open_instagram_reels", "play_reels"):
            return "Instagram Reels shuru kar di hai Sir."
        elif tool_name == "make_phone_call":
            return f"{args.get('phone_number', 'contact')} ko call laga raha hoon Sir."
        elif tool_name == "end_active_call":
            return "Call disconnect kar di Sir."
        elif tool_name == "answer_incoming_call":
            return "Call utha li Sir."
        elif tool_name == "wake_and_unlock_phone":
            return "Phone unlock kar diya Sir."
        return "Ji Sir, bilkul."

    # Legacy single-shot method (kept for backward compatibility during transition)
    def process_natural_input(self, user_speech_text: str, context: dict = None) -> dict:
        """Legacy single-shot method. Use run_agentic_loop() instead."""
        context = context or {}
        if not self.is_connected:
            return {
                "speech_text": "Sir, secrets.env me valid GEMINI_API_KEY add karein.",
                "tool_calls": [],
                "model_used": "none"
            }

        conversation = [{
            "role": "user",
            "parts": [{"text": f"Context: {context}\nSir says: \"{user_speech_text}\""}]
        }]

        response = self._call_gemini(conversation)
        if response is None:
            return {
                "speech_text": "Sir, Gemini cloud network me temporary issue aa raha hai.",
                "tool_calls": [],
                "model_used": "none"
            }

        speech_text = ""
        tool_calls = []
        for part in response.get("parts", []):
            if "text" in part:
                speech_text += part["text"]
            if "functionCall" in part:
                fc = part["functionCall"]
                tool_calls.append({
                    "name": fc.get("name"),
                    "args": fc.get("args", {})
                })

        if not speech_text and tool_calls:
            speech_text = self._generate_speech(tool_calls[0]["name"], tool_calls[0].get("args", {}))

        return {
            "speech_text": speech_text.strip(),
            "tool_calls": tool_calls,
            "model_used": self.active_model
        }
