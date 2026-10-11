"""
Native Windows Control & Automation Engine for J.A.R.V.I.S.
Provides direct hardware, audio, process, and system automation on Windows 10/11.
"""
import os
import sys
import time
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
        info = self.get_active_window_info()
        return info["title"]

    def get_active_window_info(self) -> dict:
        """Returns the title, process name, and hwnd of the current foreground window."""
        try:
            hwnd = win32gui.GetForegroundWindow()
            if not hwnd:
                return {"title": "Desktop", "process": "explorer.exe", "hwnd": 0}
            title = win32gui.GetWindowText(hwnd) or "Desktop"
            _, pid = win32process.GetWindowThreadProcessId(hwnd)
            proc_name = "unknown"
            try:
                proc = psutil.Process(pid)
                proc_name = proc.name().lower()
            except Exception:
                pass
            return {"title": title, "process": proc_name, "hwnd": hwnd}
        except Exception:
            return {"title": "Desktop", "process": "explorer.exe", "hwnd": 0}

    def volume_up(self, step: int = 10) -> int:
        """Increases volume by step percent."""
        cur = self.get_volume_percent()
        new_vol = min(100, cur + step)
        return self.set_volume_percent(new_vol)

    def volume_down(self, step: int = 10) -> int:
        """Decreases volume by step percent."""
        cur = self.get_volume_percent()
        new_vol = max(0, cur - step)
        return self.set_volume_percent(new_vol)

    # ==================== MEDIA KEYBOARD CONTROLS ====================

    def _send_virtual_key(self, vk_code: int):
        """Simulates native Windows virtual key press and release."""
        user32 = ctypes.windll.user32
        user32.keybd_event(vk_code, 0, 0, 0)
        time.sleep(0.05)
        user32.keybd_event(vk_code, 0, 2, 0)

    def media_play_pause(self) -> bool:
        """Toggles play/pause for global media (Spotify, YouTube, Windows Media)."""
        try:
            self._send_virtual_key(0xB3)  # VK_MEDIA_PLAY_PAUSE
            return True
        except Exception as e:
            print(f"[Media Error] Play/Pause failed: {e}")
            return False

    def media_next(self) -> bool:
        """Skips to next media track."""
        try:
            self._send_virtual_key(0xB0)  # VK_MEDIA_NEXT_TRACK
            return True
        except Exception as e:
            print(f"[Media Error] Next track failed: {e}")
            return False

    def media_prev(self) -> bool:
        """Skips to previous media track."""
        try:
            self._send_virtual_key(0xB1)  # VK_MEDIA_PREV_TRACK
            return True
        except Exception as e:
            print(f"[Media Error] Previous track failed: {e}")
            return False

    def media_stop(self) -> bool:
        """Stops active media playback."""
        try:
            self._send_virtual_key(0xB2)  # VK_MEDIA_STOP
            return True
        except Exception as e:
            print(f"[Media Error] Media stop failed: {e}")
            return False

    # ==================== WINDOW STATE MANAGEMENT ====================

    def maximize_active_window(self) -> bool:
        """Maximizes the current foreground window."""
        try:
            hwnd = win32gui.GetForegroundWindow()
            if hwnd:
                win32gui.ShowWindow(hwnd, 3)  # SW_MAXIMIZE = 3
                return True
        except Exception as e:
            print(f"[Window Error] Maximize failed: {e}")
        return False

    def minimize_active_window(self) -> bool:
        """Minimizes the current foreground window."""
        try:
            hwnd = win32gui.GetForegroundWindow()
            if hwnd:
                win32gui.ShowWindow(hwnd, 6)  # SW_MINIMIZE = 6
                return True
        except Exception as e:
            print(f"[Window Error] Minimize failed: {e}")
        return False

    def restore_active_window(self) -> bool:
        """Restores the current foreground window."""
        try:
            hwnd = win32gui.GetForegroundWindow()
            if hwnd:
                win32gui.ShowWindow(hwnd, 9)  # SW_RESTORE = 9
                return True
        except Exception as e:
            print(f"[Window Error] Restore failed: {e}")
        return False

    def close_active_window(self) -> bool:
        """Closes the current active window (sends WM_CLOSE)."""
        try:
            hwnd = win32gui.GetForegroundWindow()
            if hwnd:
                win32gui.PostMessage(hwnd, 0x0010, 0, 0)  # WM_CLOSE = 0x0010
                return True
        except Exception as e:
            print(f"[Window Error] Close window failed: {e}")
        return False

    def show_desktop(self) -> bool:
        """Toggles Show Desktop (Win+D)."""
        try:
            user32 = ctypes.windll.user32
            user32.keybd_event(0x5B, 0, 0, 0)  # VK_LWIN
            user32.keybd_event(0x44, 0, 0, 0)  # 'D'
            time.sleep(0.05)
            user32.keybd_event(0x44, 0, 2, 0)
            user32.keybd_event(0x5B, 0, 2, 0)
            return True
        except Exception as e:
            print(f"[Window Error] Show Desktop failed: {e}")
            return False

    def switch_window(self) -> bool:
        """Simulates Alt+Tab to cycle windows."""
        try:
            user32 = ctypes.windll.user32
            user32.keybd_event(0x12, 0, 0, 0)  # VK_LMENU (Alt)
            user32.keybd_event(0x09, 0, 0, 0)  # VK_TAB
            time.sleep(0.05)
            user32.keybd_event(0x09, 0, 2, 0)
            user32.keybd_event(0x12, 0, 2, 0)
            return True
        except Exception as e:
            print(f"[Window Error] Switch window failed: {e}")
            return False

    # ==================== HARDWARE & SYSTEM TELEMETRY ====================

    def get_system_telemetry(self) -> dict:
        """Collects real-time battery, CPU, RAM, and active window data."""
        battery = psutil.sensors_battery()
        win_info = self.get_active_window_info()
        return {
            "battery_percent": battery.percent if battery else None,
            "power_plugged": battery.power_plugged if battery else True,
            "cpu_percent": psutil.cpu_percent(interval=None),
            "ram_percent": psutil.virtual_memory().percent,
            "active_window": win_info["title"],
            "active_process": win_info["process"]
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

    def search_web(self, query: str, engine: str = "google") -> bool:
        """Performs a web search on Google or YouTube."""
        import urllib.parse
        encoded = urllib.parse.quote_plus(query)
        if engine.lower() == "youtube":
            url = f"https://www.youtube.com/results?search_query={encoded}"
        else:
            url = f"https://www.google.com/search?q={encoded}"
        return self.open_url(url)

    def play_youtube(self, query: str) -> bool:
        """
        Searches YouTube, extracts the first matching video ID,
        and directly opens the watch URL so the video starts playing immediately!
        """
        import urllib.request
        import urllib.parse
        import re

        clean_query = query.strip()
        encoded = urllib.parse.quote_plus(clean_query)
        search_url = f"https://www.youtube.com/results?search_query={encoded}"

        try:
            req = urllib.request.Request(
                search_url,
                headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
            )
            html = urllib.request.urlopen(req, timeout=5.0).read().decode("utf-8")
            # Extract video IDs from search response
            video_ids = [
                vid for vid in re.findall(r'/watch\?v=([a-zA-Z0-9_-]{11})', html)
                if len(vid) == 11
            ]
            if video_ids:
                play_url = f"https://www.youtube.com/watch?v={video_ids[0]}"
                print(f"[WindowsController] Found top video for '{clean_query}': {play_url}")
                return self.open_url(play_url)
        except Exception as e:
            print(f"[WindowsController Warning] Could not extract direct video ID ({e}), falling back to search page.")

        return self.open_url(search_url)
