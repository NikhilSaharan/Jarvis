"""
Universal Android OS Tool Declarations for J.A.R.V.I.S. Agentic Loop.
Clean 2-Layer Architecture:
Layer 1: Universal Android OS Primitives (Instant, Zero GUI)
Layer 2: Universal Visual/UI Agent (Tencent AppAgent Set-of-Marks)
"""

MOBILE_TOOL_DECLARATIONS = [
    # ═════════════════════════════════════════════════════════════════
    # LAYER 1: UNIVERSAL ANDROID OS PRIMITIVES (Instant, Zero GUI)
    # ═════════════════════════════════════════════════════════════════
    {
        "name": "set_dnd",
        "description": "Enables or disables Do Not Disturb (DND) / Silent Mode natively via Android OS in 0.05s. Use whenever Sir says 'so raha hoon', 'disturbance nahi chahiye', 'dnd on karo', 'silent karo', 'phone silent par dalo', 'mute karo'. NEVER open Settings app for DND!",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "enabled": {
                    "type": "BOOLEAN",
                    "description": "True to enable DND/silent mode, False to restore normal ringer (default True)."
                }
            }
        }
    },
    {
        "name": "set_brightness",
        "description": "Sets Android screen brightness directly. Use whenever Sir says 'brightness kam karo', 'brightness badhao', 'brightness full karo', 'brightness low/dim'. NEVER use volume keys for brightness!",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "level": {
                    "type": "STRING",
                    "description": "Brightness level: 'low', 'medium', 'high', 'max', 'min', or number 0-255."
                }
            },
            "required": ["level"]
        }
    },
    {
        "name": "set_alarm",
        "description": "Sets a hardware system alarm directly via Android OS in 0.1s. Use for fixed clock times (e.g. '6pm ka alarm lagao', 'subah 7 baje ka alarm'). Supports hour (0-23) and minute (0-59).",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "hour": {
                    "type": "INTEGER",
                    "description": "Hour in 24-hour format (0-23). E.g. 6pm is 18, 7am is 7."
                },
                "minute": {
                    "type": "INTEGER",
                    "description": "Minute (0-59). Default is 0."
                },
                "message": {
                    "type": "STRING",
                    "description": "Optional label for the alarm."
                }
            },
            "required": ["hour"]
        }
    },
    {
        "name": "set_timer",
        "description": "Sets a countdown timer directly via Android OS in 0.1s. Use whenever Sir asks for a relative countdown (e.g. '2 min baad jagaa dena', '5 minute ka timer lagao', '10 min baad alert karo'). Pass duration in seconds (e.g. 2 minutes = 120).",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "seconds": {
                    "type": "INTEGER",
                    "description": "Duration in seconds (e.g. 120 for 2 minutes, 300 for 5 minutes)."
                },
                "message": {
                    "type": "STRING",
                    "description": "Optional label for the timer."
                }
            },
            "required": ["seconds"]
        }
    },
    {
        "name": "make_phone_call",
        "description": "Places a direct cellular SIM phone call. Pass a contact name (e.g. 'Anuj', 'Abhh', 'Papa', 'Mom') or phone number. Contact names are resolved automatically from the phone address book or SQLite DB.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "phone_number": {
                    "type": "STRING",
                    "description": "Contact name or phone number to call."
                }
            },
            "required": ["phone_number"]
        }
    },
    {
        "name": "send_sms",
        "description": "Sends a cellular text SMS to a contact or phone number. Use whenever Sir says 'text message karo', 'sms bhejo', 'normal message bhejo'. This is cellular SMS, NOT WhatsApp.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "recipient": {
                    "type": "STRING",
                    "description": "Contact name (e.g. 'Manish', 'Abhh') or phone number."
                },
                "message": {
                    "type": "STRING",
                    "description": "The SMS text to send."
                }
            },
            "required": ["recipient", "message"]
        }
    },
    {
        "name": "send_whatsapp_message",
        "description": "Sends a WhatsApp text message to a contact in <1.5s. Opens chat, types message, and taps Send automatically. Use whenever Sir asks to send a WhatsApp message (e.g. 'whatsapp par Abhh ko hello bhej do').",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "recipient": {
                    "type": "STRING",
                    "description": "Contact name (e.g. 'Abhh', 'Manish') or phone number."
                },
                "message": {
                    "type": "STRING",
                    "description": "The text message content to send."
                }
            },
            "required": ["recipient", "message"]
        }
    },
    {
        "name": "save_contact",
        "description": "Saves or updates a contact or VIP entity in J.A.R.V.I.S.'s SQLite memory. Use when Sir says 'Mummy ka number save karo', 'Abhh mera dost hai', etc.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "name": {
                    "type": "STRING",
                    "description": "Contact or person name (e.g. 'Mummy', 'Abhh')."
                },
                "relationship": {
                    "type": "STRING",
                    "description": "Relationship: 'Mummy', 'Papa', 'Friend', 'VIP', 'Urgent'."
                },
                "phone": {
                    "type": "STRING",
                    "description": "Phone number."
                },
                "notes": {
                    "type": "STRING",
                    "description": "Additional facts or notes."
                }
            },
            "required": ["name"]
        }
    },
    {
        "name": "take_camera_photo",
        "description": "Launches the camera hardware and captures a photo via hardware shutter key in 1 second. Use whenever Sir says 'camera open karke photo click karo', 'photo lo', 'picture khincho'.",
        "parameters": {
            "type": "OBJECT",
            "properties": {}
        }
    },
    {
        "name": "play_youtube",
        "description": "Plays any song, music, or video on YouTube in 0.3s. Direct video player launch (ZERO ads, ZERO shorts). Use whenever Sir asks for music or says 'gaane chalao', 'purane gaane', 'song bajao', or mentions bad mood.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "query": {
                    "type": "STRING",
                    "description": "Search topic / song title (e.g. 'sad songs hindi', 'Arijit Singh songs', 'relaxing music')."
                }
            },
            "required": ["query"]
        }
    },
    {
        "name": "schedule_delayed_action",
        "description": "Schedules a tool to execute automatically after a delay (e.g. '1 min baad call laga dena', '10 min baad message bhejo'). Runs via background thread.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "delay_seconds": {
                    "type": "INTEGER",
                    "description": "Delay in seconds before execution (e.g. 60 for 1 minute, 300 for 5 minutes)."
                },
                "tool_name": {
                    "type": "STRING",
                    "description": "The tool to call (e.g. 'make_phone_call', 'open_chat_whatsapp', 'set_alarm')."
                },
                "tool_args": {
                    "type": "OBJECT",
                    "description": "Arguments for that tool."
                }
            },
            "required": ["delay_seconds", "tool_name"]
        }
    },
    {
        "name": "open_instagram_reels",
        "description": "Launches Instagram Reels directly in full-screen player.",
        "parameters": {
            "type": "OBJECT",
            "properties": {}
        }
    },
    {
        "name": "search_playstore",
        "description": "Searches for an app on Google Play Store directly.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "query": {
                    "type": "STRING",
                    "description": "Name of the app to search for (e.g. 'Hill Climb Racing')."
                }
            },
            "required": ["query"]
        }
    },

    # ═════════════════════════════════════════════════════════════════
    # LAYER 2: UNIVERSAL HUMAN-EQUIVALENT UI AGENT (Set-of-Marks)
    # ═════════════════════════════════════════════════════════════════
    {
        "name": "open_app",
        "description": "Launches ANY application installed on Sir's phone by name. Examples: 'Notes', 'WhatsApp', 'Instagram', 'Settings', 'Chrome', 'Spotify', 'Play Store', 'Gallery'. Returns live screen tags.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "app_name": {
                    "type": "STRING",
                    "description": "Name of the app to launch (e.g. 'Notes', 'WhatsApp', 'Instagram')."
                }
            },
            "required": ["app_name"]
        }
    },
    {
        "name": "open_chat_whatsapp",
        "description": "Directly opens the WhatsApp chat conversation with a specific contact (e.g. 'Abhh', 'Manish'). Returns the live screen tags inside the chat (e.g. Video call, Voice call, Message input) so you can tap them in the next turn.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "recipient": {
                    "type": "STRING",
                    "description": "Contact name (e.g. 'Abhh') or phone number."
                }
            },
            "required": ["recipient"]
        }
    },
    {
        "name": "tap",
        "description": "Taps an element on screen. PREFER using `tag` (the integer number like [1], [2], [3] from screen_tags). You can also use `target` (visible text/label) or `x, y` coordinates.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "tag": {
                    "type": "INTEGER",
                    "description": "The numeric tag [1], [2], [3] of the element from screen_tags."
                },
                "target": {
                    "type": "STRING",
                    "description": "Visible text or label of the element to tap (if tag is not used)."
                },
                "x": {
                    "type": "INTEGER",
                    "description": "Optional pixel X coordinate."
                },
                "y": {
                    "type": "INTEGER",
                    "description": "Optional pixel Y coordinate."
                }
            }
        }
    },
    {
        "name": "type_text",
        "description": "Types text into an input field. Pass `tag` to automatically tap and focus that input field first, then type.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "text": {
                    "type": "STRING",
                    "description": "The text to type."
                },
                "tag": {
                    "type": "INTEGER",
                    "description": "Optional tag number of the input field [INPUT] to tap and focus before typing."
                },
                "press_enter": {
                    "type": "BOOLEAN",
                    "description": "Press Enter after typing (default False)."
                }
            },
            "required": ["text"]
        }
    },
    {
        "name": "swipe",
        "description": "Swipes on screen. 'up' scrolls down / advances Reels. 'down' scrolls up. 'left'/'right' for tabs.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "direction": {
                    "type": "STRING",
                    "description": "'up', 'down', 'left', or 'right'."
                },
                "count": {
                    "type": "INTEGER",
                    "description": "Number of swipes (default 1)."
                }
            }
        }
    },
    {
        "name": "press_key",
        "description": "Sends a hardware keycode: 'BACK' (go back/dismiss), 'HOME' (go to home screen), 'ENTER', 'RECENTS'.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "key_name": {
                    "type": "STRING",
                    "description": "'BACK', 'HOME', 'ENTER', 'RECENTS'."
                }
            },
            "required": ["key_name"]
        }
    },
    {
        "name": "wait_seconds",
        "description": "Pauses execution for UI animations or network loads. Use 0.5 to 2.0 seconds.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "seconds": {
                    "type": "NUMBER",
                    "description": "Seconds to wait (e.g. 1.0)."
                }
            },
            "required": ["seconds"]
        }
    },
    {
        "name": "get_screen_state",
        "description": "Reads all visible interactive UI elements currently on screen and returns them with numeric tags ([1], [2], ...). Call this if you need to inspect the screen before taking action.",
        "parameters": {
            "type": "OBJECT",
            "properties": {}
        }
    },
    {
        "name": "task_complete",
        "description": "Call this when Sir's requested task is fully accomplished and no more actions are needed. Ends the agentic loop.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "summary": {
                    "type": "STRING",
                    "description": "Brief summary of what was accomplished."
                }
            },
            "required": ["summary"]
        }
    },
    {
        "name": "task_failed",
        "description": "Call this when the task cannot be completed (e.g. contact not found, network error). Ends the agentic loop.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "reason": {
                    "type": "STRING",
                    "description": "Why the task failed."
                }
            },
            "required": ["reason"]
        }
    }
]
