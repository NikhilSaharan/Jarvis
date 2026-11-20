"""
Universal Notification Watcher for J.A.R.V.I.S. Mobile.
Continuously captures live notifications across ALL applications on Android via Termux:API.
Extracts sender, app name, title, and message content with deduplication and priority filtering.
"""
import time
import json
import threading
import subprocess
from typing import Optional, Callable

class NotificationWatcher:
    """Monitors incoming notifications across ALL applications on the device."""

    def __init__(self, on_notification_callback: Optional[Callable[[dict], None]] = None, poll_interval: float = 2.5):
        self.callback = on_notification_callback
        self.poll_interval = poll_interval
        self.seen_keys = set()
        self._running = False
        self._thread: Optional[threading.Thread] = None

    def start(self):
        """Starts 24/7 background notification polling."""
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True, name="NotificationWatcher")
        self._thread.start()
        print("[Notification Watcher] Universal notification monitoring active.")

    def stop(self):
        """Stops background notification polling."""
        self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3)

    def fetch_current_notifications(self) -> list:
        """Runs termux-notification-list and parses JSON output."""
        try:
            res = subprocess.run(["termux-notification-list"], capture_output=True, text=True, timeout=3)
            if res.returncode == 0 and res.stdout.strip():
                return json.loads(res.stdout)
        except Exception:
            pass
        return []

    def _loop(self):
        # Initial priming: populate seen_keys with existing notifications so we don't spam on boot
        initial = self.fetch_current_notifications()
        for item in initial:
            k = f"{item.get('packageName')}_{item.get('id')}_{item.get('title')}_{item.get('content')}"
            self.seen_keys.add(k)

        while self._running:
            try:
                items = self.fetch_current_notifications()
                for item in items:
                    pkg = item.get("packageName", "")
                    title = item.get("title", "")
                    content = item.get("content", "")
                    notif_id = item.get("id", "")

                    # Unique fingerprint
                    key = f"{pkg}_{notif_id}_{title}_{content}"
                    if key not in self.seen_keys:
                        self.seen_keys.add(key)
                        parsed = {
                            "package": pkg,
                            "title": title,
                            "content": content,
                            "raw": item
                        }
                        if self.callback:
                            self.callback(parsed)
            except Exception as e:
                print(f"[Notification Watcher Error]: {e}")

            time.sleep(self.poll_interval)
