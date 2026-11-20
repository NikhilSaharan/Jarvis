"""
Speech Synthesis Engine for J.A.R.V.I.S. Mobile.
Zero-dependency direct REST synthesis for ElevenLabs Turbo v2.5 George (British Butler).
Automatic fallback to Termux Native Android TTS (termux-tts-speak) and Edge-TTS.
Does NOT require elevenlabs python SDK or pydantic. Runs natively on Android Termux.
"""
import os
import sys
import io
import time
import json
import urllib.request
import subprocess
import shutil
from pathlib import Path

# Load environment variables with zero-dependency native fallback
def _load_env_fallback():
    for candidate in [
        Path(__file__).resolve().parent.parent / "config" / "secrets.env",
        Path.cwd() / "config" / "secrets.env",
        Path.home() / "mobile" / "config" / "secrets.env"
    ]:
        if candidate.exists():
            try:
                with open(candidate, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith("#") and "=" in line:
                            k, v = line.split("=", 1)
                            os.environ[k.strip()] = v.strip().strip("'\"")
                break
            except Exception:
                pass

_load_env_fallback()

class MobileSpeaker:
    DEFAULT_BRITISH_VOICE = "JBFqnCBsd6RMkjVDRZzb"  # George (British Male Butler)

    def __init__(self, api_key: str = None, voice_id: str = None):
        self.api_key = api_key or os.getenv("ELEVENLABS_API_KEY")
        self.voice_id = voice_id or os.getenv("ELEVENLABS_VOICE_ID", self.DEFAULT_BRITISH_VOICE)
        self.is_live = False
        self._init_elevenlabs()

    def _init_elevenlabs(self):
        if not self.api_key or self.api_key.startswith("your_"):
            print("[Mobile Speaker] ElevenLabs credentials not provided. Using Android Native TTS.")
            self.is_live = False
            return

        self.is_live = True
        print(f"[Mobile Speaker] ElevenLabs Voice Engine ACTIVE with Voice ID: {self.voice_id}")

    def _play_audio_file(self, file_path: str):
        """Plays audio file on Android via termux-media-player or mpv."""
        if shutil.which("termux-media-player"):
            try:
                subprocess.run(["termux-media-player", "play", file_path], capture_output=True, timeout=10)
                return
            except Exception:
                pass

        if shutil.which("mpv"):
            try:
                subprocess.run(["mpv", "--no-video", file_path], capture_output=True, timeout=10)
                return
            except Exception:
                pass

    def speak(self, text: str, block: bool = False):
        """Synthesizes speech and outputs audio to phone speaker asynchronously (0ms blocking)."""
        if not text or not text.strip():
            return
        if not block:
            import threading
            threading.Thread(target=self._speak_sync, args=(text,), daemon=True).start()
        else:
            self._speak_sync(text)

    def _speak_sync(self, text: str):
        """Synchronous speech worker."""
        clean_text = text.strip()
        print(f"\n=======================================================")
        print(f"[J.A.R.V.I.S. Mobile]: \"{clean_text}\"")
        print(f"=======================================================\n")

        # 1. ElevenLabs Direct REST Path
        if self.is_live:
            try:
                url = f"https://api.elevenlabs.io/v1/text-to-speech/{self.voice_id}"
                headers = {
                    "xi-api-key": self.api_key,
                    "Content-Type": "application/json"
                }
                payload = {
                    "text": clean_text,
                    "model_id": "eleven_turbo_v2_5"
                }
                req = urllib.request.Request(
                    url,
                    data=json.dumps(payload).encode("utf-8"),
                    headers=headers
                )
                with urllib.request.urlopen(req, timeout=8) as resp:
                    audio_bytes = resp.read()

                if audio_bytes:
                    temp_file = "/sdcard/jarvis_speech.mp3"
                    with open(temp_file, "wb") as f:
                        f.write(audio_bytes)
                    self._play_audio_file(temp_file)
                    return
            except Exception as e:
                # If quota exceeded or 401, seamlessly fall back to Android TTS
                pass

        # 2. Android Native TTS (termux-tts-speak) - 0ms latency, always works
        if shutil.which("termux-tts-speak"):
            try:
                subprocess.run(["termux-tts-speak", clean_text], capture_output=True)
                return
            except Exception:
                pass

        # 3. Edge-TTS Fallback
        try:
            import asyncio
            import edge_tts
            temp_file = "/sdcard/jarvis_speech.mp3"
            communicate = edge_tts.Communicate(clean_text, "en-GB-RyanNeural")
            asyncio.run(communicate.save(temp_file))
            self._play_audio_file(temp_file)
            return
        except Exception:
            pass
