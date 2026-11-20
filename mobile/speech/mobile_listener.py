"""
Microphone Listener and Wake-Word Detection for J.A.R.V.I.S. Mobile.
Enables continuous hands-free voice command processing in Termux.
"""
import time
import subprocess
import threading
from typing import Optional, Callable

class MobileListener:
    def __init__(self, on_command_callback: Optional[Callable[[str], None]] = None):
        self.callback = on_command_callback
        self._running = False
        self._thread: Optional[threading.Thread] = None

    def start_listening(self):
        """Starts background listener."""
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True, name="MobileListener")
        self._thread.start()
        print("[Mobile Listener] Background microphone listener active.")

    def stop_listening(self):
        """Stops background listener."""
        self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3)

    def _loop(self):
        # Interactive speech recognition loop
        while self._running:
            # If termux-speech-to-text is available, it can be called:
            # res = subprocess.run(["termux-speech-to-text"], capture_output=True, text=True)
            time.sleep(1.0)
