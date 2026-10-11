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
        "description": "Call when user wants to be reminded of something or follow up on a task/call at a specific time or in a few seconds/minutes.",
        "parameters": {
            "type": "object",
            "properties": {
                "target_time": {"type": "string", "description": "Time in HH:MM (24-hour format)."},
                "delay_seconds": {"type": "integer", "description": "Relative delay in seconds (e.g. 10 for 10 seconds)."},
                "delay_minutes": {"type": "integer", "description": "Relative delay in minutes (e.g. 5 for 5 minutes)."},
                "topic_or_person": {"type": "string", "description": "Name of person or subject of the task."},
                "proactive_speech": {"type": "string", "description": "The exact polite prompt Jarvis should say aloud when the time arrives."}
            },
            "required": ["topic_or_person", "proactive_speech"]
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
                "action": {"type": "string", "enum": ["mute", "unmute", "set_volume", "volume_up", "volume_down", "lock_screen", "check_battery"]},
                "value": {"type": "integer", "description": "Optional level (e.g. volume 0-100)."}
            },
            "required": ["action"]
        }
    },
    {
        "name": "browser_and_web_action",
        "description": "Universal browser and web controller: open URLs, search Google, search/play YouTube, switch tabs, close tabs, refresh, or scroll.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": [
                        "open_url", "search_google", "search_youtube", "play_youtube",
                        "new_tab", "close_tab", "next_tab", "prev_tab", "reopen_tab",
                        "refresh", "scroll_down", "scroll_up"
                    ],
                    "description": "The browser or web action to execute."
                },
                "query": {"type": "string", "description": "Search query or target URL if applicable."}
            },
            "required": ["action"]
        }
    },
    {
        "name": "window_and_system_action",
        "description": "Control Windows desktop windows and media playback: maximize, minimize, restore, close window, show desktop, switch window, media play/pause, next track, prev track, volume up, or volume down.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": [
                        "maximize", "minimize", "restore", "close_window",
                        "show_desktop", "switch_window",
                        "media_play_pause", "media_next", "media_prev", "media_stop",
                        "volume_up", "volume_down"
                    ],
                    "description": "Window or media action to execute."
                },
                "value": {"type": "integer", "description": "Optional step value (e.g. 10 for volume change)."}
            },
            "required": ["action"]
        }
    },
    {
        "name": "universal_communicate",
        "description": "Universal communication tool: call or send a message to any person on ANY application (Teams, WhatsApp, Telegram, Discord, Slack, etc.) by contact name. Can be executed immediately or scheduled for a specific time/delay.",
        "parameters": {
            "type": "object",
            "properties": {
                "app_name": {"type": "string", "description": "The application to use (e.g. 'Teams', 'WhatsApp', 'Telegram', 'Slack')."},
                "action": {"type": "string", "enum": ["call", "message", "open"]},
                "contact_name": {"type": "string", "description": "Name of the person (e.g. 'Dhruv', 'Vishal', 'Manish')."},
                "message": {"type": "string", "description": "Message content if sending a message."},
                "call_type": {"type": "string", "enum": ["audio", "video"], "description": "Type of call ('audio' or 'video')."},
                "schedule_time": {"type": "string", "description": "Time in HH:MM (e.g. '02:42', '14:30') if the communication should be sent at a future scheduled time instead of immediately."},
                "delay_minutes": {"type": "integer", "description": "Relative delay in minutes (e.g. 2 for 2 minutes later) if specified."}
            },
            "required": ["app_name", "action", "contact_name"]
        }
    },
    {
        "name": "universal_computer_action",
        "description": "Perform any general autonomous computer-use action on the PC: open any app/URL, click any button by name (e.g. 'Accept All', 'Accept', 'Proceed', 'Keep', 'Save', 'Submit', 'Run'), type text, or press keyboard shortcuts. Always use this when user asks to accept task changes, click a button in any app, or interact with GUI controls.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["open_app_or_url", "click_button", "type_text", "press_keys"]},
                "target": {"type": "string", "description": "Button name (e.g. 'Accept All', 'Accept', 'Proceed', 'Save'), app name, website URL, or key combination to press."},
                "text": {"type": "string", "description": "Text to type if action is 'type_text'."},
                "app_name": {"type": "string", "description": "Optional application name to bring to foreground first (e.g. 'Antigravity', 'Chrome', 'VS Code')."}
            },
            "required": ["action", "target"]
        }
    },
    {
        "name": "update_personal_knowledge",
        "description": "Store or update facts about family, friends, colleagues, relationships, phone numbers, or user habits in the Knowledge Graph.",
        "parameters": {
            "type": "object",
            "properties": {
                "person_name": {"type": "string", "description": "Full name or primary identifier of the person."},
                "relationship": {"type": "string", "description": "Relationship to Sir (e.g. 'Friend', 'Manager', 'Colleague', 'Father', 'Brother', 'Family')."},
                "phone": {"type": "string", "description": "Phone number, email, or communication handle."},
                "aliases": {"type": "string", "description": "Nicknames or alternative names (e.g. 'bhai', 'lead', 'boss')."},
                "importance": {"type": "integer", "description": "Importance level: 1 = Normal, 2 = High Priority, 3 = VIP / Critical Family Member."},
                "notes": {"type": "string", "description": "Habits, preferences, role details, or life facts."}
            },
            "required": ["person_name"]
        }
    },
    {
        "name": "query_personal_knowledge",
        "description": "Query facts, relationships, or contact information about a person or role from the Knowledge Graph (e.g. 'Who is Dhruv?', 'Who is my manager?', 'Tell me about Manish').",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Name, role, or relationship to look up."}
            },
            "required": ["query"]
        }
    },
    {
        "name": "list_personal_knowledge",
        "description": "List all people, relationships, and contacts stored in JARVIS's memory graph.",
        "parameters": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "resume_or_wake_up",
        "description": "Call when user wakes up, finishes resting, or requests to resume normal mode (e.g. 'mai jag gaya', 'wake up', 'uth gaya', 'resume normal mode', 'stop rest mode').",
        "parameters": {
            "type": "object",
            "properties": {
                "user_confirmation": {"type": "string", "description": "User statement (e.g. 'I am awake', 'mai jag gaya')."}
            }
        }
    },
    {
        "name": "review_quarantined_disturbances",
        "description": "Call when user asks to review, inspect, or see messages and calls held in quarantine while they were resting or in DND mode.",
        "parameters": {
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "description": "Number of recent quarantined items to retrieve."}
            }
        }
    },
    {
        "name": "inspect_inbox_or_emails",
        "description": "Inspect, read, and check visible emails from Gmail or the active email client. Call whenever Sir asks to check emails, see if an email arrived from someone, or ask what emails are in the inbox.",
        "parameters": {
            "type": "object",
            "properties": {
                "sender_filter": {"type": "string", "description": "Optional sender name, topic, or keyword to check for."},
                "open_if_closed": {"type": "boolean", "description": "Whether to open Gmail if not already visible (default true)."}
            }
        }
    },
    {
        "name": "inspect_screen_or_window_content",
        "description": "Perceive and extract visible text, items, and UI hierarchy from any open application or window on screen. Use for inspecting any screen content without hardcoded filters.",
        "parameters": {
            "type": "object",
            "properties": {
                "target_app_or_url": {"type": "string", "description": "Application name, window title, or URL (e.g. 'chrome', 'teams', 'gmail', 'antigravity', or leave empty for active window)."},
                "query": {"type": "string", "description": "What Sir wants to verify or check on screen."}
            }
        }
    },
    {
        "name": "activate_detective_mode",
        "description": "Activate Detective Mode to monitor, track, and record every single window switch, app launch, file opened, and touch on the laptop (e.g. 'detective mode on', 'activate detective mode', 'sentry mode on').",
        "parameters": {
            "type": "object",
            "properties": {
                "custom_note": {"type": "string", "description": "Optional context or reason."}
            }
        }
    },
    {
        "name": "deactivate_detective_mode",
        "description": "Deactivate Detective Mode and return to normal operation (e.g. 'detective mode off', 'stop detective mode').",
        "parameters": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "get_laptop_activity_timeline",
        "description": "Inspect and retrieve the real chronological timeline of applications, windows, files, folders, and web pages opened or used on Sir's laptop in the past N minutes (e.g. 'kya hua tha', 'kya kya open hua tha', 'pichhle 2 minute mein kya open hua', 'kisne chhua tha', 'mere piche se kya hua'). If Detective Mode is active, automatically presents the full debrief and turns Detective Mode OFF.",
        "parameters": {
            "type": "object",
            "properties": {
                "minutes": {
                    "type": "integer",
                    "description": "How many minutes into the past to check (default 5 minutes, e.g. 2 for last 2 minutes)."
                },
                "query": {
                    "type": "string",
                    "description": "Optional specific question or activity Sir asked about."
                }
            }
        }
    },
    {
        "name": "monitor_incoming_messages",
        "description": "Continuously monitor and check for incoming messages from ANY contact or on ANY communication application (e.g. WhatsApp, Teams, Telegram, Slack, etc.) in the background, and proactively alert Sir the moment a new message arrives.",
        "parameters": {
            "type": "object",
            "properties": {
                "contact_name": {"type": "string", "description": "The person or contact to monitor (any name specified by Sir)."},
                "app_name": {"type": "string", "description": "Application name to check (e.g. 'WhatsApp', 'Teams', 'Telegram', 'Slack', etc., default 'WhatsApp')."},
                "instruction": {"type": "string", "description": "User instruction or intent."}
            },
            "required": ["contact_name"]
        }
    }
]

