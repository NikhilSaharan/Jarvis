"""
System State Machine for JARVIS Mobile.
Tracks modes: NORMAL, SLEEPING, DND, FOCUS_WORK, GUARD.
Preserved 100% from desktop core.
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
        """Removes a registered message watcher."""
        c = (contact_name or "").strip().lower()
        a = (app_name or "").strip().lower()
        self.active_message_watchers = [
            w for w in self.active_message_watchers
            if not (w["contact"].lower() == c and (not a or w["app"].lower() == a))
        ]

    def set_state(self, new_state: SystemState, **kwargs):
        self.current_state = new_state
        if new_state in (SystemState.SLEEPING, SystemState.DND):
            self.sleep_started_at = time.time()
            self.sleep_target_time = kwargs.get("target_time")
            self.quarantine_reason = kwargs.get("reason", "Sir is resting")
        elif new_state == SystemState.NORMAL:
            self.sleep_started_at = None
            self.sleep_target_time = None
            self.quarantine_reason = None
        elif new_state == SystemState.GUARD:
            self.guard_started_at = time.time()
            self.guard_events = []
