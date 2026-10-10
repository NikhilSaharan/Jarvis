"""
Universal Function Calling Tool Declarations for Google Gemini 3.1 Flash Lite.
Autonomous tools operating purely on Person Names and Natural Intent.
"""

JARVIS_TOOL_DECLARATIONS = [
    {
        "name": "activate_rest_or_dnd_mode",
        "description": "Call whenever the user wants to sleep, rest, take a nap, focus, or avoid disturbances (in any words).",
        "parameters": {
            "type": "object",
            "properties": {
                "wake_time": {"type": "string", "description": "Target wake-up time in HH:MM (24-hour format) if specified."},
                "duration_minutes": {"type": "integer", "description": "Relative duration in minutes if specified (e.g., 60 for 1 hour)."},
                "block_all_disturbances": {"type": "boolean", "description": "True to mute laptop and hold calls/messages."},
                "custom_note": {"type": "string", "description": "Reason or context (e.g., 'headache', 'sleeping', 'coding')."}
            },
            "required": ["block_all_disturbances"]
        }
    },
    {
        "name": "schedule_proactive_reminder",
        "description": "Call when user wants to be reminded of something or follow up on a task/call at a specific time.",
        "parameters": {
            "type": "object",
            "properties": {
                "target_time": {"type": "string", "description": "Time in HH:MM (24-hour format)."},
                "topic_or_person": {"type": "string", "description": "Name of person or subject of the task."},
                "proactive_speech": {"type": "string", "description": "The exact polite prompt Jarvis should say aloud when the time arrives."}
            },
            "required": ["target_time", "topic_or_person", "proactive_speech"]
        }
    },
    {
        "name": "control_laptop_application",
        "description": "Launch, close, or focus any Windows software (Spotify, Chrome, VS Code, Teams, etc.).",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["launch", "close", "focus"]},
                "app_name": {"type": "string", "description": "Executable or application name."}
            },
            "required": ["action", "app_name"]
        }
    },
    {
        "name": "control_hardware_and_audio",
        "description": "Control volume, mute/unmute, lock laptop, or inspect battery.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["mute", "unmute", "set_volume", "lock_screen", "check_battery"]},
                "value": {"type": "integer", "description": "Optional level (e.g. volume 0-100)."}
            },
            "required": ["action"]
        }
    },
    {
        "name": "universal_communicate",
        "description": "Universal communication tool: call or send a message to any person on ANY application (Teams, WhatsApp, Telegram, Discord, Slack, etc.) by contact name.",
        "parameters": {
            "type": "object",
            "properties": {
                "app_name": {"type": "string", "description": "The application to use (e.g. 'Teams', 'WhatsApp', 'Telegram', 'Slack')."},
                "action": {"type": "string", "enum": ["call", "message", "open"]},
                "contact_name": {"type": "string", "description": "Name of the person (e.g. 'Dhruv', 'Vishal', 'Manish')."},
                "message": {"type": "string", "description": "Message content if sending a message."},
                "call_type": {"type": "string", "enum": ["audio", "video"], "description": "Type of call ('audio' or 'video')."}
            },
            "required": ["app_name", "action", "contact_name"]
        }
    },
    {
        "name": "universal_computer_action",
        "description": "Perform any general autonomous computer-use action on the PC: open any app/URL, click any button by name, or type text.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["open_app_or_url", "click_button", "type_text", "press_keys"]},
                "target": {"type": "string", "description": "App name, website URL, button name, or key combination to press."},
                "text": {"type": "string", "description": "Text to type if action is 'type_text'."}
            },
            "required": ["action", "target"]
        }
    },
    {
        "name": "update_personal_knowledge",
        "description": "Store or update facts about family, friends, relationships, or user habits.",
        "parameters": {
            "type": "object",
            "properties": {
                "person_name": {"type": "string"},
                "relationship": {"type": "string"},
                "notes": {"type": "string"}
            },
            "required": ["person_name"]
        }
    }
]
