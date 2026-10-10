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
        - For calling or messaging someone on ANY application (Teams, WhatsApp, Telegram, Discord, Slack, etc.):
          Call `universal_communicate(app_name="...", action="call"|"message", contact_name="...", message="...", call_type="audio"|"video")`.
        - For general OS/computer tasks (opening websites/apps, clicking buttons, typing text):
          Call `universal_computer_action(action="open_app_or_url"|"click_button"|"type_text"|"press_keys", target="...", text="...")`.
        - For hardware, volume, lock, battery: Call `control_hardware_and_audio`.
        - For sleep, rest, DND: Call `activate_rest_or_dnd_mode`.
        - For proactive time-based reminders: Call `schedule_proactive_reminder`.
        - For saving memory/relationships: Call `update_personal_knowledge`.

        Respond verbally with utmost courtesy, elegance, and brevity.
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

        return {
            "speech_text": speech,
            "tool_calls": tool_calls,
            "model_used": "simulation"
        }
