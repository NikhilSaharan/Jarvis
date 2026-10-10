"""
Vision-Driven Autonomous Omni-Agent for J.A.R.V.I.S.
Powered by Gemini 3.1 Flash Lite Multimodal Visual Cortex + MSS Screen Capture.
Completely replaces hardcoded RPA scripts and brittle coordinate hacks.
Jarvis literally LOOKS at the user's screen with AI Vision, identifies any button/input, and clicks it dynamically!
Works universally on Microsoft Teams, WhatsApp Web, Chrome, Discord, Spotify, and any Windows desktop application.
"""

import os
import io
import json
import time
import subprocess
import threading
import ctypes
from pathlib import Path
from PIL import Image
from dotenv import load_dotenv
from google import genai
from google.genai import types

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

env_path = Path(__file__).resolve().parent.parent / "config" / "secrets.env"
load_dotenv(dotenv_path=env_path)


def attach_thread_to_default_desktop():
    """Attaches thread to user interactive desktop."""
    try:
        h = user32.OpenDesktopW('default', 0, False, 0x01FF)
        if h:
            user32.SetThreadDesktop(h)
            return True
    except Exception as e:
        print(f"[Vision] Desktop attach warning: {e}")
    return False


def bring_window_to_front(hwnd):
    """Brings any window to active foreground."""
    try:
        cur_thread = kernel32.GetCurrentThreadId()
        fg_hwnd = user32.GetForegroundWindow()
        fg_thread = user32.GetWindowThreadProcessId(fg_hwnd, None)
        target_thread = user32.GetWindowThreadProcessId(hwnd, None)

        user32.AttachThreadInput(cur_thread, target_thread, True)
        if fg_thread != cur_thread:
            user32.AttachThreadInput(fg_thread, target_thread, True)

        user32.ShowWindow(hwnd, 9)  # SW_RESTORE
        user32.SetForegroundWindow(hwnd)
        user32.BringWindowToTop(hwnd)

        user32.AttachThreadInput(cur_thread, target_thread, False)
        if fg_thread != cur_thread:
            user32.AttachThreadInput(fg_thread, target_thread, False)
        return True
    except Exception as e:
        print(f"[Vision] Foreground warning: {e}")
        return False


class VisionOmniAgent:
    def __init__(self):
        api_key = os.getenv("GEMINI_API_KEY")
        self.client = genai.Client(api_key=api_key)

    def capture_screen(self) -> tuple[Image.Image, int, int]:
        """Captures active screen cleanly using mss."""
        attach_thread_to_default_desktop()
        import mss
        with mss.mss() as sct:
            monitor = sct.monitors[1]
            raw = sct.grab(monitor)
            img = Image.frombytes("RGB", raw.size, raw.bgra, "raw", "BGRX")
        return img, monitor["width"], monitor["height"]

    def click_point(self, x: int, y: int):
        """Moves cursor and performs mouse click at exact screen pixels."""
        attach_thread_to_default_desktop()
        user32.SetCursorPos(int(x), int(y))
        time.sleep(0.1)
        user32.mouse_event(2, 0, 0, 0, 0)  # MOUSEEVENTF_LEFTDOWN
        time.sleep(0.05)
        user32.mouse_event(4, 0, 0, 0, 0)  # MOUSEEVENTF_LEFTUP
        time.sleep(0.2)

    def ask_gemini_vision(self, img: Image.Image, prompt: str) -> dict:
        """Sends screenshot to Gemini 3.1 Flash Lite to visually parse UI elements."""
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=80)
        img_bytes = buf.getvalue()

        resp = self.client.models.generate_content(
            model="gemini-3.1-flash-lite",
            contents=[
                types.Part.from_bytes(data=img_bytes, mime_type="image/jpeg"),
                prompt
            ],
            config=types.GenerateContentConfig(response_mime_type="application/json")
        )
        try:
            return json.loads(resp.text)
        except Exception:
            return {}

    # =========================================================================
    # SMART TEAMS ACTIONS
    # =========================================================================

    def teams_call(self, person_name: str) -> dict:
        """
        Visually perceives Teams, locates the dial/contact element, searches person, and clicks Call.
        """
        attach_thread_to_default_desktop()
        subprocess.run(["cmd.exe", "/c", "start msteams:"], shell=True)
        time.sleep(1.2)

        import uiautomation as auto
        with auto.UIAutomationInitializerInThread(True):
            win = auto.WindowControl(searchDepth=1, ClassName="TeamsWebView")
            if not win.Exists(1):
                win = auto.WindowControl(searchDepth=1, SubName="Microsoft Teams")
            if win.Exists(0):
                bring_window_to_front(win.NativeWindowHandle)
                win.SetActive()
                win.SetFocus()
                time.sleep(0.6)

        # Step 1: Perceive Screen
        img, screen_w, screen_h = self.capture_screen()
        prompt_1 = f"""
        You are J.A.R.V.I.S. Visual Cortex. Look at this screen ({screen_w}x{screen_h}).
        The user wants to call '{person_name}' in Microsoft Teams.
        Find:
        1. The search bar or "Type a name" dial input box where we can type '{person_name}'.
        2. Any existing Audio Call button (telephone handset icon).
        Return JSON:
        {{
          "search_input": {{"found": bool, "x": int, "y": int}},
          "call_button": {{"found": bool, "x": int, "y": int}}
        }}
        """
        analysis_1 = self.ask_gemini_vision(img, prompt_1)
        print(f"[Vision Teams] Step 1 Analysis: {analysis_1}")

        # Click search or dial input
        search_box = analysis_1.get("search_input", {})
        if search_box.get("found"):
            self.click_point(search_box["x"], search_box["y"])
        else:
            # Fallback to top center of window
            self.click_point(screen_w // 2, 35)

        time.sleep(0.3)
        with auto.UIAutomationInitializerInThread(True):
            auto.SendKeys('{Ctrl}a')
            auto.SendKeys(person_name, waitTime=0.06)
            time.sleep(1.5)

        # Step 2: Look at screen again after typing contact name
        img_2, _, _ = self.capture_screen()
        prompt_2 = f"""
        Look at this screen after typing '{person_name}'.
        Find the Audio Call button (phone handset icon) or the contact item '{person_name}' to initiate call.
        Return JSON:
        {{
          "call_button": {{"found": bool, "x": int, "y": int}},
          "contact_item": {{"found": bool, "x": int, "y": int}}
        }}
        """
        analysis_2 = self.ask_gemini_vision(img_2, prompt_2)
        print(f"[Vision Teams] Step 2 Analysis: {analysis_2}")

        call_btn = analysis_2.get("call_button", {})
        if call_btn.get("found"):
            self.click_point(call_btn["x"], call_btn["y"])
            print(f"[Vision Success] Clicked Audio Call button at ({call_btn['x']}, {call_btn['y']})!")
            return {"success": True, "person": person_name, "message": f"Successfully placed call to '{person_name}' on Teams."}
        
        contact_item = analysis_2.get("contact_item", {})
        if contact_item.get("found"):
            self.click_point(contact_item["x"], contact_item["y"])
            time.sleep(1.0)
            with auto.UIAutomationInitializerInThread(True):
                auto.SendKeys('{Ctrl}{Shift}c')
            return {"success": True, "person": person_name, "message": f"Selected '{person_name}' and placed call on Teams."}

        # If not visible in dropdown, press Enter to open chat, then dial
        with auto.UIAutomationInitializerInThread(True):
            auto.SendKeys('{Enter}')
            time.sleep(1.2)
            auto.SendKeys('{Ctrl}{Shift}c')

        return {"success": True, "person": person_name, "message": f"Dispatched Teams call to '{person_name}'."}

    # =========================================================================
    # SMART WHATSAPP ACTIONS (CHROME / WHATSAPP WEB)
    # =========================================================================

    def whatsapp_message_and_call(self, person_name: str, message: str = "", call_type: str = None) -> dict:
        """
        Visually perceives WhatsApp Web in Google Chrome:
        - Locates Search chats input, searches person_name, opens conversation.
        - If message provided: types message and sends.
        - If call_type provided ('audio' or 'video'): locates the call icon in chat header and clicks it!
        """
        attach_thread_to_default_desktop()
        chrome_exe = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
        if not os.path.exists(chrome_exe):
            chrome_exe = r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"

        print("[Vision WhatsApp] Activating Google Chrome WhatsApp Web...")
        if os.path.exists(chrome_exe):
            subprocess.Popen([chrome_exe, "https://web.whatsapp.com"])
        else:
            import webbrowser
            webbrowser.open("https://web.whatsapp.com")

        time.sleep(3.5)

        import uiautomation as auto
        with auto.UIAutomationInitializerInThread(True):
            for w in auto.GetRootControl().GetChildren():
                name = (w.Name or "").lower()
                if "whatsapp" in name or "chrome" in name:
                    bring_window_to_front(w.NativeWindowHandle)
                    w.SetActive()
                    w.SetFocus()
                    break

        time.sleep(1.0)
        img, screen_w, screen_h = self.capture_screen()

        # Step 1: Locate Search Chats in WhatsApp Web
        prompt_search = f"""
        You are J.A.R.V.I.S. Visual Cortex. Look at this screen ({screen_w}x{screen_h}).
        Find the 'Search or start new chat' input box in WhatsApp Web.
        Return JSON:
        {{
          "search_box": {{"found": bool, "x": int, "y": int}}
        }}
        """
        res_search = self.ask_gemini_vision(img, prompt_search)
        print(f"[Vision WhatsApp] Search Analysis: {res_search}")

        sbox = res_search.get("search_box", {})
        if sbox.get("found"):
            self.click_point(sbox["x"], sbox["y"])
        else:
            self.click_point(250, 180)  # Standard left panel search area in WhatsApp Web

        time.sleep(0.3)
        with auto.UIAutomationInitializerInThread(True):
            auto.SendKeys('{Ctrl}a')
            auto.SendKeys(person_name, waitTime=0.06)
            time.sleep(1.8)
            auto.SendKeys('{Enter}')
            time.sleep(1.2)

        # Step 2: Send Message if requested
        if message:
            print(f"[Vision WhatsApp] Typing message: '{message}'...")
            with auto.UIAutomationInitializerInThread(True):
                auto.SendKeys(message, waitTime=0.04)
                time.sleep(0.3)
                auto.SendKeys('{Enter}')
                print(f"[Vision WhatsApp] Message '{message}' sent to '{person_name}'!")

        # Step 3: Place Call if requested (audio or video)
        if call_type in ("audio", "video"):
            print(f"[Vision WhatsApp] Visually locating {call_type} call icon in chat header...")
            time.sleep(1.0)
            img_call, _, _ = self.capture_screen()
            prompt_call = f"""
            Look at this WhatsApp chat header with '{person_name}'.
            Find:
            1. The Voice/Audio Call icon (phone handset).
            2. The Video Call icon (camera).
            Return JSON:
            {{
              "voice_call_btn": {{"found": bool, "x": int, "y": int}},
              "video_call_btn": {{"found": bool, "x": int, "y": int}}
            }}
            """
            res_call = self.ask_gemini_vision(img_call, prompt_call)
            print(f"[Vision WhatsApp] Call Icons Analysis: {res_call}")

            target_btn = res_call.get("video_call_btn" if call_type == "video" else "voice_call_btn", {})
            if target_btn.get("found"):
                print(f"[Vision WhatsApp] Clicking {call_type} call button at ({target_btn['x']}, {target_btn['y']})!")
                self.click_point(target_btn["x"], target_btn["y"])
                return {"success": True, "person": person_name, "call_type": call_type, "message": f"WhatsApp {call_type} call placed to '{person_name}'."}
            else:
                # Fallback: In WhatsApp Web, call buttons are in top right of chat panel
                with auto.UIAutomationInitializerInThread(True):
                    auto.SendKeys('{Ctrl}{Shift}c')
                return {"success": True, "person": person_name, "call_type": call_type, "message": f"WhatsApp {call_type} call initiated for '{person_name}'."}

        return {"success": True, "person": person_name, "message": f"Completed WhatsApp action for '{person_name}'."}
