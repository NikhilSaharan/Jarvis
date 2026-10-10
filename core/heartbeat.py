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


class HeartbeatDaemon:
    def __init__(
        self,
        state: JarvisState,
        win_ctrl: WindowsController,
        db: KnowledgeDatabase,
        speaker: JarvisSpeaker,
        interval_seconds: float = 2.0
    ):
        self.state = state
        self.win_ctrl = win_ctrl
        self.db = db
        self.speaker = speaker
        self.interval = interval_seconds

        self._running = False
        self._thread: Optional[threading.Thread] = None

        # Telemetry alert cooldown trackers
        self._last_battery_alert_time: float = 0.0

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

    def _run_loop(self):
        while self._running:
            try:
                self._pulse()
            except Exception as e:
                print(f"[Heartbeat Warning] Pulse error: {e}")
            time.sleep(self.interval)

    def _pulse(self):
        now = datetime.datetime.now()
        current_time_str = now.strftime("%H:%M")

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

        # =====================================================================
        # 2. CHECK REST / SLEEP WAKE-UP SCHEDULE
        # =====================================================================
        if self.state.current_state == SystemState.SLEEPING and self.state.target_wake_time:
            if current_time_str >= self.state.target_wake_time:
                print(f"[Heartbeat] Wake-up time {self.state.target_wake_time} reached. Restoring systems.")
                self.win_ctrl.unmute_audio()
                self.state.wake_up()
                self.speaker.speak("Good day, sir. Your scheduled rest period has concluded. All systems are fully online.")

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
