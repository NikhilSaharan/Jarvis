"""
System State Machine for JARVIS.
Tracks modes: NORMAL, SLEEPING, DND, FOCUS_WORK.
"""
from enum import Enum
import time

class SystemState(Enum):
    NORMAL = "normal"
    SLEEPING = "sleeping"
    DND = "dnd"
    FOCUS_WORK = "focus_work"
    GUARD = "guard"
    DETECTIVE = "detective"

class JarvisState:
    def __init__(self):
        self.current_state = SystemState.NORMAL
        self.sleep_target_time = None
        self.quarantine_reason = None
        self.sleep_started_at = None
        self.guard_started_at = None
        self.guard_initial_window = None
        self.guard_events = []
        self.active_message_watchers = []
        self.last_user_interaction = time.time()
        self.active_context = {}

    def add_message_watcher(self, contact_name: str, app_name: str = "WhatsApp", instruction: str = ""):
        """Registers a contact or app to actively monitor in the background for new messages."""
        contact_clean = (contact_name or "").strip()
        app_clean = (app_name or "WhatsApp").strip()
        for w in self.active_message_watchers:
            if w["contact"].lower() == contact_clean.lower() and w["app"].lower() == app_clean.lower():
                w["instruction"] = instruction
                w["updated_at"] = time.time()
                return w
        watcher = {
            "contact": contact_clean,
            "app": app_clean,
            "instruction": instruction,
            "added_at": time.time(),
            "last_seen_snippet": None,
            "last_unread_count": None,
            "last_notified_time": 0.0
        }
        self.active_message_watchers.append(watcher)
        return watcher

    def remove_message_watcher(self, contact_name: str, app_name: str = "WhatsApp"):
        """Removes an active message watcher."""
        c = contact_name.strip().lower()
        a = app_name.strip().lower()
        self.active_message_watchers = [
            w for w in self.active_message_watchers
            if not (w["contact"].lower() == c and w["app"].lower() == a)
        ]

    def activate_rest_mode(self, target_time_str: str = None, reason: str = "Rest"):
        """Activates quiet/sleep mode with target wake time and reason."""
        self.current_state = SystemState.SLEEPING
        self.sleep_target_time = target_time_str
        self.quarantine_reason = reason
        self.sleep_started_at = time.time()

    def activate_dnd_mode(self, reason: str = "Focus"):
        """Activates DND mode without sleeping."""
        self.current_state = SystemState.DND
        self.quarantine_reason = reason
        self.sleep_started_at = time.time()

    def activate_detective_mode(self, initial_window: str = "Desktop", reason: str = "Detective Mode"):
        """Activates Detective / Sentry mode to log all laptop touches and window switches."""
        self.current_state = SystemState.DETECTIVE
        self.guard_started_at = time.time()
        self.guard_initial_window = initial_window
        self.guard_events = []
        self.quarantine_reason = reason

    def activate_guard_mode(self, initial_window: str = "Desktop", reason: str = "Sir stepped away"):
        """Alias for activate_detective_mode."""
        self.activate_detective_mode(initial_window, reason)

    def record_guard_event(self, event_dict: dict):
        """Records an activity or window switch detected while in Detective/Guard mode."""
        self.guard_events.append({
            "timestamp": time.strftime("%H:%M:%S"),
            "window": event_dict.get("title", ""),
            "process": event_dict.get("process", ""),
            "duration": event_dict.get("duration", 0)
        })
        if len(self.guard_events) > 50:
            self.guard_events.pop(0)

    def deactivate_detective_mode(self):
        """Disengages Detective Mode and restores normal operating state."""
        if self.current_state in (SystemState.DETECTIVE, SystemState.GUARD):
            self.current_state = SystemState.NORMAL

    def deactivate_guard_mode(self):
        """Alias for deactivate_detective_mode."""
        self.deactivate_detective_mode()

    def wake_up_or_resume(self):
        """Restores normal operating state."""
        self.current_state = SystemState.NORMAL
        self.sleep_target_time = None
        self.quarantine_reason = None
        self.sleep_started_at = None
        self.last_user_interaction = time.time()

    @property
    def target_wake_time(self):
        """Property alias for sleep_target_time."""
        return self.sleep_target_time

    @target_wake_time.setter
    def target_wake_time(self, val):
        self.sleep_target_time = val

    def wake_up(self):
        """Alias for wake_up_or_resume."""
        self.wake_up_or_resume()

    def is_quarantined(self) -> bool:
        """Returns True if user should not be disturbed."""
        return self.current_state in (SystemState.SLEEPING, SystemState.DND)
