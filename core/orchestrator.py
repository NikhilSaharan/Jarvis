"""
Master Orchestrator for J.A.R.V.I.S.
Integrates Perception -> Gemini 3.1 Flash Lite -> Tool Execution -> ElevenLabs Speech.
"""
from core.state_machine import JarvisState
from os_engine.windows_control import WindowsController
from memory.database import KnowledgeDatabase
from brain.gemini_client import UniversalGeminiBrain
from brain.tool_executor import ToolExecutor
from speech.speaker import JarvisSpeaker
from speech.listener import JarvisListener
from core.heartbeat import HeartbeatDaemon

class JarvisOrchestrator:
    def __init__(self, start_heartbeat: bool = True, start_listener: bool = False):
        self.state = JarvisState()
        self.win_ctrl = WindowsController()
        self.db = KnowledgeDatabase()
        self.brain = UniversalGeminiBrain()
        self.executor = ToolExecutor(self.win_ctrl, self.db, self.state, brain=self.brain)
        self.speaker = JarvisSpeaker()
        self.heartbeat = HeartbeatDaemon(self.state, self.win_ctrl, self.db, self.speaker)
        self.listener = JarvisListener()

        if start_heartbeat:
            self.heartbeat.start()

        if start_listener:
            self.start_voice_listening()

    def start_voice_listening(self):
        """Starts 24/7 background voice listening for wake words ('Jarvis', 'Hey Jarvis', 'Sun Jarvis')."""
        def _on_command(cmd: str):
            if cmd:
                print(f"[Orchestrator Voice Dispatch] Processing command: '{cmd}'")
                self.process_command(cmd, speak_output=True)

        def _on_wake():
            print("[Orchestrator] Wake word confirmed. Standing by for Sir's orders.")

        self.listener.start_background_listening(callback=_on_command, on_wake=_on_wake)

    def stop_voice_listening(self):
        """Stops background voice listener."""
        self.listener.stop_background_listening()

    def process_command(self, user_text: str, speak_output: bool = True) -> dict:
        """
        Executes an end-to-end user request:
        1. Gathers current system context (telemetry, state)
        2. Queries Gemini 3.1 Flash Lite with universal function calling
        3. Executes all resulting tool calls on Windows OS
        4. Speaks out the response through ElevenLabs Voice
        """
        context = {
            "current_time": self.win_ctrl.get_system_telemetry().get("active_window"),
            "state": self.state.current_state.value,
            "telemetry": self.win_ctrl.get_system_telemetry()
        }

        # Step 1: Cognitive Processing with Gemini 3.1 Flash Lite
        brain_response = self.brain.process_natural_input(user_text, context)
        speech_text = brain_response.get("speech_text", "")
        tool_calls = brain_response.get("tool_calls", [])

        # Step 2: Execute actions on Windows OS / Database
        execution_results = self.executor.execute_all(tool_calls)

        # Step 3: Vocalize response
        if speak_output and speech_text:
            self.speaker.speak(speech_text)

        return {
            "user_prompt": user_text,
            "speech_reply": speech_text,
            "tools_called": tool_calls,
            "execution_results": execution_results,
            "system_state": self.state.current_state.value
        }
