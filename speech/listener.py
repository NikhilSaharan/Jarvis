"""
Wake Word & Continuous Voice Listening Engine for J.A.R.V.I.S.
Provides:
- Microphone noise calibration and energy threshold tracking.
- Wake word detection ('Jarvis', 'Hey Jarvis', 'Sun Jarvis', 'Oye Jarvis').
- In-stream command extraction ('Jarvis, volume badha do' -> wake='Jarvis', cmd='volume badha do').
- Futuristic Iron Man audio chime on wake detection via pygame.mixer.
- Google Speech Recognition with Indian English / Hinglish ('en-IN') optimization.
- Non-blocking background listening daemon thread.
"""
import os
import sys
import time
import math
import struct
import threading
from typing import Optional, Callable, List, Tuple

import speech_recognition as sr
import pygame


class JarvisListener:
    DEFAULT_WAKE_WORDS = [
        "jarvis",
        "hey jarvis",
        "sun jarvis",
        "jarvis sun",
        "oye jarvis",
        "hello jarvis",
        "listen jarvis",
        "hi jarvis",
        "ok jarvis"
    ]

    def __init__(self, wake_words: Optional[List[str]] = None, language: str = "en-IN"):
        self.wake_words = [w.lower().strip() for w in (wake_words or self.DEFAULT_WAKE_WORDS)]
        self.language = language
        self.recognizer = sr.Recognizer()
        self.recognizer.energy_threshold = 280
        self.recognizer.dynamic_energy_threshold = True
        self.recognizer.pause_threshold = 0.85
        self.recognizer.non_speaking_duration = 0.5

        self._chime_sound = None
        self._init_chime()

        self.speaker = None
        self._conversation_session_active = False
        self._last_interaction_time = 0.0
        self.session_timeout = 30.0

        self._is_listening_bg = False
        self._bg_thread = None
        self.mic_available = False

        self._check_mic()

    def set_speaker(self, speaker):
        """Attaches speaker reference so listener pauses while Jarvis is talking."""
        self.speaker = speaker

    def keep_session_alive(self):
        """Keeps conversation session open after Jarvis finishes speaking so Sir can reply without wake word."""
        self._conversation_session_active = True
        self._last_interaction_time = time.time()

    def _init_chime(self):
        """Synthesizes a clean futuristic two-tone chime in memory via pygame.mixer."""
        try:
            if not pygame.mixer.get_init():
                pygame.mixer.init(frequency=44100, size=-16, channels=2)

            sample_rate = 44100
            duration = 0.22
            n_samples = int(sample_rate * duration)
            buf = bytearray()
            for i in range(n_samples):
                # Frequency jump: D5 (587 Hz) -> A5 (880 Hz)
                freq = 587.33 if i < n_samples // 2 else 880.0
                t = float(i) / sample_rate
                decay = 1.0 - (float(i) / n_samples)
                val = int(32767.0 * 0.25 * math.sin(2.0 * math.pi * freq * t) * decay)
                packed = struct.pack('<hh', val, val)
                buf.extend(packed)

            self._chime_sound = pygame.mixer.Sound(buffer=bytes(buf))
        except Exception as e:
            print(f"[Listener Warning] Chime init failed: {e}")
            self._chime_sound = None

    def play_chime(self):
        """Plays the futuristic audio chime when wake word is activated."""
        if self._chime_sound:
            try:
                self._chime_sound.play()
            except Exception:
                pass

    def _check_mic(self):
        """Verifies if microphone hardware is operational."""
        try:
            with sr.Microphone() as source:
                self.recognizer.adjust_for_ambient_noise(source, duration=0.4)
            self.mic_available = True
        except Exception as e:
            print(f"[Listener Warning] Microphone not ready: {e}")
            self.mic_available = False

    def is_wake_word_triggered(self, text: str) -> Tuple[bool, str]:
        """
        Checks if recognized speech contains any wake word.
        Returns (is_wake, extracted_command).
        E.g.:
          'jarvis what is the time' -> (True, 'what is the time')
          'hey jarvis volume badhao' -> (True, 'volume badhao')
          'jarvis' -> (True, '')
        """
        if not text:
            return False, ""

        clean_text = text.lower().strip()

        # Check multi-word wake words first (e.g. 'hey jarvis', 'sun jarvis')
        sorted_wake_words = sorted(self.wake_words, key=len, reverse=True)
        for wake in sorted_wake_words:
            if wake in clean_text:
                # Extract command after the wake word
                idx = clean_text.find(wake)
                command_after = clean_text[idx + len(wake):].strip()
                # Remove leading commas or punctuation
                command_after = command_after.lstrip(",.?! ")
                return True, command_after

        return False, ""

    def transcribe_audio(self, audio: sr.AudioData) -> str:
        """Transcribes recorded audio data into text via Google Speech Recognition."""
        try:
            # Primary: Indian English / Hinglish
            return self.recognizer.recognize_google(audio, language=self.language).strip()
        except sr.UnknownValueError:
            return ""
        except sr.RequestError:
            try:
                # Fallback to standard en-US if en-IN endpoint is busy
                return self.recognizer.recognize_google(audio, language="en-US").strip()
            except Exception:
                return ""
        except Exception:
            return ""

    def listen_once(self, timeout: float = 5.0, phrase_time_limit: float = 8.0) -> str:
        """Captures a single audio phrase and returns the transcribed text."""
        if not self.mic_available:
            return ""

        try:
            with sr.Microphone() as source:
                audio = self.recognizer.listen(source, timeout=timeout, phrase_time_limit=phrase_time_limit)
                return self.transcribe_audio(audio)
        except sr.WaitTimeoutError:
            return ""
        except Exception as e:
            print(f"[Listener Error] Error capturing audio: {e}")
            return ""

    def start_background_listening(self, callback: Callable[[str], None], on_wake: Optional[Callable[[], None]] = None):
        """
        Starts a 24/7 background worker thread that listens continuously.
        When a wake word is detected, it plays the chime, triggers on_wake(),
        and passes the extracted command (or prompts for command) to callback.
        """
        if self._is_listening_bg:
            return

        self._is_listening_bg = True
        self._conversation_session_active = False
        self._last_interaction_time = 0.0
        self.session_timeout = 30.0

        def _loop():
            print("[Listener Daemon] Continuous Wake-Word & Conversational Flow ACTIVE ('Jarvis' / 'Hey Jarvis').")
            while self._is_listening_bg:
                try:
                    with sr.Microphone() as source:
                        self.recognizer.adjust_for_ambient_noise(source, duration=0.2)
                        while self._is_listening_bg:
                            # Do not capture audio while Jarvis is speaking through speakers
                            if self.speaker and getattr(self.speaker, "is_speaking", False):
                                time.sleep(0.3)
                                if self._conversation_session_active:
                                    self._last_interaction_time = time.time()
                                continue

                            try:
                                audio = self.recognizer.listen(source, timeout=1.5, phrase_time_limit=7.0)
                            except sr.WaitTimeoutError:
                                # Check session timeout
                                if self._conversation_session_active and (time.time() - self._last_interaction_time > self.session_timeout):
                                    self._conversation_session_active = False
                                    print("[Listener] Conversational session idle timeout. Standing by for 'Hey Jarvis'...")
                                continue

                            text = self.transcribe_audio(audio)
                            if not text:
                                if self._conversation_session_active and (time.time() - self._last_interaction_time > self.session_timeout):
                                    self._conversation_session_active = False
                                    print("[Listener] Conversational session idle timeout. Standing by for 'Hey Jarvis'...")
                                continue

                            text_lower = text.lower().strip()

                            # 1. Dismiss phrases
                            dismiss_phrases = ["thank you", "thanks", "bye", "bas jarvis", "bas itna hi", "so jao", "rest karo", "shant ho jao"]
                            if any(dp in text_lower for dp in dismiss_phrases):
                                self._conversation_session_active = False
                                print(f"[Listener] User ended session: '{text}'. Returning to wake-word standby.")
                                self.play_chime()
                                continue

                            # 2. If in active conversational session, accept speech directly without wake word
                            if self._conversation_session_active and (time.time() - self._last_interaction_time <= self.session_timeout):
                                self._last_interaction_time = time.time()
                                is_wake, cmd = self.is_wake_word_triggered(text)
                                cmd_to_run = cmd if (is_wake and cmd) else text
                                print(f"[Listener Conversational Flow] Speech recognized: '{cmd_to_run}'")
                                callback(cmd_to_run)
                                continue

                            # 3. Standard Wake Word Detection
                            is_wake, command = self.is_wake_word_triggered(text)
                            if is_wake:
                                self.play_chime()
                                self._conversation_session_active = True
                                self._last_interaction_time = time.time()
                                if on_wake:
                                    on_wake()

                                if command:
                                    print(f"[Listener] Wake detected with command: '{command}'")
                                    callback(command)
                                else:
                                    print("[Listener] Wake word detected. Listening for command (Flow Mode ACTIVE)...")
                                    try:
                                        follow_up_audio = self.recognizer.listen(source, timeout=4.0, phrase_time_limit=8.0)
                                        follow_up_text = self.transcribe_audio(follow_up_audio)
                                        if follow_up_text:
                                            self._last_interaction_time = time.time()
                                            print(f"[Listener] Received command: '{follow_up_text}'")
                                            callback(follow_up_text)
                                    except sr.WaitTimeoutError:
                                        print("[Listener] Flow mode active for 25s.")
                except Exception as e:
                    time.sleep(1.0)

        self._bg_thread = threading.Thread(target=_loop, daemon=True)
        self._bg_thread.start()

    def stop_background_listening(self):
        """Stops background listener thread."""
        self._is_listening_bg = False
        if self._bg_thread and self._bg_thread.is_alive():
            self._bg_thread.join(timeout=2.0)
        print("[Listener Daemon] Background Voice Monitoring STOPPED.")
