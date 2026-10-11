"""
Autonomous 24/7 Heartbeat Daemon for J.A.R.V.I.S.
Continuously runs in the background (every 2 seconds) to deliver true proactive initiative:
- Speaks time-based reminders and scheduled follow-ups without user prompts.
- Monitors critical hardware telemetry (battery low alerts).
- Manages wake-up schedules and transitions from Rest/Sleep mode to Normal mode.
"""

import time
import threading
import datetime
from typing import Optional
from core.state_machine import JarvisState, SystemState
from os_engine.windows_control import WindowsController
from memory.database import KnowledgeDatabase
from speech.speaker import JarvisSpeaker


def build_quarantine_briefing(db: KnowledgeDatabase, brain=None) -> str:
    """
    Summarizes all unreviewed quarantined communications and marks them reviewed.
    Uses Gemini 3.1 Flash Lite to generate a natural, single-sentence Hinglish wake-up greeting.
    """
    quarantined = db.get_unreviewed_quarantined_messages()
    if brain and hasattr(brain, "synthesize_wake_up_briefing"):
        briefing = brain.synthesize_wake_up_briefing(quarantined)
    else:
        if quarantined:
            senders = list(dict.fromkeys(q["sender"] for q in quarantined))
            apps = list(dict.fromkeys(q["source_app"] for q in quarantined))
            senders_str = ", ".join(senders)
            apps_str = ", ".join(apps)
            briefing = f"Good morning Sir, time ho gaya hai; aaraam ke waqt {apps_str} par {senders_str} ka call aaya tha."
        else:
            briefing = "Good morning Sir, time ho gaya hai, uth jaiye."

    db.mark_quarantined_messages_reviewed()
    return briefing


class HeartbeatDaemon:
    def __init__(
        self,
        state: JarvisState,
        win_ctrl: WindowsController,
        db: KnowledgeDatabase,
        speaker: JarvisSpeaker,
        brain=None,
        interval_seconds: float = 2.0
    ):
        self.state = state
        self.win_ctrl = win_ctrl
        self.db = db
        self.speaker = speaker
        self.brain = brain
        self.interval = interval_seconds

        self._running = False
        self._thread: Optional[threading.Thread] = None

        # Telemetry alert cooldown trackers
        self._last_battery_alert_time: float = 0.0
        self.pulse_count: int = 0
        self.triggered_actions_count: int = 0

        # Ambient Co-Pilot & Activity Tracker (Iron Man style)
        self._session_start_time: float = time.time()
        self._last_ambient_eval_time: float = 0.0
        self.ambient_eval_interval: float = 300.0  # 5 min cooldown between non-critical tips

        # Continuous Window & Activity Recorder
        self.activity_buffer: list = []
        self._last_window_title: Optional[str] = None
        self._last_process_name: Optional[str] = None
        self._last_window_time: float = time.time()

    def start(self):
        """Starts the autonomous heartbeat background loop."""
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._run_loop, name="JarvisHeartbeat", daemon=True)
        self._thread.start()
        print("[Heartbeat] Autonomous 24/7 daemon started.")

    def stop(self):
        """Stops the autonomous heartbeat loop."""
        self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3.0)
        print("[Heartbeat] Daemon stopped.")

    def build_wake_up_briefing(self) -> str:
        """Constructs post-rest summary briefing of any quarantined disturbances."""
        return build_quarantine_briefing(self.db, brain=self.brain)

    def _record_window_activity(self):
        """
        Continuously tracks active window and process transitions on Windows OS.
        Logs each window change chronologically into memory and SQLite database.
        Detects unauthorized interactions when in GUARD mode.
        """
        try:
            win_info = self.win_ctrl.get_active_window_info()
            title = (win_info.get("title") or "").strip()
            proc = (win_info.get("process") or "").strip()
            now_ts = time.time()

            if not title or title.lower() in ("desktop", ""):
                return

            if title != self._last_window_title or proc != self._last_process_name:
                dur = int(now_ts - self._last_window_time) if self._last_window_time else 0

                # Log previous window to database
                if self._last_window_title:
                    self.db.log_window_activity(self._last_window_title, self._last_process_name, dur)

                # Record new event in chronological memory buffer
                event = {
                    "time": datetime.datetime.now().strftime("%H:%M:%S"),
                    "window": title,
                    "process": proc,
                    "timestamp": now_ts
                }
                self.activity_buffer.append(event)
                if len(self.activity_buffer) > 100:
                    self.activity_buffer.pop(0)

                # Check Detective / Guard / Sentry Mode
                if self.state.current_state in (SystemState.GUARD, SystemState.DETECTIVE):
                    self.state.record_guard_event(win_info)
                    print(f"[Detective Mode Alert] Activity detected: '{title}' ({proc})")

                self._last_window_title = title
                self._last_process_name = proc
                self._last_window_time = now_ts
        except Exception:
            pass

    def get_recent_activities(self, minutes: int = 5) -> list:
        """
        Fetches chronological window and application activity for the past N minutes.
        Combines fast in-memory buffer with SQLite persistent logs.
        """
        cutoff_ts = time.time() - (minutes * 60)
        recent = [
            {"time": ev["time"], "window": ev["window"], "process": ev["process"]}
            for ev in self.activity_buffer
            if ev.get("timestamp", 0) >= cutoff_ts
        ]
        if not recent:
            db_logs = self.db.get_recent_activity_logs(minutes=minutes, limit=30)
            recent = [
                {"time": str(l.get("timestamp", ""))[-8:], "window": l.get("window_title", ""), "process": l.get("process_name", "")}
                for l in db_logs
            ]
        return recent

    def _run_loop(self):
        while self._running:
            try:
                self.pulse()
            except Exception as e:
                print(f"[Heartbeat Warning] Pulse error: {e}")
            time.sleep(self.interval)

    def pulse(self):
        """Executes a single observation and dispatch pulse."""
        self.pulse_count += 1
        now = datetime.datetime.now()
        current_time_str = now.strftime("%H:%M")
        current_full_str = now.strftime("%Y-%m-%d %H:%M:%S")

        # =====================================================================
        # 0. CONTINUOUS WINDOW TRANSITION & ACTIVITY TRACKING
        # =====================================================================
        self._record_window_activity()

        # =====================================================================
        # 1. CHECK SCHEDULED ACTIONS & REMINDERS
        # =====================================================================
        pending = self.db.get_pending_actions(current_time_str)
        for action_id, target_time, action_type, entity, proactive_speech in pending:
            # 1. Scheduled Communication (Message / Call)
            if action_type == "scheduled_communication":
                import json
                try:
                    p = json.loads(proactive_speech)
                    from os_engine.computer_use import ComputerUseEngine
                    cu = ComputerUseEngine()
                    app_name = p.get("app_name", "Teams")
                    contact = p.get("contact_name", "")
                    msg_txt = p.get("message", "")
                    if p.get("action") == "call":
                        cu.universal_call(app_name, contact, call_type=p.get("call_type", "audio"))
                    else:
                        cu.universal_message(app_name, contact, msg_txt)

                    confirm_text = ""
                    if self.brain and hasattr(self.brain, "_generate_dynamic_speech"):
                        confirm_text = self.brain._generate_dynamic_speech(
                            f"Sir, scheduled time aa gaya hai aur maine {contact} ko {app_name} par message '{msg_txt}' bhej diya hai.",
                            tool_calls=[{"name": "universal_communicate", "args": p}]
                        )
                    if not confirm_text:
                        confirm_text = f"Sir, scheduled time par {contact} ko {app_name} par {p.get('action')} bhej diya hai."
                    print(f"[Heartbeat] Executed scheduled communication: {confirm_text}")
                    self.speaker.speak(confirm_text)
                except Exception as e:
                    print(f"[Heartbeat] Error executing scheduled communication: {e}")
                self.db.mark_action_executed(action_id)
                self.triggered_actions_count += 1
                continue

            # 2. Wake Up Alarm / Reminder (Always executed even in Sleep mode)
            is_wake_reminder = (action_type == "wake_up") or any(k in str(entity).lower() or k in str(proactive_speech).lower() for k in ["wake", "uth", "sleep", "alarm"])
            if is_wake_reminder:
                print(f"[Heartbeat Trigger] Wake-Up action due ({target_time}): \"{proactive_speech}\"")
                self.win_ctrl.unmute_audio()
                self.win_ctrl.set_volume_percent(40)
                if self.state.current_state == SystemState.SLEEPING:
                    self.state.wake_up()
                self.state.target_wake_time = None
                briefing = self.build_wake_up_briefing()
                # Single concise spoken message
                wake_msg = briefing if "rest period" in proactive_speech.lower() or "uth jaiye" in proactive_speech.lower() else f"{proactive_speech}. {briefing}".strip()
                self.speaker.speak(wake_msg)
                self.db.mark_action_executed(action_id)
                self.triggered_actions_count += 1
                continue

            # Respect DND / Rest modes for non-wake reminders
            if self.state.current_state in (SystemState.SLEEPING, SystemState.DND):
                print(f"[Heartbeat] Action {action_id} deferred due to {self.state.current_state.value} mode.")
                continue

            print(f"[Heartbeat Trigger] Scheduled proactive speech: \"{proactive_speech}\"")
            self.speaker.speak(proactive_speech)
            self.db.mark_action_executed(action_id)
            self.triggered_actions_count += 1

        # =====================================================================
        # 2. CHECK REST / SLEEP WAKE-UP SCHEDULE & POST-REST BRIEFING
        # =====================================================================
        if self.state.current_state == SystemState.SLEEPING and self.state.target_wake_time:
            target_str = str(self.state.target_wake_time).strip()
            is_wake_due = False
            if len(target_str) == 5 and ":" in target_str:
                is_wake_due = (current_time_str >= target_str)
            elif len(target_str) >= 16:
                is_wake_due = (current_full_str >= target_str)
            elif target_str.replace(".", "", 1).isdigit():
                is_wake_due = (now.timestamp() >= float(target_str))
            else:
                is_wake_due = (current_time_str >= target_str)

            if is_wake_due:
                print(f"[Heartbeat] Wake-up time {self.state.target_wake_time} reached. Restoring systems.")
                self.win_ctrl.unmute_audio()
                self.win_ctrl.set_volume_percent(40)
                self.state.wake_up()
                self.state.target_wake_time = None
                # Mark any pending scheduled wake_up actions executed so they don't trigger twice
                for aid, tt, at, ent, ps in self.db.get_pending_actions(current_time_str):
                    if at == "wake_up":
                        self.db.mark_action_executed(aid)

                # Generate post-rest single-sentence briefing
                briefing = self.build_wake_up_briefing()
                print(f"[Heartbeat Wake-Up Briefing] {briefing}")
                self.speaker.speak(briefing)

        # =====================================================================
        # 3. CRITICAL HARDWARE TELEMETRY (BATTERY WARNING)
        # =====================================================================
        telemetry = self.win_ctrl.get_system_telemetry()
        battery_pct = telemetry.get("battery_percent")
        is_plugged = telemetry.get("power_plugged")

        if battery_pct is not None and battery_pct <= 15 and not is_plugged:
            # Alert at most once every 15 minutes (900 seconds)
            if time.time() - self._last_battery_alert_time > 900:
                self._last_battery_alert_time = time.time()
                alert_msg = f"Sir, battery level is at {battery_pct} percent. I strongly recommend connecting the power adapter."
                print(f"[Heartbeat Telemetry Alert] {alert_msg}")
                if self.state.current_state != SystemState.SLEEPING:
                    self.speaker.speak(alert_msg)

        # =====================================================================
        # 4. AUTONOMOUS AMBIENT CO-PILOT & WORK OBSERVATION (IRON MAN STYLE)
        # =====================================================================
        if self.state.current_state not in (SystemState.SLEEPING, SystemState.DND) and self.brain:
            now_time = time.time()
            continuous_mins = int((now_time - self._session_start_time) / 60)

            # Check if evaluation is due (cooldown expired)
            if now_time - self._last_ambient_eval_time >= self.ambient_eval_interval:
                self._last_ambient_eval_time = now_time
                win_info = self.win_ctrl.get_active_window_info()
                eval_res = self.brain.evaluate_ambient_activity(
                    active_window=win_info["title"],
                    active_process=win_info["process"],
                    continuous_work_minutes=continuous_mins,
                    user_state=self.state.current_state.value
                )
                if eval_res.get("should_speak"):
                    proactive_msg = eval_res["speech_text"]
                    print(f"[Heartbeat Ambient Co-Pilot] {proactive_msg} (Reason: {eval_res.get('reason')})")
                    self.speaker.speak(proactive_msg)
                    self.db.log_activity_session(
                        window_title=win_info["title"],
                        process_name=win_info["process"],
                        duration_seconds=int(now_time - self._session_start_time)
                    )

        # =====================================================================
        # 5. MONITORED COMMUNICATION WATCHER (WHATSAPP, TEAMS, ETC.)
        # =====================================================================
        self._check_monitored_messages()

    def _check_monitored_messages(self):
        """
        Continuously checks active message watchers (e.g. WhatsApp, Teams) in the background.
        If a new message from a monitored contact is detected, immediately alerts Sir aloud in Hinglish.
        """
        if not getattr(self.state, "active_message_watchers", None):
            return

        now_ts = time.time()
        # Non-disruptive check interval: every 3.5 seconds
        if now_ts - getattr(self, "_last_msg_check_time", 0.0) < 3.5:
            return
        self._last_msg_check_time = now_ts

        from os_engine.computer_use import ComputerUseEngine
        cu = getattr(self, "_cu_engine", None)
        if cu is None:
            cu = ComputerUseEngine()
            self._cu_engine = cu

        for watcher in list(self.state.active_message_watchers):
            contact = watcher.get("contact", "")
            app = watcher.get("app", "WhatsApp")
            if not contact:
                continue

            try:
                res = cu.inspect_messages_in_background(app_name=app, contact_name=contact)
                if not res.get("found"):
                    continue

                has_unread = res.get("has_unread", False)
                unread_count = res.get("unread_count", 0)
                matched_contact = res.get("matched_contact", False)
                snippet = res.get("snippet", "")
                last_count = watcher.get("last_unread_count")
                last_seen = watcher.get("last_seen_snippet")

                # Baseline initialization on first pulse
                if last_count is None and last_seen is None:
                    watcher["last_unread_count"] = unread_count
                    watcher["last_seen_snippet"] = snippet
                    # If an unread message already exists when Sir requested the check
                    if has_unread and unread_count > 0:
                        watcher["last_notified_time"] = now_ts
                        alert_msg = f"Sir, {app} par {contact} ka message aaya hua hai, check kar lijiye."
                        print(f"[Monitored Message Alert] {alert_msg}")
                        self.speaker.speak(alert_msg)
                    continue

                # Detection of new incoming message
                # 1. Unread count increased (e.g. 0 -> 1, or 1 -> 2)
                # 2. Or title/snippet changed while unread is present
                # 3. Or specific contact element appeared
                count_increased = (unread_count > (last_count or 0)) if (unread_count and last_count is not None) else False
                snippet_changed = (snippet != last_seen) and (has_unread or matched_contact)
                is_new = count_increased or snippet_changed

                if is_new and (now_ts - watcher.get("last_notified_time", 0.0) > 12.0):
                    watcher["last_unread_count"] = unread_count
                    watcher["last_seen_snippet"] = snippet
                    watcher["last_notified_time"] = now_ts

                    if matched_contact and contact.lower() not in (snippet or "").lower() and len(snippet) < 60:
                        alert_msg = f"Sir, {app} par {contact} ka naya message aaya hai: '{snippet}'."
                    else:
                        alert_msg = f"Sir, {app} par {contact} ka naya message aaya hai."

                    print(f"[Monitored Message Alert] {alert_msg}")
                    self.speaker.speak(alert_msg)
                elif snippet != last_seen:
                    watcher["last_seen_snippet"] = snippet
                    watcher["last_unread_count"] = unread_count
            except Exception:
                pass

