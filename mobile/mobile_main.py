"""
Master Autonomous 24/7 Mobile Daemon for J.A.R.V.I.S.
Agentic Loop Architecture:
- Gemini operates in a multi-turn observe-act loop
- Each tool call result is fed back to Gemini for the next decision
- No blind single-shot execution — JARVIS adapts to what actually happens on screen
"""
import sys
import os
import time
from pathlib import Path

# Set up local import paths
current_dir = Path(__file__).resolve().parent
if str(current_dir) not in sys.path:
    sys.path.insert(0, str(current_dir))

from core.state_machine import JarvisState, SystemState
from core.disturbance_engine import DisturbanceEngine
from core.heartbeat import MobileHeartbeatDaemon
from memory.database import KnowledgeDatabase
from mobile_engine.android_controller import AndroidController
from mobile_engine.lock_manager import LockScreenManager
from mobile_engine.screen_perception import ScreenPerception
from mobile_engine.app_orchestrator import UniversalAppOrchestrator
from mobile_engine.telephony_calls import TelephonyManager
from mobile_engine.playstore_installer import PlayStoreInstaller
from mobile_engine.notification_watcher import NotificationWatcher
from brain.mobile_gemini_client import MobileGeminiBrain
from brain.mobile_tool_executor import MobileToolExecutor
from speech.mobile_speaker import MobileSpeaker
from speech.mobile_listener import MobileListener

class MobileJarvisOrchestrator:
    def __init__(self, start_daemons: bool = True):
        print("=======================================================")
        print("    J.A.R.V.I.S. MOBILE — AGENTIC LOOP ARCHITECTURE     ")
        print("=======================================================")

        # 1. State & Storage
        self.state = JarvisState()
        self.db = KnowledgeDatabase()
        self.disturbance_engine = DisturbanceEngine(self.state, self.db)

        # 2. Physical Android Engine Primitives
        self.ac = AndroidController()
        self.lock_mgr = LockScreenManager(self.ac)
        self.perception = ScreenPerception(self.ac)
        self.orchestrator = UniversalAppOrchestrator(self.ac, self.perception)
        self.telephony = TelephonyManager(self.ac, db=self.db)
        self.playstore = PlayStoreInstaller(self.ac, self.perception)

        # 3. Speech & Voice
        self.speaker = MobileSpeaker()

        # 4. Intelligence & Brain
        self.brain = MobileGeminiBrain()
        self.orchestrator.set_brain(self.brain)
        self.orchestrator.set_telephony(self.telephony)
        self.executor = MobileToolExecutor(
            self.ac,
            self.lock_mgr,
            self.perception,
            self.orchestrator,
            self.telephony,
            self.playstore,
            self.db,
            self.state
        )
        self.orchestrator.set_tool_executor(self.executor)

        # 5. Autonomous 24/7 Heartbeat Daemon
        self.heartbeat = MobileHeartbeatDaemon(
            self.state,
            self.db,
            self.speaker,
            self.brain,
            interval_seconds=2.0
        )
        self.executor.set_heartbeat(self.heartbeat)

        # 6. Universal Notification Watcher
        self.notification_watcher = NotificationWatcher(
            on_notification_callback=self._handle_incoming_notification,
            poll_interval=2.5
        )

        # 7. Voice Listener
        self.listener = MobileListener(on_command_callback=self.process_command)

        if start_daemons:
            self.start_all_daemons()

    def start_all_daemons(self):
        """Starts 24/7 background daemons."""
        self.heartbeat.start()
        self.notification_watcher.start()
        self.speaker.speak("Mobile systems fully operational hain Sir. Agentic loop active.")

    def _handle_incoming_notification(self, notif: dict):
        """Processes incoming live notification from any app."""
        pkg = notif.get("package", "")
        title = notif.get("title", "")
        content = notif.get("content", "")

        # Silently record in DB for debriefing / review
        try:
            self.db.add_quarantined_message(source_app=pkg, sender=title, message_content=content)
        except Exception:
            pass

        # Only speak aloud for critical alerts (e.g. OTP, phone calls)
        c_low = (content or "").lower()
        t_low = (title or "").lower()
        is_critical = any(k in c_low or k in t_low for k in ["otp", "verification code", "incoming call", "emergency"])
        if is_critical:
            announcement = f"Sir, {title}: {content}"
            print(f"[Critical Alert] {announcement}")
            self.speaker.speak(announcement)
        else:
            print(f"[Notification Logged] From {title} ({pkg})")

    def process_command(self, user_text: str) -> dict:
        """
        Processes a user command using the AGENTIC LOOP:
        1. Sends command to Gemini
        2. Gemini calls a tool → executes → result fed back
        3. Gemini decides next action → repeat
        4. Until task_complete or max turns
        """
        if not user_text or not user_text.strip():
            return {}

        print(f"\n{'='*55}")
        print(f"[Sir]: \"{user_text}\"")
        print(f"{'='*55}")

        context = {
            "state": self.state.current_state.value,
            "screen_awake": self.lock_mgr.is_screen_on()
        }

        # Run the agentic loop with instant early voice feedback
        result = self.brain.run_agentic_loop(
            user_text=user_text,
            tool_executor=self.executor,
            context=context,
            on_speech=self.speaker.speak
        )

        speech_text = result.get("speech_text", "")
        spoken_early = result.get("spoken_early", False)
        turns = result.get("turns", 0)
        tools_executed = result.get("tool_calls_executed", [])

        print(f"\n[Agentic Loop Summary] {turns} turns, {len(tools_executed)} tools executed")
        for i, tc in enumerate(tools_executed):
            print(f"  Step {i+1}: {tc['name']}({tc.get('args', {})})")

        # If speech was not already spoken early during Turn 1, speak it now
        if speech_text and not spoken_early:
            self.speaker.speak(speech_text)

        return result

if __name__ == "__main__":
    import traceback
    try:
        # Interactive CLI Test Mode
        jarvis = MobileJarvisOrchestrator(start_daemons=True)
        print("\n>> J.A.R.V.I.S. Mobile (Agentic Loop) is running. Type a command (or 'exit'):\n")
        while True:
            try:
                cmd = input("Sir > ")
            except EOFError:
                break
            if cmd.strip().lower() in ("exit", "quit"):
                break
            if cmd.strip():
                jarvis.process_command(cmd)
    except KeyboardInterrupt:
        print("\nStopping J.A.R.V.I.S. Mobile Daemon...")
        if "jarvis" in locals():
            jarvis.heartbeat.stop()
            jarvis.notification_watcher.stop()
    except Exception as e:
        print(f"\n[CRITICAL INITIALIZATION ERROR]: {e}")
        traceback.print_exc()
