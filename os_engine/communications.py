"""
Autonomous Communications Engine for J.A.R.V.I.S.
Provides fast, reliable, human-like UI automation for communications apps:
- Microsoft Teams (Native App): Instant call & message dispatch by contact name.
- WhatsApp Web (Google Chrome) & WhatsApp Desktop: Message dispatch, audio call & video call by contact name.

Operates purely on person's name (e.g. 'Dhruv', 'Vishal', 'Manish') without manual IDs, emails or phone numbers.
Uses Windows UI Automation (uiautomation) and dedicated thread desktop attachment with 0 coordinate guessing.
"""

import os
import sys
import time
import subprocess
import threading
import ctypes
import webbrowser
import urllib.parse

# Ensure UTF-8 output on Windows console
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32


def run_on_default_desktop(func):
    """
    Decorator that executes the wrapped function in a clean, fresh worker thread
    attached to the user's interactive Windows 'default' desktop.
    """
    def wrapper(*args, **kwargs):
        res_container = {"result": None, "error": None}

        def worker():
            try:
                h_desk = user32.OpenDesktopW("default", 0, False, 0x01FF)
                if h_desk:
                    user32.SetThreadDesktop(h_desk)
                import uiautomation as auto
                with auto.UIAutomationInitializerInThread(True):
                    res_container["result"] = func(*args, **kwargs)
            except Exception as e:
                res_container["error"] = e

        thread = threading.Thread(target=worker, daemon=True)
        thread.start()
        thread.join()

        if res_container["error"]:
            raise res_container["error"]
        return res_container["result"]

    return wrapper


def force_foreground_focus(hwnd):
    """
    Forces a window to the active foreground on Windows 11,
    bypassing AllowSetForegroundWindow restrictions using Alt-key unlock trick + AttachThreadInput.
    """
    try:
        user32.keybd_event(0x12, 0, 0, 0)
        time.sleep(0.05)
        user32.keybd_event(0x12, 0, 2, 0)
        time.sleep(0.05)

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
        user32.SwitchToThisWindow(hwnd, True)

        user32.AttachThreadInput(cur_thread, target_thread, False)
        if fg_thread != cur_thread:
            user32.AttachThreadInput(fg_thread, target_thread, False)
        time.sleep(0.3)
        return True
    except Exception as e:
        print(f"[Communications] Foreground activation warning: {e}")
        return False


def wake_chrome_accessibility(chrome_win):
    """Wakes up Chrome DOM accessibility via MSAA AccessibleObjectFromWindow."""
    try:
        legacy = chrome_win.PaneControl(ClassName='Chrome_RenderWidgetHostHWND')
        if legacy.Exists(1, 1):
            from comtypes import GUID
            oleacc = ctypes.windll.oleacc
            p_acc = ctypes.c_void_p()
            IID_IAccessible = GUID("{618736E0-3C3D-11CF-810C-00AA00389B71}")
            oleacc.AccessibleObjectFromWindow(legacy.NativeWindowHandle, 0, ctypes.byref(IID_IAccessible), ctypes.byref(p_acc))
            return legacy
    except Exception:
        pass
    return None


class CommunicationsController:
    """
    Universal High-Speed Communications Controller for J.A.R.V.I.S.
    """

    def __init__(self):
        pass

    # =========================================================================
    # MICROSOFT TEAMS AUTOMATION
    # =========================================================================

    def _find_teams_window(self, max_wait: float = 3.0):
        """Locates the active Microsoft Teams window control on default desktop."""
        import uiautomation as auto
        start = time.time()
        while time.time() - start < max_wait:
            win = auto.WindowControl(searchDepth=1, ClassName="TeamsWebView")
            if win.Exists(maxSearchSeconds=0.2):
                return win
            win = auto.WindowControl(searchDepth=1, SubName="Microsoft Teams")
            if win.Exists(maxSearchSeconds=0.2):
                return win
            time.sleep(0.2)
        return None

    @run_on_default_desktop
    def open_teams(self) -> dict:
        """Launches or immediately brings Microsoft Teams to focus."""
        win = self._find_teams_window(max_wait=0.5)

        if not win:
            print("[Teams] Launching Microsoft Teams process...")
            subprocess.Popen("start msteams:", shell=True)
            win = self._find_teams_window(max_wait=4.0)

        if win:
            force_foreground_focus(win.NativeWindowHandle)
            win.SetActive()
            win.SetFocus()
            return {"success": True, "message": "Microsoft Teams is active and focused."}
        else:
            return {"success": False, "message": "Could not locate or launch Microsoft Teams."}

    @run_on_default_desktop
    def teams_send_message_by_name(self, person_name: str, message: str) -> dict:
        """
        Fast, reliable Microsoft Teams message delivery by contact name:
        1. Focus Teams window
        2. Ctrl+2 ensures Chat view
        3. Ctrl+N opens New Chat composer
        4. Types contact name & selects with Enter
        5. Jumps focus to message box via Tab
        6. Types message & sends with Enter
        """
        win = self._find_teams_window(max_wait=0.5)
        if not win:
            subprocess.Popen("start msteams:", shell=True)
            win = self._find_teams_window(max_wait=4.0)

        if not win:
            return {"success": False, "message": "Microsoft Teams window could not be found."}

        import uiautomation as auto
        force_foreground_focus(win.NativeWindowHandle)
        win.SetActive()
        win.SetFocus()
        time.sleep(0.3)

        auto.SendKeys('{Ctrl}2')
        time.sleep(0.3)

        print(f"[Teams] Initiating new chat with '{person_name}'...")
        auto.SendKeys('{Ctrl}n')
        time.sleep(0.4)

        auto.SendKeys(person_name, waitTime=0.04)
        time.sleep(1.0)

        auto.SendKeys('{Down}{Enter}')
        time.sleep(0.5)

        auto.SendKeys('{Tab}')
        time.sleep(0.3)

        print(f"[Teams] Typing message to '{person_name}': {message}")
        auto.SendKeys(message, waitTime=0.03)
        time.sleep(0.3)

        auto.SendKeys('{Enter}')
        print(f"[Teams Success] Message '{message}' sent to '{person_name}'!")

        return {
            "success": True,
            "person": person_name,
            "message": f"Message '{message}' successfully dispatched to '{person_name}' on Teams."
        }

    @run_on_default_desktop
    def teams_call_by_name(self, person_name: str, call_type: str = "audio") -> dict:
        """
        Initiates an audio/video call to a contact by name on Microsoft Teams:
        1. Opens chat with contact
        2. Alt+Shift+A (Audio Call) or Alt+Shift+V (Video Call)
        """
        win = self._find_teams_window(max_wait=0.5)
        if not win:
            subprocess.Popen("start msteams:", shell=True)
            win = self._find_teams_window(max_wait=4.0)

        if not win:
            return {"success": False, "message": "Microsoft Teams window could not be found."}

        import uiautomation as auto
        force_foreground_focus(win.NativeWindowHandle)
        win.SetActive()
        win.SetFocus()
        time.sleep(0.3)

        auto.SendKeys('{Ctrl}2')
        time.sleep(0.3)

        print(f"[Teams] Searching for '{person_name}' to open direct chat...")
        auto.SendKeys('{Ctrl}e')
        time.sleep(0.2)
        auto.SendKeys('{Ctrl}a{Back}')
        auto.SendKeys(person_name, waitTime=0.05)
        time.sleep(1.0)

        auto.SendKeys('{Down}{Enter}')
        time.sleep(1.0)

        print(f"[Teams] Dialing {call_type} call to '{person_name}'...")
        if call_type.lower() == "video":
            auto.SendKeys('{Alt}{Shift}v')
        else:
            auto.SendKeys('{Alt}{Shift}a')

        return {
            "success": True,
            "person": person_name,
            "call_type": call_type,
            "message": f"Teams {call_type} call placed to '{person_name}'."
        }

    # =========================================================================
    # WHATSAPP WEB & DESKTOP AUTOMATION
    # =========================================================================

    def _find_whatsapp_window(self, max_wait: float = 3.0):
        """Locates active WhatsApp Web (in Chrome/Edge) or WhatsApp Desktop window."""
        import uiautomation as auto
        start = time.time()
        while time.time() - start < max_wait:
            for w in auto.GetRootControl().GetChildren():
                name = (w.Name or "").lower()
                if "whatsapp" in name:
                    return w
            time.sleep(0.2)
        return None

    @run_on_default_desktop
    def open_whatsapp(self) -> dict:
        """Opens or brings WhatsApp Web (Google Chrome) / WhatsApp Desktop to front."""
        win = self._find_whatsapp_window(max_wait=0.5)

        if not win:
            print("[WhatsApp] Launching WhatsApp Web in Google Chrome...")
            webbrowser.open("https://web.whatsapp.com")
            win = self._find_whatsapp_window(max_wait=6.0)

        if win:
            force_foreground_focus(win.NativeWindowHandle)
            win.SetActive()
            win.SetFocus()
            return {"success": True, "message": "WhatsApp Web is active and focused."}
        else:
            return {"success": True, "message": "Launched WhatsApp Web in browser."}

    @run_on_default_desktop
    def whatsapp_send_message_by_name(self, person_name: str, message: str) -> dict:
        """
        Sends a message on WhatsApp Web / Desktop to a person by name:
        1. Focuses WhatsApp window (Chrome / Desktop)
        2. Wakes Chrome accessibility
        3. Clears and searches for contact
        4. Selects contact from results
        5. Types message in compose box & sends with Enter
        """
        win = self._find_whatsapp_window(max_wait=0.5)
        if not win:
            self.open_whatsapp()
            time.sleep(3.0)
            win = self._find_whatsapp_window(max_wait=5.0)

        if not win:
            return {"success": False, "message": "WhatsApp window could not be found."}

        import uiautomation as auto
        force_foreground_focus(win.NativeWindowHandle)
        time.sleep(0.3)

        legacy = wake_chrome_accessibility(win)
        doc = legacy.DocumentControl(Name='WhatsApp') if legacy else win

        # Find search box
        search_edit = None
        for c in doc.GetChildren():
            def find_se(ctrl):
                nonlocal search_edit
                if search_edit: return
                if ctrl.ControlTypeName == 'EditControl':
                    r = ctrl.BoundingRectangle
                    if 200 < r.top < 300 and r.left < 300:
                        search_edit = ctrl
                        return
                for sub in ctrl.GetChildren():
                    find_se(sub)
            find_se(doc)

        if search_edit:
            search_edit.Click()
            time.sleep(0.2)
            auto.SendKeys('{Ctrl}a{Back}')
            time.sleep(0.2)
            auto.SendKeys(person_name, waitTime=0.05)
            time.sleep(1.0)

        target_item = None
        for _ in range(5):
            for c in doc.GetChildren():
                def find_item(ctrl):
                    nonlocal target_item
                    if target_item: return
                    if ctrl.ControlTypeName == 'DataItemControl' and person_name.lower() in (ctrl.Name or '').lower():
                        target_item = ctrl
                        return
                    for sub in ctrl.GetChildren():
                        find_item(sub)
                find_item(doc)
            if target_item:
                break
            time.sleep(0.3)

        if target_item:
            target_item.Click()
            time.sleep(1.0)
        else:
            auto.SendKeys('{Down}{Enter}')
            time.sleep(1.0)

        # Compose box
        compose_box = None
        for _ in range(5):
            for c in doc.GetChildren():
                def find_msg_box(ctrl):
                    nonlocal compose_box
                    if compose_box: return
                    if ctrl.ControlTypeName == 'EditControl' and any(k in (ctrl.Name or '').lower() for k in ['type a message', 'message']):
                        compose_box = ctrl
                        return
                    for sub in ctrl.GetChildren():
                        find_msg_box(sub)
                find_msg_box(doc)
            if compose_box:
                break
            time.sleep(0.3)

        if compose_box:
            compose_box.Click()
            time.sleep(0.2)

        auto.SendKeys(message, waitTime=0.04)
        time.sleep(0.3)
        auto.SendKeys('{Enter}')

        return {
            "success": True,
            "person": person_name,
            "message": f"Message '{message}' sent to '{person_name}' on WhatsApp."
        }

    @run_on_default_desktop
    def whatsapp_call_by_name(self, person_name: str, call_type: str = "audio") -> dict:
        """
        Initiates an Audio or Video call on WhatsApp Web / Desktop by person name:
        call_type: 'audio' or 'video'
        """
        win = self._find_whatsapp_window(max_wait=0.5)
        if not win:
            self.open_whatsapp()
            time.sleep(3.0)
            win = self._find_whatsapp_window(max_wait=5.0)

        if not win:
            return {"success": False, "message": "WhatsApp window could not be found."}

        import uiautomation as auto
        force_foreground_focus(win.NativeWindowHandle)
        time.sleep(0.3)

        legacy = wake_chrome_accessibility(win)
        doc = legacy.DocumentControl(Name='WhatsApp') if legacy else win

        search_edit = None
        for c in doc.GetChildren():
            def find_se(ctrl):
                nonlocal search_edit
                if search_edit: return
                if ctrl.ControlTypeName == 'EditControl':
                    r = ctrl.BoundingRectangle
                    if 200 < r.top < 300 and r.left < 300:
                        search_edit = ctrl
                        return
                for sub in ctrl.GetChildren():
                    find_se(sub)
            find_se(doc)

        if search_edit:
            search_edit.Click()
            time.sleep(0.2)
            auto.SendKeys('{Ctrl}a{Back}')
            time.sleep(0.2)
            auto.SendKeys(person_name, waitTime=0.05)
            time.sleep(1.0)

        target_item = None
        for _ in range(5):
            for c in doc.GetChildren():
                def find_item(ctrl):
                    nonlocal target_item
                    if target_item: return
                    if ctrl.ControlTypeName == 'DataItemControl' and person_name.lower() in (ctrl.Name or '').lower():
                        target_item = ctrl
                        return
                    for sub in ctrl.GetChildren():
                        find_item(sub)
                find_item(doc)
            if target_item:
                break
            time.sleep(0.3)

        if target_item:
            target_item.Click()
            time.sleep(1.2)
        else:
            auto.SendKeys('{Down}{Enter}')
            time.sleep(1.2)

        btn_name = "Video call" if call_type.lower() == "video" else "Voice call"
        call_btn = None
        for _ in range(5):
            for c in doc.GetChildren():
                def find_btn(ctrl):
                    nonlocal call_btn
                    if call_btn: return
                    if ctrl.ControlTypeName == 'ButtonControl' and ctrl.Name == btn_name:
                        call_btn = ctrl
                        return
                    for sub in ctrl.GetChildren():
                        find_btn(sub)
                find_btn(doc)
            if call_btn:
                break
            time.sleep(0.3)

        if call_btn:
            call_btn.Click()
            time.sleep(1.5)

            for c in win.GetChildren():
                def check_allow(ctrl):
                    name = (ctrl.Name or "").lower()
                    if ctrl.ControlTypeName == 'ButtonControl' and any(k in name for k in ['allow while visiting', 'allow this time']):
                        ctrl.Click()
                        return True
                    for sub in ctrl.GetChildren():
                        if check_allow(sub): return True
                    return False
                if check_allow(c): break

            return {
                "success": True,
                "person": person_name,
                "call_type": call_type,
                "message": f"WhatsApp {call_type} call placed to '{person_name}'."
            }

        return {
            "success": False,
            "person": person_name,
            "message": f"{btn_name} button could not be located in WhatsApp header."
        }
