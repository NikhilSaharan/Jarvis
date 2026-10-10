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
    Uses Gemini 3.1 Flash Lite to generate a natural, dynamic, conversational Hinglish briefing.
    """
    quarantined = db.get_unreviewed_quarantined_messages()
    if brain and hasattr(brain, "synthesize_wake_up_briefing"):
        briefing = brain.synthesize_wake_up_briefing(quarantined)
    else:
        if quarantined:
            count = len(quarantined)
            senders = list(dict.fromkeys(q["sender"] for q in quarantined))
            apps = list(dict.fromkeys(q["source_app"] for q in quarantined))
            senders_str = ", ".join(senders)
            apps_str = ", ".join(apps)
            briefing = (
                f"Shubh prabhat, Sir. Aapka rest period poora ho gaya hai aur sabhi systems active hain. "
                f"Aapke aaraam ke dauraan {apps_str} par {senders_str} ki taraf se {count} update aayi thi jo maine hold kar li thi. "
                f"Kya main unki details open kar doon?"
            )
        else:
            briefing = (
                "Good day, Sir. Aapka aaraam poora ho chuka hai, sabhi systems 100% online hain aur aaraam ke dauraan koi disturbance nahi hui. "
                "Main aapki kya madad kar sakta hu?"
            )

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
        # 1. CHECK SCHEDULED ACTIONS & REMINDERS
        # =====================================================================
        pending = self.db.get_pending_actions(current_time_str)
        for action_id, target_time, action_type, entity, proactive_speech in pending:
            # Respect DND / Rest modes
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

                # Generate post-rest dynamic briefing
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

