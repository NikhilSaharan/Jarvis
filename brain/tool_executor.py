"""
Tool Executor for J.A.R.V.I.S.
Maps Gemini 3.1 Flash Lite function calls to real Windows OS, Teams, WhatsApp, and Database actions.
"""
import datetime
from os_engine.windows_control import WindowsController
from os_engine.communications import CommunicationsController
from memory.database import KnowledgeDatabase
from core.state_machine import JarvisState

from os_engine.computer_use import ComputerUseEngine

class ToolExecutor:
    def __init__(self, win_ctrl: WindowsController, db: KnowledgeDatabase, state: JarvisState):
        self.win_ctrl = win_ctrl
        self.comm_ctrl = CommunicationsController()
        self.computer_use = ComputerUseEngine()
        self.db = db
        self.state = state

    def execute_tool(self, tool_call: dict) -> dict:
        name = tool_call.get("name")
        args = tool_call.get("args", {})
        result_message = ""
        success = True

        if name == "activate_rest_or_dnd_mode":
            wake_time = args.get("wake_time")
            duration = args.get("duration_minutes")
            note = args.get("custom_note", "Rest")

            if duration and not wake_time:
                target_dt = datetime.datetime.now() + datetime.timedelta(minutes=duration)
                wake_time = target_dt.strftime("%H:%M")

            if args.get("block_all_disturbances", True):
                self.win_ctrl.mute_audio()
                self.state.activate_rest_mode(target_time_str=wake_time, reason=note)
            else:
                self.state.activate_dnd_mode(reason=note)

            result_message = f"Rest mode activated until {wake_time or 'further notice'}. Audio muted."

        elif name == "schedule_proactive_reminder":
            target_time = args.get("target_time")
            person = args.get("topic_or_person", "")
            speech = args.get("proactive_speech", f"Sir, reminder regarding {person}.")

            action_id = self.db.add_scheduled_action(
                target_time=target_time,
                action_type="reminder",
                target_entity=person,
                proactive_speech=speech
            )
            result_message = f"Reminder scheduled for {target_time} regarding {person} (ID: {action_id})."

        elif name == "control_hardware_and_audio":
            action = args.get("action")
            val = args.get("value")

            if action == "mute":
                self.win_ctrl.mute_audio()
                result_message = "Master volume muted."
            elif action == "unmute":
                self.win_ctrl.unmute_audio()
                result_message = "Master volume unmuted."
            elif action == "set_volume":
                new_vol = self.win_ctrl.set_volume_percent(val if val is not None else 50)
                result_message = f"Master volume set to {new_vol}%."
            elif action == "lock_screen":
                self.win_ctrl.lock_workstation()
                result_message = "Workstation locked."
            elif action == "check_battery":
                telemetry = self.win_ctrl.get_system_telemetry()
                result_message = f"Battery at {telemetry['battery_percent']}%, plugged in: {telemetry['power_plugged']}."

        elif name == "control_laptop_application":
            action = args.get("action")
            app = args.get("app_name", "")

            if action == "launch":
                res = self.computer_use.open_or_focus(app)
                result_message = res["message"]
                success = res["success"]
            elif action == "close":
                res = self.win_ctrl.close_application(app)
                result_message = res["message"]
                success = res["success"]

        elif name in ("universal_communicate", "communicate_teams", "communicate_whatsapp"):
            app = args.get("app_name", "Teams" if "teams" in name else "WhatsApp")
            action = args.get("action", "message")
            contact = args.get("contact_name") or args.get("person_name") or args.get("target", "")
            msg = args.get("message", "")
            call_type = args.get("call_type", "audio")

            if action == "call":
                res = self.computer_use.universal_call(app, contact, call_type=call_type)
            elif action == "open":
                res = self.computer_use.open_or_focus(app)
            else:
                res = self.computer_use.universal_message(app, contact, msg)

            result_message = res.get("message", "Dispatched.")
            success = res.get("success", True)

        elif name == "universal_computer_action":
            action = args.get("action")
            target = args.get("target", "")
            text = args.get("text", "")

            if action == "open_app_or_url":
                res = self.computer_use.open_or_focus(target)
            elif action == "click_button":
                res = self.computer_use.click_element_by_name(target)
            elif action == "type_text":
                res = self.computer_use.type_into_input(text, input_query=target, press_enter=True)
            elif action == "press_keys":
                res = self.computer_use.send_keyboard_shortcut(target)
            else:
                res = {"success": False, "message": f"Unknown action: {action}"}

            result_message = res.get("message", "Executed.")
            success = res.get("success", True)

        elif name == "update_personal_knowledge":
            person = args.get("person_name")
            rel = args.get("relationship", "")
            notes = args.get("notes", "")

            self.db.add_person(name=person, relationship=rel, notes=notes)
            result_message = f"Knowledge updated: {person} ({rel})."

        else:
            success = False
            result_message = f"Unknown tool call: {name}"

        return {
            "tool_name": name,
            "success": success,
            "message": result_message
        }

    def execute_all(self, tool_calls: list) -> list:
        results = []
        for call in tool_calls:
            results.append(self.execute_tool(call))
        return results
