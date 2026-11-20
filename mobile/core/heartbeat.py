"""
Autonomous 24/7 Heartbeat Daemon for J.A.R.V.I.S. Mobile.
Continuously runs in the background (every 2 seconds) to deliver true proactive initiative:
- Speaks time-based reminders and scheduled follow-ups without user prompts.
- Monitors critical mobile hardware telemetry (battery level alerts).
- Manages wake-up schedules and transitions from Rest/Sleep mode to Normal mode with debriefings.
"""

import time
import json
import threading
import datetime
import subprocess
from typing import Optional
from core.state_machine import JarvisState, SystemState
from memory.database import KnowledgeDatabase

def build_quarantine_briefing(db: KnowledgeDatabase, brain=None) -> str:
    """Summarizes all unreviewed quarantined communications and marks them reviewed."""
    quarantined = db.get_unreviewed_quarantined_messages()
    if brain and hasattr(brain, "synthesize_wake_up_briefing"):
        briefing = brain.synthesize_wake_up_briefing(quarantined)
    else:
        if quarantined:
            senders = list(dict.fromkeys(q["sender"] for q in quarantined if q.get("sender")))
            apps = list(dict.fromkeys(q["source_app"] for q in quarantined if q.get("source_app")))
            senders_str = ", ".join(senders) if senders else "kuch contacts"
            apps_str = ", ".join(apps) if apps else "phone"
            briefing = f"Good morning Sir, time ho gaya hai. Aaraam ke dauraan {apps_str} par {senders_str} ki notifications aayi thi."
        else:
            briefing = "Good morning Sir, time ho gaya hai, uth jaiye."

    db.mark_quarantined_messages_reviewed()
    return briefing

class MobileHeartbeatDaemon:
    def __init__(
        self,
        state: JarvisState,
        db: KnowledgeDatabase,
        speaker=None,
        brain=None,
        interval_seconds: float = 2.0
    ):
        self.state = state
        self.db = db
        self.speaker = speaker
        self.brain = brain
        self.interval = interval_seconds

        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._last_battery_alert = 0.0

    def start(self):
        """Starts 24/7 background heartbeat thread."""
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._run_loop, daemon=True, name="MobileHeartbeat")
        self._thread.start()
        print(f"[Mobile Heartbeat] 24/7 autonomous pulse started (Interval: {self.interval}s)")

    def stop(self):
        """Stops heartbeat thread."""
        self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3)
        print("[Mobile Heartbeat] Daemon stopped.")

    def _speak(self, text: str):
        if self.speaker and hasattr(self.speaker, "speak"):
            self.speaker.speak(text)
        else:
            print(f"[Mobile Heartbeat Spoken Announcement]: {text}")

    def _get_battery_status(self) -> dict:
        try:
            res = subprocess.run(["termux-battery-status"], capture_output=True, text=True, timeout=2)
            if res.returncode == 0 and res.stdout.strip():
                return json.loads(res.stdout)
        except Exception:
            pass
        return {}

    def _run_loop(self):
        while self._running:
            try:
                now = datetime.datetime.now()
                now_str = now.strftime("%Y-%m-%d %H:%M:%S")

                # 1. Check SQLite Scheduled Proactive Actions
                due_actions = self.db.get_due_actions(now_str)
                for action in due_actions:
                    prompt = action.get("proactive_speech_prompt") or f"Sir, reminder: {action.get('action_type')}"
                    print(f"[Mobile Heartbeat] Triggering proactive reminder: {prompt}")
                    self._speak(prompt)
                    self.db.mark_action_executed(action["id"])

                # 2. Check Sleep / Rest Mode Wake-up Schedule
                if self.state.current_state in (SystemState.SLEEPING, SystemState.DND):
                    if self.state.sleep_target_time:
                        wake_target = self.state.sleep_target_time
                        if isinstance(wake_target, str) and now_str >= wake_target:
                            print(f"[Mobile Heartbeat] Sleep wake-up time reached ({wake_target}). Waking up Sir.")
                            self.state.set_state(SystemState.NORMAL)
                            briefing = build_quarantine_briefing(self.db, self.brain)
                            self._speak(briefing)

                # 3. Check Battery Status Every 10 Minutes
                if time.time() - self._last_battery_alert > 600:
                    batt = self._get_battery_status()
                    pct = batt.get("percentage")
                    status = batt.get("status", "")
                    if pct is not None and pct <= 15 and status != "CHARGING":
                        self._last_battery_alert = time.time()
                        self._speak(f"Sir, phone ki battery {pct} percent reh gayi hai. Kripya charger connect kar lijiye.")

            except Exception as e:
                print(f"[Mobile Heartbeat Error]: {e}")

            time.sleep(self.interval)
