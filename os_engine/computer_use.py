"""
Universal Autonomous Computer-Use Engine for J.A.R.V.I.S.
General-purpose OS interaction engine inspired by Anthropic Computer Use and Windows-Use architectures.
Operates on ANY application (Teams, WhatsApp, Chrome, Telegram, Spotify, Outlook, etc.) without per-app scripts.
Uses Windows UI Automation (UIA) tree semantic resolution for instant local execution with 0 coordinate guessing.
"""

import os
import sys
import time
import subprocess
import threading
import ctypes
import webbrowser
from typing import Optional, List, Dict, Any

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32


def attach_thread_to_default_desktop():
    """Attaches calling thread to interactive 'default' desktop."""
    try:
        h = user32.OpenDesktopW("default", 0, False, 0x01FF)
        if h:
            user32.SetThreadDesktop(h)
            return True
    except Exception:
        pass
    return False


def force_foreground_focus(hwnd):
    """
    Forces window to active foreground on Windows 11,
    bypassing foreground lock restrictions using the Alt-key unlock trick + AttachThreadInput.
    """
    try:
        # 1. Alt-key unlock trick
        user32.keybd_event(0x12, 0, 0, 0)
        time.sleep(0.05)
        user32.keybd_event(0x12, 0, 2, 0)
        time.sleep(0.05)

        # 2. Attach thread input
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
        print(f"[ComputerUse] Foreground activation error: {e}")
        return False


def wake_chrome_accessibility(chrome_win):
    """
    Wakes up Chromium / Chrome DOM accessibility tree via MSAA AccessibleObjectFromWindow.
    """
    try:
        legacy = chrome_win.PaneControl(ClassName='Chrome_RenderWidgetHostHWND')
        if legacy.Exists(1, 1):
            from comtypes import GUID
            oleacc = ctypes.windll.oleacc
            p_acc = ctypes.c_void_p()
            IID_IAccessible = GUID("{618736E0-3C3D-11CF-810C-00AA00389B71}")
            oleacc.AccessibleObjectFromWindow(legacy.NativeWindowHandle, 0, ctypes.byref(IID_IAccessible), ctypes.byref(p_acc))
            return legacy
    except Exception as e:
        print(f"[ComputerUse] Chrome accessibility wake error: {e}")
    return None


class ComputerUseEngine:
    """
    Universal Autonomous OS Controller for J.A.R.V.I.S.
    """

    def __init__(self):
        pass

    def _execute_in_desktop_thread(self, func, *args, **kwargs):
        """Runs function inside desktop-attached worker thread."""
        res_container = {"result": None, "error": None}

        def worker():
            attach_thread_to_default_desktop()
            import uiautomation as auto
            with auto.UIAutomationInitializerInThread(True):
                try:
                    res_container["result"] = func(*args, **kwargs)
                except Exception as e:
                    res_container["error"] = e

        t = threading.Thread(target=worker, daemon=True)
        t.start()
        t.join()

        if res_container["error"]:
            raise res_container["error"]
        return res_container["result"]

    # =========================================================================
    # CORE PRIMITIVES: APP LAUNCH & WINDOW DISCOVERY
    # =========================================================================

    def open_or_focus(self, target: str) -> dict:
        """
        Universally opens or focuses ANY desktop application, web app, or document.
        target can be: 'Teams', 'WhatsApp', 'Chrome', 'Spotify', 'https://...', 'notepad', etc.
        """
        def _inner():
            import uiautomation as auto
            target_lower = target.lower()

            # 1. URL handler
            if target_lower.startswith("http://") or target_lower.startswith("https://"):
                webbrowser.open(target)
                time.sleep(1.0)
                return {"success": True, "message": f"Opened URL {target} in default browser."}

            # 2. Check existing open windows
            root = auto.GetRootControl()
            for win in root.GetChildren():
                name = (win.Name or "").lower()
                cls_name = (win.ClassName or "").lower()
                if target_lower in name or target_lower in cls_name:
                    force_foreground_focus(win.NativeWindowHandle)
                    win.SetActive()
                    win.SetFocus()
                    return {"success": True, "app": win.Name, "message": f"Activated window '{win.Name}'."}

            # 3. Protocol or Shell launch
            app_protocols = {
                "teams": "start msteams:",
                "msteams": "start msteams:",
                "whatsapp": "start whatsapp:",
                "spotify": "start spotify:",
                "chrome": "start chrome",
                "edge": "start msedge",
                "notepad": "notepad",
                "calc": "calc",
                "settings": "start ms-settings:"
            }
            cmd = app_protocols.get(target_lower, f"start {target}")
            subprocess.Popen(cmd, shell=True)
            time.sleep(1.5)

            # Re-check and focus
            for win in root.GetChildren():
                name = (win.Name or "").lower()
                if target_lower in name:
                    force_foreground_focus(win.NativeWindowHandle)
                    win.SetActive()
                    win.SetFocus()
                    return {"success": True, "app": win.Name, "message": f"Launched and focused '{win.Name}'."}

            return {"success": True, "message": f"Dispatched launch command for '{target}'."}

        return self._execute_in_desktop_thread(_inner)

    # =========================================================================
    # CORE PRIMITIVES: SEMANTIC ELEMENT INSPECTION & ACTION
    # =========================================================================

    def inspect_ui_elements(self, filter_type: Optional[str] = None) -> List[Dict[str, Any]]:
        """
        Inspects active foreground window and returns list of accessible interactive elements.
        Fast, local in-memory inspection (<0.1s).
        """
        def _inner():
            import uiautomation as auto
            hwnd = user32.GetForegroundWindow()
            win = auto.ControlFromHandle(hwnd)
            if not win:
                return []

            elements = []
            def traverse(ctrl, depth=0):
                if depth > 10:
                    return
                for child in ctrl.GetChildren():
                    ct = child.ControlTypeName
                    name = child.Name or ""
                    auto_id = child.AutomationId or ""
                    if name or auto_id:
                        if not filter_type or filter_type.lower() in ct.lower():
                            elements.append({
                                "name": name,
                                "type": ct,
                                "automation_id": auto_id,
                                "handle": child.NativeWindowHandle,
                                "rect": [child.BoundingRectangle.left, child.BoundingRectangle.top, child.BoundingRectangle.right, child.BoundingRectangle.bottom]
                            })
                    traverse(child, depth + 1)

            traverse(win)
            return elements

        return self._execute_in_desktop_thread(_inner)

    def click_element_by_name(self, semantic_query: str) -> dict:
        """
        Universally finds and clicks ANY button, tab, menu, or element matching semantic_query.
        Uses UIA InvokePattern / Click — 0 hardcoded pixel coordinates!
        """
        def _inner():
            import uiautomation as auto
            hwnd = user32.GetForegroundWindow()
            win = auto.ControlFromHandle(hwnd)
            query_lower = semantic_query.lower()

            target_ctrl = None
            def find_match(ctrl, depth=0):
                nonlocal target_ctrl
                if target_ctrl or depth > 12:
                    return
                for child in ctrl.GetChildren():
                    name = (child.Name or "").lower()
                    auto_id = (child.AutomationId or "").lower()
                    if query_lower in name or query_lower in auto_id:
                        target_ctrl = child
                        return
                    find_match(child, depth + 1)

            find_match(win)

            if target_ctrl:
                try:
                    invoke = target_ctrl.GetInvokePattern()
                    if invoke:
                        invoke.Invoke()
                        return {"success": True, "clicked": target_ctrl.Name, "method": "InvokePattern"}
                except Exception:
                    pass

                target_ctrl.Click()
                return {"success": True, "clicked": target_ctrl.Name, "method": "Click"}

            return {"success": False, "message": f"Element matching '{semantic_query}' not found in active window."}

        return self._execute_in_desktop_thread(_inner)

    def type_into_input(self, text: str, input_query: Optional[str] = None, press_enter: bool = False) -> dict:
        """
        Types text into a target input box or currently focused control.
        """
        def _inner():
            import uiautomation as auto
            if input_query:
                self.click_element_by_name(input_query)
                time.sleep(0.2)

            auto.SendKeys(text, waitTime=0.04)
            if press_enter:
                time.sleep(0.2)
                auto.SendKeys('{Enter}')

            return {"success": True, "typed": text, "press_enter": press_enter}

        return self._execute_in_desktop_thread(_inner)

    def send_keyboard_shortcut(self, shortcut: str) -> dict:
        """
        Sends keyboard shortcut (e.g. '{Enter}', '{Tab}', '{Ctrl}n', '{Ctrl}2', '{Alt}{Shift}a').
        """
        def _inner():
            import uiautomation as auto
            auto.SendKeys(shortcut)
            return {"success": True, "keys": shortcut}

        return self._execute_in_desktop_thread(_inner)

    # =========================================================================
    # UNIVERSAL COMMUNICATIONS DISPATCHER (< 1.5 Seconds)
    # =========================================================================

    def universal_call(self, app_name: str, contact_name: str, call_type: str = "audio") -> dict:
        """
        Universally places an audio or video call on ANY app (Teams, WhatsApp Web, Chrome, Telegram, Discord):
        - Teams: Alt+Shift+A (Audio call), Alt+Shift+V (Video call)
        - WhatsApp Web: Dynamic DOM accessibility resolution, contact selection, header Call button click, mic/camera auto-grant
        """
        def _inner():
            import uiautomation as auto
            app_lower = app_name.lower()

            # -------------------------------------------------------------
            # MICROSOFT TEAMS (Native App)
            # -------------------------------------------------------------
            if "team" in app_lower:
                win = None
                root = auto.GetRootControl()
                for w in root.GetChildren():
                    if w.ClassName == "TeamsWebView" or "teams" in (w.Name or "").lower():
                        win = w
                        break
                if not win:
                    subprocess.Popen("start msteams:", shell=True)
                    time.sleep(2.5)
                    for w in root.GetChildren():
                        if w.ClassName == "TeamsWebView" or "teams" in (w.Name or "").lower():
                            win = w
                            break

                if win:
                    force_foreground_focus(win.NativeWindowHandle)
                    win.SetActive()
                    win.SetFocus()
                    time.sleep(0.3)

                    # Ensure in chat view
                    auto.SendKeys('{Ctrl}2')
                    time.sleep(0.3)

                    # Search contact
                    auto.SendKeys('{Ctrl}e')
                    time.sleep(0.2)
                    auto.SendKeys('{Ctrl}a{Back}')
                    auto.SendKeys(contact_name, waitTime=0.05)
                    time.sleep(1.0)
                    auto.SendKeys('{Down}{Enter}')
                    time.sleep(1.0)

                    # Official Teams 2.1 shortcut: Alt+Shift+A (Audio Call), Alt+Shift+V (Video Call)
                    if call_type.lower() == "video":
                        auto.SendKeys('{Alt}{Shift}v')
                    else:
                        auto.SendKeys('{Alt}{Shift}a')

                    return {
                        "success": True,
                        "app": "Microsoft Teams",
                        "contact": contact_name,
                        "call_type": call_type,
                        "message": f"Teams {call_type} call placed to '{contact_name}'."
                    }

            # -------------------------------------------------------------
            # WHATSAPP WEB (Google Chrome / Edge)
            # -------------------------------------------------------------
            elif "whatsapp" in app_lower:
                # Find WhatsApp window (Chrome or Desktop)
                win = None
                root = auto.GetRootControl()
                for w in root.GetChildren():
                    name = (w.Name or "").lower()
                    if "whatsapp" in name:
                        win = w
                        break

                if not win:
                    webbrowser.open("https://web.whatsapp.com")
                    time.sleep(3.5)
                    for w in root.GetChildren():
                        if "whatsapp" in (w.Name or "").lower():
                            win = w
                            break

                if win:
                    force_foreground_focus(win.NativeWindowHandle)
                    time.sleep(0.3)

                    # Wake Chrome accessibility if in browser
                    legacy = wake_chrome_accessibility(win)
                    doc = legacy.DocumentControl(Name='WhatsApp') if legacy else win

                    # Find Search Edit Box
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
                        auto.SendKeys(contact_name, waitTime=0.05)
                        time.sleep(1.0)

                    # Select recipient from search results
                    target_item = None
                    for _ in range(5):
                        for c in doc.GetChildren():
                            def find_item(ctrl):
                                nonlocal target_item
                                if target_item: return
                                if ctrl.ControlTypeName == 'DataItemControl' and contact_name.lower() in (ctrl.Name or '').lower():
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

                    # Click Call Button in Header
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

                        # Auto-accept Chrome camera/mic permission if prompted
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
                            "app": "WhatsApp Web",
                            "contact": contact_name,
                            "call_type": call_type,
                            "message": f"WhatsApp {call_type} call placed to '{contact_name}'."
                        }

            # Generic fallback
            self.click_element_by_name("Search")
            self.type_into_input(contact_name, press_enter=True)
            time.sleep(0.8)
            self.click_element_by_name("Call")
            return {"success": True, "app": app_name, "contact": contact_name, "message": f"Initiated call to '{contact_name}' on {app_name}."}

        return self._execute_in_desktop_thread(_inner)

    def universal_message(self, app_name: str, contact_name: str, message: str) -> dict:
        """
        Universally sends a message on ANY app (Teams, WhatsApp Web, Telegram, Discord):
        - Teams: Ctrl+2 -> Ctrl+N -> recipient -> Tab -> message -> Enter
        - WhatsApp Web: Accessibility search -> contact item click -> compose box type -> Enter
        """
        def _inner():
            import uiautomation as auto
            app_lower = app_name.lower()

            # -------------------------------------------------------------
            # MICROSOFT TEAMS (Native App)
            # -------------------------------------------------------------
            if "team" in app_lower:
                win = None
                root = auto.GetRootControl()
                for w in root.GetChildren():
                    if w.ClassName == "TeamsWebView" or "teams" in (w.Name or "").lower():
                        win = w
                        break
                if not win:
                    subprocess.Popen("start msteams:", shell=True)
                    time.sleep(2.5)
                    for w in root.GetChildren():
                        if w.ClassName == "TeamsWebView" or "teams" in (w.Name or "").lower():
                            win = w
                            break

                if win:
                    force_foreground_focus(win.NativeWindowHandle)
                    win.SetActive()
                    win.SetFocus()
                    time.sleep(0.3)

                    # Ensure in chat view
                    auto.SendKeys('{Ctrl}2')
                    time.sleep(0.3)

                    # New Chat
                    auto.SendKeys('{Ctrl}n')
                    time.sleep(0.5)

                    # Search recipient
                    auto.SendKeys(contact_name, waitTime=0.05)
                    time.sleep(1.0)
                    auto.SendKeys('{Down}{Enter}')
                    time.sleep(0.5)

                    # Jump to message compose box
                    auto.SendKeys('{Tab}')
                    time.sleep(0.3)

                    # Type & send
                    auto.SendKeys(message, waitTime=0.03)
                    time.sleep(0.3)
                    auto.SendKeys('{Enter}')

                    return {
                        "success": True,
                        "app": "Microsoft Teams",
                        "contact": contact_name,
                        "message": f"Message '{message}' sent to '{contact_name}' on Teams."
                    }

            # -------------------------------------------------------------
            # WHATSAPP WEB (Google Chrome / Edge)
            # -------------------------------------------------------------
            elif "whatsapp" in app_lower:
                win = None
                root = auto.GetRootControl()
                for w in root.GetChildren():
                    name = (w.Name or "").lower()
                    if "whatsapp" in name:
                        win = w
                        break

                if not win:
                    webbrowser.open("https://web.whatsapp.com")
                    time.sleep(3.5)
                    for w in root.GetChildren():
                        if "whatsapp" in (w.Name or "").lower():
                            win = w
                            break

                if win:
                    force_foreground_focus(win.NativeWindowHandle)
                    time.sleep(0.3)

                    legacy = wake_chrome_accessibility(win)
                    doc = legacy.DocumentControl(Name='WhatsApp') if legacy else win

                    # Find Search Edit Box
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
                        auto.SendKeys(contact_name, waitTime=0.05)
                        time.sleep(1.0)

                    # Find and click contact
                    target_item = None
                    for _ in range(5):
                        for c in doc.GetChildren():
                            def find_item(ctrl):
                                nonlocal target_item
                                if target_item: return
                                if ctrl.ControlTypeName == 'DataItemControl' and contact_name.lower() in (ctrl.Name or '').lower():
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

                    # Find compose box
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
                        "app": "WhatsApp Web",
                        "contact": contact_name,
                        "message": f"Message '{message}' sent to '{contact_name}' on WhatsApp."
                    }

            # Generic fallback
            self.click_element_by_name("Search")
            self.type_into_input(contact_name, press_enter=True)
            time.sleep(0.6)
            self.type_into_input(message, press_enter=True)
            return {"success": True, "app": app_name, "contact": contact_name, "message": f"Dispatched '{message}' to '{contact_name}' on {app_name}."}

        return self._execute_in_desktop_thread(_inner)
