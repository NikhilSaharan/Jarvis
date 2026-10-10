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
from core.heartbeat import HeartbeatDaemon

class JarvisOrchestrator:
    def __init__(self, start_heartbeat: bool = True):
        self.state = JarvisState()
        self.win_ctrl = WindowsController()
        self.db = KnowledgeDatabase()
        self.brain = UniversalGeminiBrain()
        self.executor = ToolExecutor(self.win_ctrl, self.db, self.state)
        self.speaker = JarvisSpeaker()
        self.heartbeat = HeartbeatDaemon(self.state, self.win_ctrl, self.db, self.speaker)

        if start_heartbeat:
            self.heartbeat.start()

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
