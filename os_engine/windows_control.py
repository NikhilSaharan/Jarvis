"""
Native Windows Control & Automation Engine for J.A.R.V.I.S.
Provides direct hardware, audio, process, and system automation on Windows 10/11.
"""
import os
import sys
import subprocess
import ctypes
import psutil
import win32gui
import win32process
from pycaw.pycaw import AudioUtilities

# Known Windows app aliases for easy launching
COMMON_APP_MAP = {
    "notepad": "notepad.exe",
    "calc": "calc.exe",
    "calculator": "calc.exe",
    "chrome": "chrome.exe",
    "browser": "chrome.exe",
    "spotify": "spotify.exe",
    "code": "code.cmd",
    "vscode": "code.cmd",
    "terminal": "wt.exe",
    "cmd": "cmd.exe",
    "explorer": "explorer.exe",
    "taskmanager": "taskmgr.exe",
    "paint": "mspaint.exe"
}

class WindowsController:
    def __init__(self):
        self._init_audio()

    def _init_audio(self):
        try:
            device = AudioUtilities.GetSpeakers()
            self.volume_ctrl = device.EndpointVolume
            self.audio_available = True
        except Exception as e:
            print(f"[WindowsController Warning] Audio device unavailable: {e}")
            self.volume_ctrl = None
            self.audio_available = False

    # ==================== AUDIO CONTROL ====================

    def get_volume_percent(self) -> int:
        """Returns the current master volume as an integer (0-100)."""
        if not self.audio_available:
            return 50
        try:
            scalar = self.volume_ctrl.GetMasterVolumeLevelScalar()
            return int(round(scalar * 100))
        except Exception as e:
            print(f"[Audio Error] Could not read volume: {e}")
            return 50

    def set_volume_percent(self, percent: int) -> int:
        """Sets master volume from 0 to 100."""
        if not self.audio_available:
            return percent
        try:
            scalar = max(0.0, min(1.0, float(percent) / 100.0))
            self.volume_ctrl.SetMute(0, None)
            self.volume_ctrl.SetMasterVolumeLevelScalar(scalar, None)
            return int(round(scalar * 100))
        except Exception as e:
            print(f"[Audio Error] Could not set volume: {e}")
            return self.get_volume_percent()

    def mute_audio(self) -> bool:
        """Silences system speakers."""
        if not self.audio_available:
            return True
        try:
            self.volume_ctrl.SetMute(1, None)
            return True
        except Exception as e:
            print(f"[Audio Error] Mute failed: {e}")
            return False

    def unmute_audio(self) -> bool:
        """Restores system speakers."""
        if not self.audio_available:
            return False
        try:
            self.volume_ctrl.SetMute(0, None)
            return True
        except Exception as e:
            print(f"[Audio Error] Unmute failed: {e}")
            return False

    def is_muted(self) -> bool:
        """Checks if master audio is currently muted."""
        if not self.audio_available:
            return False
        try:
            return bool(self.volume_ctrl.GetMute())
        except Exception:
            return False

    # ==================== PROCESS & APPLICATION CONTROL ====================

    def launch_application(self, app_name: str) -> dict:
        """
        Launches an application by name, alias, or file path.
        """
        clean_name = app_name.strip().lower()
        target_executable = COMMON_APP_MAP.get(clean_name, app_name)

        try:
            # Use Windows start to handle both PATH executables and custom apps
            proc = subprocess.Popen(
                f'start "" "{target_executable}"',
                shell=True
            )
            return {
                "success": True,
                "app": app_name,
                "message": f"Application '{app_name}' launched successfully."
            }
        except Exception as e:
            return {
                "success": False,
                "app": app_name,
                "message": f"Failed to launch '{app_name}': {e}"
            }

    def close_application(self, app_name: str) -> dict:
        """
        Gracefully terminates or kills processes matching the app name.
        """
        clean_name = app_name.strip().lower()
        target_name = COMMON_APP_MAP.get(clean_name, app_name).replace(".exe", "")
        terminated_count = 0

        for proc in psutil.process_iter(['name']):
            try:
                proc_name = proc.info['name'].lower()
                if target_name in proc_name:
                    proc.terminate()
                    terminated_count += 1
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue

        if terminated_count > 0:
            return {
                "success": True,
                "app": app_name,
                "message": f"Terminated {terminated_count} instance(s) of '{app_name}'."
            }
        else:
            return {
                "success": False,
                "app": app_name,
                "message": f"No active processes found matching '{app_name}'."
            }

    def get_active_window_title(self) -> str:
        """Returns the title of the current foreground window."""
        try:
            hwnd = win32gui.GetForegroundWindow()
            if hwnd:
                return win32gui.GetWindowText(hwnd) or "Desktop"
            return "Desktop"
        except Exception:
            return "Desktop"

    # ==================== HARDWARE & SYSTEM TELEMETRY ====================

    def get_system_telemetry(self) -> dict:
        """Collects real-time battery, CPU, RAM, and active window data."""
        battery = psutil.sensors_battery()
        return {
            "battery_percent": battery.percent if battery else None,
            "power_plugged": battery.power_plugged if battery else True,
            "cpu_percent": psutil.cpu_percent(interval=None),
            "ram_percent": psutil.virtual_memory().percent,
            "active_window": self.get_active_window_title()
        }

    def lock_workstation(self) -> bool:
        """Locks the Windows computer."""
        try:
            ctypes.windll.user32.LockWorkStation()
            return True
        except Exception as e:
            print(f"[System Error] Lock workstation failed: {e}")
            return False

    def open_url(self, url: str) -> bool:
        """Opens a web URL in the default browser."""
        try:
            subprocess.Popen(f'start "" "{url}"', shell=True)
            return True
        except Exception as e:
            print(f"[Browser Error] Failed to open URL: {e}")
            return False
