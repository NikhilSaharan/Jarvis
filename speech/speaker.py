"""
ElevenLabs Voice Engine for J.A.R.V.I.S.
Loads credentials automatically from config/secrets.env.
Supports target custom voice ID with automatic premade British butler fallback for Free Tier.
Uses native pygame.mixer for zero-dependency high-fidelity Windows audio playback.
"""
import os
import sys
import io
import time
from pathlib import Path

# Ensure UTF-8 output on Windows console
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

# Load environment variables from config/secrets.env
try:
    from dotenv import load_dotenv
    env_path = Path(__file__).resolve().parent.parent / "config" / "secrets.env"
    if env_path.exists():
        load_dotenv(dotenv_path=env_path)
except Exception:
    pass


class JarvisSpeaker:
    # British Butler premade voice on ElevenLabs free tier
    DEFAULT_PREMADE_BRITISH_VOICE = "JBFqnCBsd6RMkjVDRZzb"  # George (British Male)

    def __init__(self, api_key: str = None, voice_id: str = None):
        self.api_key = api_key or os.getenv("ELEVENLABS_API_KEY")
        self.voice_id = voice_id or os.getenv("ELEVENLABS_VOICE_ID", self.DEFAULT_PREMADE_BRITISH_VOICE)
        self.is_live = False
        self.client = None
        self._init_elevenlabs()

    def _init_elevenlabs(self):
        if not self.api_key or self.api_key.startswith("your_"):
            print("[Speaker] ElevenLabs credentials not provided. Running in Console Voice Mode.")
            self.is_live = False
            return

        try:
            from elevenlabs.client import ElevenLabs
            self.client = ElevenLabs(api_key=self.api_key)
            self.is_live = True
            print(f"[Speaker] ElevenLabs Voice Engine ACTIVE with Voice ID: {self.voice_id}")
        except Exception as e:
            print(f"[Speaker Warning] Failed to initialize ElevenLabs client ({e}). Fallback to Console Voice Mode.")
            self.is_live = False

    def update_credentials(self, api_key: str, voice_id: str):
        self.api_key = api_key
        self.voice_id = voice_id
        self._init_elevenlabs()

    def _play_audio_stream(self, audio_generator):
        """Plays audio stream chunks using pygame mixer without requiring ffplay/ffmpeg."""
        import pygame
        # Collect audio chunks into a byte buffer (raises ApiError if 402 on unpaid voices)
        audio_bytes = b"".join(chunk for chunk in audio_generator if isinstance(chunk, bytes))
        if not audio_bytes:
            return

        if not pygame.mixer.get_init():
            pygame.mixer.init()

        sound_file = io.BytesIO(audio_bytes)
        pygame.mixer.music.load(sound_file)
        pygame.mixer.music.play()

        # Wait until playback finishes
        while pygame.mixer.music.get_busy():
            time.sleep(0.05)

    def speak(self, text: str, play_audio: bool = True):
        """
        Synthesizes speech via ElevenLabs.
        Falls back to premade British voice if user is on Free Tier and requested library voice.
        Plays directly through Windows audio output using pygame mixer.
        """
        if not text or not text.strip():
            return

        print(f"\n=======================================================")
        print(f"[J.A.R.V.I.S.]: \"{text.strip()}\"")
        print(f"=======================================================\n")

        if self.is_live and play_audio:
            try:
                # 1. Try target Voice ID
                audio_stream = self.client.text_to_speech.convert(
                    voice_id=self.voice_id,
                    text=text,
                    model_id="eleven_multilingual_v2"
                )
                self._play_audio_stream(audio_stream)
            except Exception as e:
                err_str = str(e)
                if "Free users cannot use library voices" in err_str or "paid_plan_required" in err_str or "402" in err_str:
                    print(f"ℹ️  [Speaker Info] Voice ID '{self.voice_id}' is a community library voice requiring a paid ElevenLabs tier.")
                    print(f"🔄 [Speaker Fallback] Using Premade British Butler Voice (George) for audio synthesis...")
                    try:
                        audio_stream = self.client.text_to_speech.convert(
                            voice_id=self.DEFAULT_PREMADE_BRITISH_VOICE,
                            text=text,
                            model_id="eleven_multilingual_v2"
                        )
                        self._play_audio_stream(audio_stream)
                    except Exception as fallback_err:
                        print(f"[Speaker Error] Fallback voice synthesis error: {fallback_err}")
                else:
                    print(f"[Speaker Error] Audio synthesis error: {e}")
