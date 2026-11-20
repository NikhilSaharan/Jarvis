"""
Lock Screen Manager for J.A.R.V.I.S. Mobile.
Automates hands-free screen wake-up, lock screen swipe gestures, and PIN auto-unlock.
"""
import time
import os
import json
from pathlib import Path

CONFIG_PATH = Path(__file__).resolve().parent.parent / "config" / "mobile_settings.json"

class LockScreenManager:
    def __init__(self, controller):
        self.ac = controller
        self.settings = self._load_settings()

    def _load_settings(self) -> dict:
        try:
            if CONFIG_PATH.exists():
                with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                    return json.load(f)
        except Exception:
            pass
        return {}

    def is_screen_on(self) -> bool:
        """Checks if screen is currently active/awake."""
        res = self.ac.shell("dumpsys power | grep 'mHoldingDisplaySuspendBlocker'")
        if "true" in res.lower():
            return True
        res_display = self.ac.shell("dumpsys display | grep 'mScreenState'")
        return "ON" in res_display or "STATE_ON" in res_display

    def wake_screen(self):
        """Turns the physical screen ON if asleep."""
        self.ac.wake_screen()
        time.sleep(0.3)

    def wake_and_unlock(self, pin: str = None) -> dict:
        """
        Executes full hands-free wake and unlock sequence:
        1. Wakes display (Keyevent 224)
        2. Swipes up to reveal PIN keypad
        3. Types PIN
        4. Presses Enter (Keyevent 66)
        """
        device_cfg = self.settings.get("device", {})
        target_pin = pin or os.getenv("PHONE_PIN") or device_cfg.get("default_pin", "1234")
        # Query live physical screen resolution
        w, h = self.ac.get_screen_size()
        cx = w // 2
        start_y = int(h * 0.80)
        end_y = int(h * 0.25)

        # 1. Wake screen
        self.ac.wake_screen()
        time.sleep(0.5)

        # 2. Swipe up to reveal PIN pad
        self.ac.swipe(cx, start_y, cx, end_y, duration_ms=280)
        time.sleep(0.6)

        # 3. Inject PIN
        if target_pin:
            self.ac.type_text(str(target_pin))
            time.sleep(0.3)
            # 4. Press Enter
            self.ac.keyevent(66)
            time.sleep(0.5)

        return {
            "success": True,
            "message": "Screen awakened, swiped up, and PIN entered successfully."
        }
