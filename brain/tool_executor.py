"""
Tool Executor for J.A.R.V.I.S.
Maps Gemini 3.1 Flash Lite function calls to real Windows OS, Teams, WhatsApp, and Database actions.
"""
import datetime
from os_engine.windows_control import WindowsController
from os_engine.communications import CommunicationsController
from memory.database import KnowledgeDatabase
from core.state_machine import JarvisState

from os_engine.computer_use import ComputerUseEngine

def normalize_target_time(time_str: str, now: datetime.datetime = None) -> datetime.datetime:
    """
    Universally normalizes any human time string ('2:40', '14:40', '240', '02:42')
    to the closest upcoming datetime object, resolving 12h/24h AM/PM ambiguity dynamically.
    """
    if now is None:
        now = datetime.datetime.now()

    clean_t = str(time_str).strip().replace(":", "").replace(".", "")
    if len(clean_t) == 3:
        clean_t = "0" + clean_t
    if len(clean_t) != 4 or not clean_t.isdigit():
        return now + datetime.timedelta(minutes=2)

    hh = int(clean_t[:2])
    mm = int(clean_t[2:])

    cand1 = now.replace(hour=hh % 24, minute=mm, second=0, microsecond=0)
    cand2 = now.replace(hour=(hh - 12 if hh >= 12 else hh + 12), minute=mm, second=0, microsecond=0)

    candidates = []
    for c in [cand1, cand2]:
        diff = (c - now).total_seconds()
        if diff >= -60:
            candidates.append((diff, c))
        else:
            candidates.append((diff + 86400, c + datetime.timedelta(days=1)))

    candidates.sort(key=lambda x: x[0])
    return candidates[0][1]

class ToolExecutor:
    def __init__(self, win_ctrl: WindowsController, db: KnowledgeDatabase, state: JarvisState, brain=None, heartbeat=None):
        self.win_ctrl = win_ctrl
        self.comm_ctrl = CommunicationsController()
        self.computer_use = ComputerUseEngine()
        self.db = db
        self.state = state
        self.brain = brain
        self.heartbeat = heartbeat

    def set_heartbeat(self, heartbeat):
        self.heartbeat = heartbeat

    def execute_tool(self, tool_call: dict) -> dict:
        name = tool_call.get("name")
        args = tool_call.get("args", {})
        result_message = ""
        success = True

        if name == "activate_rest_or_dnd_mode":
            wake_time = args.get("wake_time")
            duration = args.get("duration_minutes")
            note = args.get("custom_note", "Rest")

            now = datetime.datetime.now()
            target_timestamp = None
            if duration:
                target_dt = now + datetime.timedelta(minutes=int(duration))
                target_timestamp = target_dt.strftime("%Y-%m-%d %H:%M:%S")
                wake_time = target_dt.strftime("%H:%M")
            elif wake_time:
                target_dt = normalize_target_time(wake_time, now)
                target_timestamp = target_dt.strftime("%Y-%m-%d %H:%M:%S")
                wake_time = target_dt.strftime("%H:%M")

            if args.get("block_all_disturbances", True):
                self.win_ctrl.mute_audio()
                self.state.activate_rest_mode(target_time_str=wake_time, reason=note)
            else:
                self.state.activate_dnd_mode(reason=note)

            # Schedule wake up reminder alarm
            if target_timestamp:
                self.db.add_scheduled_action(
                    target_time=target_timestamp,
                    action_type="wake_up",
                    target_entity="Self (Wake Up)",
                    proactive_speech="Sir, uth jaiye! Scheduled rest period khatam ho gaya hai."
                )

            result_message = f"Rest mode activated until {wake_time or 'further notice'}. Audio muted."

        elif name == "schedule_proactive_reminder":
            target_time = args.get("target_time")
            delay_sec = args.get("delay_seconds")
            delay_min = args.get("delay_minutes")
            person = args.get("topic_or_person", "")
            speech = args.get("proactive_speech", f"Sir, reminder regarding {person}.")

            now = datetime.datetime.now()
            if delay_sec:
                target_dt = now + datetime.timedelta(seconds=int(delay_sec))
                target_time = target_dt.strftime("%Y-%m-%d %H:%M:%S")
            elif delay_min:
                target_dt = now + datetime.timedelta(minutes=int(delay_min))
                target_time = target_dt.strftime("%Y-%m-%d %H:%M:%S")
            elif target_time:
                target_dt = normalize_target_time(target_time, now)
                target_time = target_dt.strftime("%Y-%m-%d %H:%M:%S")
            else:
                target_dt = now + datetime.timedelta(minutes=1)
                target_time = target_dt.strftime("%Y-%m-%d %H:%M:%S")

            is_wake = any(w in str(person).lower() or w in str(speech).lower() for w in ["wake", "uth", "sleep", "so"])
            action_type = "wake_up" if is_wake else "reminder"

            action_id = self.db.add_scheduled_action(
                target_time=target_time,
                action_type=action_type,
                target_entity=person,
                proactive_speech=speech
            )
            result_message = f"Reminder scheduled for {target_time} regarding {person} (ID: {action_id})."

        elif name == "control_hardware_and_audio":
            action = args.get("action")
            val = args.get("value")

            if action == "mute":
                self.win_ctrl.mute_audio()
                result_message = "Master volume muted."
            elif action == "unmute":
                self.win_ctrl.unmute_audio()
                result_message = "Master volume unmuted."
            elif action == "set_volume":
                new_vol = self.win_ctrl.set_volume_percent(val if val is not None else 50)
                result_message = f"Master volume set to {new_vol}%."
            elif action == "volume_up":
                step = val if val is not None else 10
                new_vol = self.win_ctrl.volume_up(step=step)
                result_message = f"Master volume increased to {new_vol}%."
            elif action == "volume_down":
                step = val if val is not None else 10
                new_vol = self.win_ctrl.volume_down(step=step)
                result_message = f"Master volume decreased to {new_vol}%."
            elif action == "lock_screen":
                self.win_ctrl.lock_workstation()
                result_message = "Workstation locked."
            elif action == "check_battery":
                telemetry = self.win_ctrl.get_system_telemetry()
                result_message = f"Battery at {telemetry['battery_percent']}%, plugged in: {telemetry['power_plugged']}."

        elif name == "browser_and_web_action":
            action = args.get("action", "")
            query = args.get("query", "").strip()

            if action == "open_url":
                self.win_ctrl.open_url(query)
                result_message = f"Opened URL {query} in browser."
            elif action == "search_google":
                self.win_ctrl.search_web(query, engine="google")
                result_message = f"Searched Google for '{query}'."
            elif action in ("search_youtube", "play_youtube"):
                self.win_ctrl.play_youtube(query)
                result_message = f"Searched and opened YouTube for '{query}'."
            elif action in ("new_tab", "close_tab", "next_tab", "prev_tab", "reopen_tab", "refresh", "scroll_down", "scroll_up"):
                res = self.computer_use.browser_action(action)
                success = res.get("success", True)
                result_message = f"Browser action '{action}' executed."
            else:
                success = False
                result_message = f"Unsupported browser action: {action}"

        elif name == "window_and_system_action":
            action = args.get("action", "")
            val = args.get("value")

            if action == "maximize":
                res = self.win_ctrl.maximize_active_window()
                result_message = "Active window maximized."
            elif action == "minimize":
                res = self.win_ctrl.minimize_active_window()
                result_message = "Active window minimized."
            elif action == "restore":
                res = self.win_ctrl.restore_active_window()
                result_message = "Active window restored."
            elif action == "close_window":
                res = self.win_ctrl.close_active_window()
                result_message = "Active window closed."
            elif action == "show_desktop":
                res = self.win_ctrl.show_desktop()
                result_message = "Desktop toggled."
            elif action == "switch_window":
                res = self.win_ctrl.switch_window()
                result_message = "Switched to next window."
            elif action == "media_play_pause":
                res = self.win_ctrl.media_play_pause()
                result_message = "Media Play/Pause toggled."
            elif action == "media_next":
                res = self.win_ctrl.media_next()
                result_message = "Skipped to next track."
            elif action == "media_prev":
                res = self.win_ctrl.media_prev()
                result_message = "Skipped to previous track."
            elif action == "media_stop":
                res = self.win_ctrl.media_stop()
                result_message = "Media playback stopped."
            elif action == "volume_up":
                step = val if val is not None else 10
                new_vol = self.win_ctrl.volume_up(step=step)
                result_message = f"Master volume increased to {new_vol}%."
            elif action == "volume_down":
                step = val if val is not None else 10
                new_vol = self.win_ctrl.volume_down(step=step)
                result_message = f"Master volume decreased to {new_vol}%."
            else:
                success = False
                result_message = f"Unsupported window/system action: {action}"

        elif name == "control_laptop_application":
            action = args.get("action")
            app = args.get("app_name", "")

            if action == "launch":
                res = self.computer_use.open_or_focus(app)
                result_message = res["message"]
                success = res["success"]
            elif action == "close":
                res = self.win_ctrl.close_application(app)
                result_message = res["message"]
                success = res["success"]

        elif name in ("universal_communicate", "communicate_teams", "communicate_whatsapp"):
            app = args.get("app_name", "Teams" if "teams" in name else "WhatsApp")
            action = args.get("action", "message")
            raw_contact = args.get("contact_name") or args.get("person_name") or args.get("target", "")
            msg = args.get("message", "")
            call_type = args.get("call_type", "audio")
            sched_time = args.get("schedule_time")
            delay_min = args.get("delay_minutes")

            # Intelligently resolve contact name via Knowledge Graph (e.g. 'my colleague', 'manager', 'friend')
            resolved = self.db.resolve_entity(raw_contact)
            contact = resolved["name"] if resolved else raw_contact

            # Check if this communication should be scheduled for later
            if sched_time or delay_min:
                import json
                now = datetime.datetime.now()
                if delay_min:
                    target_dt = now + datetime.timedelta(minutes=int(delay_min))
                    target_timestamp = target_dt.strftime("%Y-%m-%d %H:%M:%S")
                else:
                    target_dt = normalize_target_time(sched_time, now)
                    target_timestamp = target_dt.strftime("%Y-%m-%d %H:%M:%S")

                payload = json.dumps({
                    "app_name": app,
                    "action": action,
                    "contact_name": contact,
                    "message": msg,
                    "call_type": call_type
                })
                aid = self.db.add_scheduled_action(
                    target_time=target_timestamp,
                    action_type="scheduled_communication",
                    target_entity=contact,
                    proactive_speech=payload
                )
                result_message = f"Sir, {contact} ko {app} par {action} ({target_timestamp}) ke liye schedule kar diya gaya hai (ID: {aid})."
                return {
                    "tool_name": name,
                    "success": True,
                    "message": result_message
                }

            if action == "call":
                res = self.computer_use.universal_call(app, contact, call_type=call_type)
            elif action == "open":
                res = self.computer_use.open_or_focus(app)
            else:
                res = self.computer_use.universal_message(app, contact, msg)

            result_message = res.get("message", "Dispatched.")
            success = res.get("success", True)

        elif name == "universal_computer_action":
            action = args.get("action")
            target = args.get("target", "")
            text = args.get("text", "")
            app_name = args.get("app_name", "")

            # If an app_name is specified, bring it to the foreground first
            if app_name and action != "open_app_or_url":
                self.computer_use.open_or_focus(app_name)

            if action == "open_app_or_url":
                res = self.computer_use.open_or_focus(target)
            elif action == "click_button":
                res = self.computer_use.click_element_by_name(target, app_name=app_name)
            elif action == "type_text":
                res = self.computer_use.type_into_input(text, input_query=target, press_enter=True)
            elif action == "press_keys":
                res = self.computer_use.send_keyboard_shortcut(target)
            else:
                res = {"success": False, "message": f"Unknown action: {action}"}

            result_message = res.get("message", "Executed.")
            success = res.get("success", True)

        elif name == "update_personal_knowledge":
            person = args.get("person_name", "").strip()
            rel = args.get("relationship", "").strip()
            phone = args.get("phone", "").strip()
            aliases = args.get("aliases", "").strip()
            importance = args.get("importance", 1)
            notes = args.get("notes", "").strip()

            self.db.add_person(name=person, relationship=rel, phone=phone, notes=notes, aliases=aliases, importance=importance)
            if notes:
                self.db.add_life_fact(entity_name=person, fact_key="notes", fact_value=notes, category="personal")
            pri_label = "VIP / Family Member" if int(importance) >= 3 else ("High Priority" if int(importance) == 2 else "Standard")
            result_message = f"Knowledge Graph updated: {person} is recorded as '{rel or 'contact'}' [{pri_label}]. {notes}"

        elif name == "query_personal_knowledge":
            query = args.get("query", "").strip()
            entity = self.db.resolve_entity(query)
            if entity:
                facts = self.db.get_entity_facts(entity["name"])
                fact_str = ""
                if facts:
                    fact_str = " Key facts: " + "; ".join(f"{f['key']}: {f['value']}" for f in facts[:3])
                phone_str = f" Phone: {entity['phone']}." if entity['phone'] and entity['phone'] != 'N/A' else ""
                notes_str = f" Notes: {entity['notes']}." if entity['notes'] else ""
                result_message = (
                    f"Sir, {entity['name']} is recorded as your {entity['relationship']}.{phone_str}{notes_str}{fact_str}"
                )
            else:
                result_message = f"I do not have any existing records regarding '{query}' in your Knowledge Graph, Sir."

        elif name == "list_personal_knowledge":
            entities = self.db.get_all_entities()
            if not entities:
                result_message = "Your personal knowledge graph is currently empty, Sir."
            else:
                lines = []
                for e in entities:
                    lines.append(f"- {e['name']} ({e['relationship']}) | Phone: {e['phone']} | Notes: {e['notes']}")
                result_message = f"Sir, here are your {len(entities)} known contacts in memory:\n" + "\n".join(lines)


        elif name == "resume_or_wake_up":
            self.win_ctrl.unmute_audio()
            self.win_ctrl.set_volume_percent(40)
            self.state.wake_up()
            from core.heartbeat import build_quarantine_briefing
            briefing = build_quarantine_briefing(self.db, brain=self.brain)
            result_message = briefing

        elif name == "review_quarantined_disturbances":
            limit = args.get("limit", 10)
            items = self.db.get_all_quarantined_messages(limit=limit)
            if not items:
                result_message = "No quarantined disturbances found in the log."
            else:
                summary_lines = []
                for it in items:
                    status = "Reviewed" if it["is_reviewed"] else "Pending"
                    summary_lines.append(f"[{it['source_app']}] from {it['sender']}: '{it['message_content']}' ({status})")
                result_message = "Quarantined items:\n" + "\n".join(summary_lines)

        elif name in ("inspect_inbox_or_emails", "inspect_screen_or_window_content"):
            import time
            sender = args.get("sender_filter") or args.get("query") or ""
            open_if = args.get("open_if_closed", True)
            target_app = args.get("target_app_or_url") or "gmail"

            if open_if:
                self.computer_use.open_or_focus("https://mail.google.com")
                time.sleep(1.0)

            # Universal OS extraction: extracts all visible text/items with ZERO hardcoded keywords
            res = self.computer_use.inspect_window_content(target_app_or_url=target_app, max_items=40)
            items = res.get("items", [])
            raw_text = res.get("raw_text", "")
            win_title = res.get("window_title", "Inbox / Window")

            obs_data = {
                "window_title": win_title,
                "user_query_filter": sender,
                "visible_items": items,
                "raw_text": raw_text[:3000]
            }

            return {
                "tool_name": name,
                "success": res.get("success", True),
                "has_observation": True,
                "observation_data": obs_data,
                "message": f"Observed {len(items)} items in '{win_title}' for Gemini semantic reasoning."
            }

        elif name in ("activate_detective_mode", "activate_guard_sentry_mode"):
            note = args.get("custom_note", "Sir activated Detective Mode")
            cur_win = self.win_ctrl.get_active_window_info()
            self.state.activate_detective_mode(initial_window=cur_win.get("title", "Desktop"), reason=note)
            result_message = "Detective mode online, Sir. Main sabhi activities aur window switches par nazar rakh raha hoon."
            success = True

        elif name == "deactivate_detective_mode":
            self.state.deactivate_detective_mode()
            result_message = "Detective mode off ho gaya hai, Sir. Systems normal mode mein hain."
            success = True

        elif name == "get_laptop_activity_timeline":
            minutes = args.get("minutes", 5)
            query = args.get("query", "")

            # Retrieve real chronological activity timeline
            recent_acts = []
            if self.heartbeat and hasattr(self.heartbeat, "get_recent_activities"):
                recent_acts = self.heartbeat.get_recent_activities(minutes=minutes)

            if not recent_acts:
                db_logs = self.db.get_recent_activity_logs(minutes=minutes, limit=30)
                recent_acts = [
                    {"time": str(l.get("timestamp", ""))[-8:], "window": l.get("window_title", ""), "process": l.get("process_name", "")}
                    for l in db_logs
                ]

            from core.state_machine import SystemState
            was_detective_active = (self.state.current_state in (SystemState.DETECTIVE, SystemState.GUARD)) or (self.state.guard_started_at is not None)
            detective_events = list(self.state.guard_events)

            # Automatically disengage Detective Mode once debrief is requested/presented
            if self.state.current_state in (SystemState.DETECTIVE, SystemState.GUARD):
                self.state.deactivate_detective_mode()
                print("[Detective Mode] Debrief delivered to Sir. Detective Mode automatically disengaged.")

            cur_win = self.win_ctrl.get_active_window_info()

            obs_data = {
                "inquiry": query,
                "requested_time_window_minutes": minutes,
                "current_active_window": cur_win,
                "detective_mode_was_active": was_detective_active,
                "detective_mode_now_disengaged": True,
                "detective_events_detected": detective_events,
                "chronological_activity_timeline": recent_acts,
                "total_recorded_events": len(recent_acts)
            }

            return {
                "tool_name": name,
                "success": True,
                "has_observation": True,
                "observation_data": obs_data,
                "message": f"Retrieved {len(recent_acts)} chronological events in the last {minutes} minutes."
            }

        elif name == "monitor_incoming_messages":
            contact = args.get("contact_name", "")
            app = args.get("app_name", "WhatsApp")
            inst = args.get("instruction", "Notify Sir when message arrives")
            self.state.add_message_watcher(contact_name=contact, app_name=app, instruction=inst)
            result_message = f"Monitoring engaged for {contact} on {app}. Sir will be proactively informed as soon as a new message arrives."
            success = True

        else:
            success = False
            result_message = f"Unknown tool call: {name}"

        return {
            "tool_name": name,
            "success": success,
            "message": result_message
        }

    def intercept_incoming_communication(self, source_app: str, sender: str, message_content: str) -> dict:
        """
        Intercepts incoming communication. If user is in Rest or DND mode,
        quarantines it into the database and suppresses disturbance.
        """
        if self.state.is_quarantined():
            qid = self.db.quarantine_message(source_app, sender, message_content)
            print(f"[Quarantine Engine] Intercepted disturbance from {sender} on {source_app} (ID: {qid}). Held in quarantine.")
            return {
                "quarantined": True,
                "message_id": qid,
                "action": "held",
                "message": f"Communication from {sender} quarantined due to active {self.state.current_state.value} mode."
            }
        else:
            return {
                "quarantined": False,
                "action": "allowed",
                "message": f"Normal mode: communication from {sender} allowed."
            }


    def execute_all(self, tool_calls: list) -> list:
        results = []
        for call in tool_calls:
            results.append(self.execute_tool(call))
        return results
