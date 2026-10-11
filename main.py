"""
J.A.R.V.I.S. (Just A Rather Very Intelligent System)
Master Autonomous 24/7 Windows Companion & Omni-Agent Runner.
"""
import os
import sys
import time
import signal
from pathlib import Path

# Ensure UTF-8 output on Windows console
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

# Ensure project root is in sys.path
PROJECT_ROOT = Path(__file__).resolve().parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from core.orchestrator import JarvisOrchestrator

BANNER = r"""
======================================================================
   ██╗ █████╗ ██████╗ ██╗   ██╗██╗███████╗
   ██║██╔══██╗██╔══██╗██║   ██║██║██╔════╝
   ██║███████║██████╔╝██║   ██║██║███████╗
██╗██║██╔══██║██╔══██╗╚██╗ ██╔╝██║╚════██║
╚█████║██║  ██║██║  ██║ ╚████╔╝ ██║███████║
 ╚════╝╚═╝  ╚═╝╚═╝  ╚═╝  ╚═══╝  ╚═╝╚══════╝
   J.A.R.V.I.S. (Just A Rather Very Intelligent System)
   Iron Man Autonomous Windows Laptop & Browser Omni-Agent
======================================================================
"""

def print_hud_status(orchestrator: JarvisOrchestrator):
    telem = orchestrator.win_ctrl.get_system_telemetry()
    vol = orchestrator.win_ctrl.get_volume_percent()
    is_muted = orchestrator.win_ctrl.is_muted()
    voice_mode = "ElevenLabs LIVE" if orchestrator.speaker.is_live else "Console Voice Emulation"
    brain_mode = f"Gemini High-Speed ({orchestrator.brain.model_name})" if orchestrator.brain.is_connected else "Local Cognitive Emulation"
    mic_status = "ONLINE (Ready for 'Hey Jarvis')" if orchestrator.listener.mic_available else "OFFLINE (No input device)"

    print(BANNER)
    print(f" [SYSTEM STATUS]")
    print(f"  * Core Brain:       {brain_mode}")
    print(f"  * Voice Engine:     {voice_mode}")
    print(f"  * Wake Microphone:  {mic_status}")
    print(f"  * Active Window:    {telem.get('active_window', 'N/A')}")
    print(f"  * Master Audio:     {vol}% {'[MUTED]' if is_muted else '[ACTIVE]'}")
    print(f"  * Battery Level:    {telem.get('battery_percent', 'N/A')}% (Plugged: {telem.get('power_plugged', True)})")
    print(f"  * Heartbeat Daemon: ACTIVE (2-Second Autonomous Pulse)")
    print(f"  * System State:     {orchestrator.state.current_state.value.upper()}")
    print("======================================================================\n")


def main():
    print("[Initializing J.A.R.V.I.S. Core Systems...]")
    orchestrator = JarvisOrchestrator(start_heartbeat=True, start_listener=False)
    orchestrator.win_ctrl.unmute_audio()
    print_hud_status(orchestrator)

    # Initial boot greeting in natural casual Hinglish
    greeting = "Systems fully online hain, Sir. Main aapki kya madad kar sakta hoon?"
    orchestrator.speaker.speak(greeting)

    # Ask user if they wish to start 24/7 background voice listening
    if orchestrator.listener.mic_available:
        print("\n[VOICE ASSISTANT READY]")
        print("  Type commands directly, OR type 'voice on' to start background microphone listening ('Hey Jarvis').")
    else:
        print("\n[TEXT ASSISTANT READY] Type your commands or queries below:")

    print("  (Type 'exit' or 'quit' to terminate J.A.R.V.I.S.)\n")

    voice_active = False

    def handle_exit(sig=None, frame=None):
        print("\n[J.A.R.V.I.S.] Powering down subsystems. Have a pleasant day, Sir.")
        if voice_active:
            orchestrator.stop_voice_listening()
        orchestrator.heartbeat.stop()
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_exit)

    while True:
        try:
            prompt = input("Sir > ").strip()
            if not prompt:
                continue

            if prompt.lower() in ("exit", "quit", "bye", "shutdown"):
                handle_exit()

            if prompt.lower() == "voice on":
                if not voice_active:
                    orchestrator.win_ctrl.unmute_audio()
                    orchestrator.start_voice_listening()
                    voice_active = True
                    print("[Voice Mode] Background listening active! Speak 'Jarvis' or 'Hey Jarvis' at any time.")
                else:
                    print("[Voice Mode] Already running.")
                continue

            if prompt.lower() == "voice off":
                if voice_active:
                    orchestrator.stop_voice_listening()
                    voice_active = False
                    print("[Voice Mode] Background listening deactivated.")
                else:
                    print("[Voice Mode] Voice listener is not currently active.")
                continue

            # Process command through Gemini Brain & Tool Executor
            res = orchestrator.process_command(prompt, speak_output=True)

        except (KeyboardInterrupt, EOFError):
            handle_exit()
        except Exception as e:
            print(f"[Error] {e}")


if __name__ == "__main__":
    main()
