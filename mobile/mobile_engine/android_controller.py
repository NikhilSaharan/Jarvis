"""
Low-Level Android OS Controller for J.A.R.V.I.S.
Executes privileged rootless shell commands via Shizuku (rish bridge) or Android System Shell.
Handles touch taps, swipe gestures, text typing, hardware keycodes, and raw shell execution.
"""
import os
import re
import subprocess
import time
import shutil

class AndroidController:
    """Executes high-privilege Android shell actions via Shizuku (rish) or Android System Shell."""

    def __init__(self):
        os.environ["RISH_APPLICATION_ID"] = "com.termux"
        self.bridge_cmd = self._detect_bridge()
        self._cached_screen_size = None
        print(f"[Android Controller] Active privilege bridge: {' '.join(self.bridge_cmd)}")

    def _detect_bridge(self) -> list:
        os.environ["RISH_APPLICATION_ID"] = "com.termux"

        # Dex permissions fix for Android 14+ (app_process refuses writable dex)
        dex_candidates = [
            "/data/data/com.termux/files/usr/bin/rish_shizuku.dex",
            os.path.expanduser("~/../usr/bin/rish_shizuku.dex"),
            os.path.expanduser("~/rish_shizuku.dex"),
            os.path.expanduser("~/storage/shared/Download/rish_shizuku.dex"),
            os.path.expanduser("~/storage/shared/shizuku/rish_shizuku.dex")
        ]
        for d in dex_candidates:
            if os.path.exists(d):
                try:
                    os.chmod(d, 0o444)
                    subprocess.run(["chmod", "444", d], capture_output=True, timeout=2)
                except Exception:
                    pass

        # Check standard rish paths
        candidates = [
            "/data/data/com.termux/files/usr/bin/rish",
            shutil.which("rish"),
            os.path.expanduser("~/../usr/bin/rish"),
            os.path.expanduser("~/rish"),
            os.path.expanduser("~/storage/shared/Download/rish"),
            os.path.expanduser("~/storage/shared/shizuku/rish"),
            "/sdcard/Download/rish"
        ]

        test_env = os.environ.copy()
        test_env["RISH_APPLICATION_ID"] = "com.termux"

        verified_rish = None

        for c in candidates:
            if c and os.path.exists(c):
                # Ensure rish binary is executable
                try:
                    os.chmod(c, 0o755)
                    subprocess.run(["chmod", "+x", c], capture_output=True, timeout=2)
                except Exception:
                    pass

                # Probe rish with generous timeout for initial Shizuku binder handshake
                for attempt in range(2):
                    try:
                        res = subprocess.run([c, "-c", "id"], capture_output=True, text=True, timeout=10, env=test_env)
                        combined = f"{res.stdout} {res.stderr}".strip()
                        if "uid=" in combined or "shell" in combined or "2000" in combined:
                            print(f"[Android Controller] Shizuku bridge validated with UID: {combined.splitlines()[0]}")
                            return [c, "-c"]
                        elif "RISH_APPLICATION_ID" in combined:
                            print(f"[Android Controller Warning] RISH_APPLICATION_ID required for {c}")
                        else:
                            if attempt == 0:
                                time.sleep(0.5)
                    except subprocess.TimeoutExpired:
                        print(f"[Android Controller] Bridge probe {c} timed out on attempt {attempt+1}")
                    except Exception as e:
                        print(f"[Android Controller] Bridge probe error on {c}: {e}")

                # Note found rish path even if first binder handshake was delayed
                if not verified_rish:
                    verified_rish = c

        # If rish is installed on system, use it as primary bridge rather than unprivileged system shell
        if verified_rish:
            print(f"[Android Controller] Using installed Shizuku bridge: {verified_rish}")
            return [verified_rish, "-c"]

        # Fallback to su if rooted
        if shutil.which("su"):
            try:
                res = subprocess.run(["su", "-c", "id"], capture_output=True, text=True, timeout=2)
                if "uid=" in res.stdout:
                    return ["su", "-c"]
            except Exception:
                pass

        print("[Android Controller WARNING] Shizuku (rish) bridge not active! Commands requiring root/shell will be restricted.")
        if os.path.exists("/system/bin/sh"):
            return ["/system/bin/sh", "-c"]

        return ["sh", "-c"]

    def shell(self, command: str, timeout: int = 15) -> str:
        """Executes a command through the privileged bridge or Android system shell."""
        try:
            env = os.environ.copy()
            env["RISH_APPLICATION_ID"] = "com.termux"
            cmd = self.bridge_cmd + [command]
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=env)
            return res.stdout.strip()
        except Exception as e:
            print(f"[Android Controller Error] Command '{command}' failed: {e}")
            return ""

    def get_screen_size(self) -> tuple:
        """Returns physical screen (width, height) dynamically via `wm size` with caching."""
        if self._cached_screen_size:
            return self._cached_screen_size
        try:
            out = self.shell("wm size", timeout=3)
            m = re.search(r'(\d+)\s*x\s*(\d+)', out)
            if m:
                self._cached_screen_size = (int(m.group(1)), int(m.group(2)))
                return self._cached_screen_size
        except Exception:
            pass
        return 1080, 2400

    def tap(self, x: int, y: int):
        """Simulates physical touch tap at coordinates (x, y)."""
        self.shell(f"input tap {x} {y}")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 250):
        """Simulates swipe gesture from (x1, y1) to (x2, y2)."""
        self.shell(f"input swipe {x1} {y1} {x2} {y2} {duration_ms}")

    def type_text(self, text: str, press_enter: bool = False):
        """Types text into the active field cleanly and safely."""
        if not text:
            return
        # Escape single quotes and replace spaces with %s for Android input text
        safe_text = re.sub(r'[\'"`$;&|><]', '', text).replace(" ", "%s")
        self.shell(f"input text {safe_text}")
        if press_enter:
            time.sleep(0.3)
            self.keyevent(66)

    def keyevent(self, keycode: int):
        """Sends an Android KeyEvent code (e.g., 3=Home, 4=Back, 224=Wake, 66=Enter)."""
        self.shell(f"input keyevent {keycode}")

    def hide_keyboard(self):
        """Hides soft keyboard by sending ESCAPE key (111)."""
        self.keyevent(111)

    def wake_screen(self):
        """Wakes up the screen display if asleep."""
        self.keyevent(224)

    def go_home(self):
        """Navigates to the Android Home screen."""
        self.keyevent(3)

    def go_back(self):
        """Simulates the Back button."""
        self.keyevent(4)

    def press_enter(self):
        """Presses Enter key."""
        self.keyevent(66)

    def app_switch(self):
        """Opens recent apps / task switcher."""
        self.keyevent(187)

    def volume_up(self):
        """Increases system volume."""
        self.keyevent(24)

    def volume_down(self):
        """Decreases system volume."""
        self.keyevent(25)

    def media_play_pause(self):
        """Toggles media play/pause."""
        self.keyevent(85)
