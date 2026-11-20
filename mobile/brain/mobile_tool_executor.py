"""
Mobile Tool Executor for J.A.R.V.I.S. Agentic Loop.
Dispatches tool calls from Gemini to physical Android actions.
Each tool returns structured results that get fed back to Gemini
in the next turn of the agentic loop.
"""
import json
import urllib.parse
from core.state_machine import JarvisState, SystemState
from memory.database import KnowledgeDatabase


class MobileToolExecutor:
    def __init__(
        self,
        android_controller,
        lock_manager,
        screen_perception,
        app_orchestrator,
        telephony_manager,
        playstore_installer,
        database: KnowledgeDatabase,
        state: JarvisState,
        heartbeat=None
    ):
        self.ac = android_controller
        self.lock_mgr = lock_manager
        self.perception = screen_perception
        self.orchestrator = app_orchestrator
        self.telephony = telephony_manager
        self.playstore = playstore_installer
        self.db = database
        self.state = state
        self.heartbeat = heartbeat

    def set_heartbeat(self, heartbeat):
        self.heartbeat = heartbeat

    def execute_tool_call(self, tool_name: str, args: dict) -> dict:
        """Executes a single tool call and returns structured result for the agentic loop."""
        print(f"[Tool Executor] {tool_name}({json.dumps(args, ensure_ascii=False)})")
        try:
            res = None

            # ═════════════════════════════════════════════════════════
            # LAYER 1: UNIVERSAL ANDROID OS PRIMITIVES (Instant 0.05s)
            # ═════════════════════════════════════════════════════════

            # ─── Do Not Disturb (DND) / Silent Mode ───
            if tool_name in ("set_dnd", "enable_dnd", "do_not_disturb", "silent_mode"):
                enabled = args.get("enabled", True)
                if enabled:
                    self.state.transition_to(SystemState.SLEEPING)
                else:
                    self.state.transition_to(SystemState.NORMAL)
                return self.orchestrator.set_dnd(enabled=enabled)

            # ─── Screen Brightness ───
            elif tool_name in ("set_brightness", "change_brightness"):
                level = args.get("level", "medium")
                return self.orchestrator.set_brightness(level)

            # ─── Set Alarm ───
            elif tool_name in ("set_alarm", "create_alarm"):
                hour = args.get("hour", 6)
                minute = args.get("minute", 0)
                msg = args.get("message", "Alarm")
                return self.orchestrator.set_alarm(hour, minute, msg)

            # ─── Set Timer ───
            elif tool_name in ("set_timer", "create_timer"):
                seconds = args.get("seconds", 60)
                msg = args.get("message", "Timer")
                return self.orchestrator.set_timer(seconds, msg)

            # ─── Cellular Phone Calls (SIM) ───
            elif tool_name in ("make_phone_call", "call_contact", "dial_call"):
                target = args.get("phone_number") or args.get("recipient") or args.get("name", "")
                return self.orchestrator.make_phone_call(target)

            # ─── Cellular SMS ───
            elif tool_name in ("send_sms", "send_text_message"):
                recipient = args.get("recipient") or args.get("contact") or ""
                message = args.get("message") or args.get("text") or ""
                return self.orchestrator.send_sms(recipient, message)

            # ─── Direct WhatsApp Messaging (<1.5s) ───
            elif tool_name in ("send_whatsapp_message", "send_message"):
                recipient = args.get("recipient") or args.get("contact_name") or args.get("username") or ""
                message = args.get("message") or args.get("text") or ""
                return self.orchestrator.send_whatsapp_message(recipient, message)

            # ─── Save Contact to SQLite DB Memory ───
            elif tool_name in ("save_contact", "add_contact", "remember_contact"):
                name = args.get("name", "").strip()
                rel = args.get("relationship", "").strip()
                phone = args.get("phone", "").strip()
                notes = args.get("notes", "").strip()
                if name:
                    self.db.add_entity(name=name, relationship=rel, phone=phone, notes=notes)
                    return {"success": True, "message": f"{name} ko contacts memory me save kar liya hai Sir."}
                return {"success": False, "message": "Contact name missing."}

            # ─── YouTube Direct Video Playback (FROZEN & UNTOUCHED) ───
            elif tool_name in ("play_youtube", "play_video", "play_song"):
                query = args.get("query") or args.get("search_query") or ""
                return self.orchestrator.play_youtube(query)

            # ─── Take Camera Photo ───
            elif tool_name in ("take_camera_photo", "capture_photo", "click_photo"):
                return self.orchestrator.take_camera_photo()

            # ─── Schedule Delayed Action ───
            elif tool_name in ("schedule_delayed_action", "delayed_action"):
                delay_seconds = args.get("delay_seconds", 60)
                target_tool = args.get("tool_name", "")
                target_args = args.get("tool_args", {})
                return self.orchestrator.schedule_delayed_action(delay_seconds, target_tool, target_args)

            # ─── Instagram Reels Direct ───
            elif tool_name in ("open_instagram_reels", "play_reels"):
                return self.orchestrator.open_instagram_reels()

            # ─── Play Store App Search ───
            elif tool_name in ("search_playstore", "install_app", "download_app"):
                query = args.get("query") or args.get("app_name") or args.get("app") or ""
                return self.orchestrator.search_playstore(query)

            # ─── End Active Call ───
            elif tool_name == "end_active_call":
                return self.telephony.reject_or_cut_call()

            # ─── Answer Incoming Call ───
            elif tool_name == "answer_incoming_call":
                return self.telephony.answer_call()

            # ─── Wake & Unlock ───
            elif tool_name == "wake_and_unlock_phone":
                if self.lock_mgr.is_screen_on():
                    return {"success": True, "message": "Phone screen is already awake and unlocked."}
                pin = args.get("pin")
                return self.lock_mgr.wake_and_unlock(pin)

            # ─── Terminal: Task Complete / Failed ───
            elif tool_name in ("task_complete", "task_failed"):
                return {"success": True, "message": args.get("summary") or args.get("reason", "Done.")}

            # ═════════════════════════════════════════════════════════
            # LAYER 2: UNIVERSAL HUMAN-EQUIVALENT UI AGENT (Set-of-Marks)
            # ═════════════════════════════════════════════════════════

            # ─── App Launch ───
            elif tool_name in ("open_app", "open_any_app"):
                app = args.get("app_name") or args.get("app")
                res = self.orchestrator.open_app(app)

            # ─── WhatsApp Open Chat Conversation ───
            elif tool_name in ("open_chat_whatsapp", "open_whatsapp_chat"):
                recipient = args.get("recipient") or args.get("contact_name") or args.get("name")
                res = self.orchestrator.open_chat_whatsapp(recipient)

            # ─── Tap / Click by Tag or Label ───
            elif tool_name in ("tap", "click_screen_element"):
                tag = args.get("tag")
                target = args.get("target") or args.get("text_or_description")
                x = args.get("x")
                y = args.get("y")
                res = self.orchestrator.tap(tag=tag, target=target, x=x, y=y)

            # ─── Type Text into Tagged Field ───
            elif tool_name == "type_text":
                text = args.get("text", "")
                tag = args.get("tag")
                press_enter = args.get("press_enter", False)
                res = self.orchestrator.type_text(text, tag=tag, press_enter=press_enter)

            # ─── Swipe / Scroll ───
            elif tool_name in ("swipe", "scroll"):
                direction = args.get("direction", "up")
                count = args.get("count", 1)
                res = self.orchestrator.swipe(direction=direction, count=count)

            # ─── Hardware Keys ───
            elif tool_name == "press_key":
                key = args.get("key_name") or args.get("key", "BACK")
                res = self.orchestrator.press_key(key)

            # ─── Wait / Pause ───
            elif tool_name in ("wait_seconds", "wait"):
                secs = float(args.get("seconds", 1.0))
                res = self.orchestrator.wait(secs)

            # ─── Screen State (Read UI Elements) ───
            elif tool_name == "get_screen_state":
                return self.orchestrator.get_screen_state()

            else:
                return {"success": False, "message": f"Unknown tool '{tool_name}'"}

            # For Layer 2 interactive UI actions, attach live screen tags for the next turn
            if res is not None and isinstance(res, dict):
                try:
                    tags = self.perception.get_tagged_elements_prompt()
                    if tags:
                        res["screen_tags"] = tags
                except Exception:
                    pass
                return res

        except Exception as e:
            err_msg = f"Execution error in {tool_name}: {e}"
            print(f"[Tool Executor Error] {err_msg}")
            return {"success": False, "message": err_msg}
