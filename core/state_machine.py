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

class JarvisState:
    def __init__(self):
        self.current_state = SystemState.NORMAL
        self.sleep_target_time = None
        self.quarantine_reason = None
        self.sleep_started_at = None
        self.last_user_interaction = time.time()
        self.active_context = {}

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
