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
            auto.SetGlobalSearchTimeout(1.0)
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

    def click_element_by_name(self, semantic_query: str, app_name: Optional[str] = None) -> dict:
        """
        Universally finds and clicks ANY button, tab, menu, or element matching semantic_query.
        Supports optional app_name to bring target window to front.
        Zero hardcoded pixel coordinates!
        """
        def _inner():
            import uiautomation as auto
            root = auto.GetRootControl()
            target_win = None

            if app_name:
                app_lower = app_name.lower()
                for w in root.GetChildren():
                    w_name = (w.Name or "").lower()
                    w_class = (w.ClassName or "").lower()
                    if app_lower in w_name or app_lower in w_class:
                        target_win = w
                        break
                if target_win:
                    force_foreground_focus(target_win.NativeWindowHandle)
                    target_win.SetActive()
                    target_win.SetFocus()
                    time.sleep(0.4)

            hwnd = user32.GetForegroundWindow()
            win = target_win or auto.ControlFromHandle(hwnd)
            query_lower = semantic_query.lower()

            target_ctrl = None
            q_words = [w for w in query_lower.split() if len(w) > 2]
            def find_match(ctrl, depth=0):
                nonlocal target_ctrl
                if target_ctrl or depth > 22:
                    return
                for child in ctrl.GetChildren():
                    name = (child.Name or "").lower()
                    auto_id = (child.AutomationId or "").lower()
                    help_txt = (child.HelpText or "").lower()
                    ct_lower = (child.ControlTypeName or "").lower()
                    is_match = (
                        query_lower in name or
                        (name and name in query_lower and len(name) >= 4) or
                        query_lower in auto_id or
                        query_lower in help_txt or
                        (any(qw in name for qw in q_words) and any(kw in ct_lower for kw in ['button', 'item', 'hyperlink', 'text']))
                    )
                    if is_match:
                        target_ctrl = child
                        return
                    find_match(child, depth + 1)

            if win:
                find_match(win)

            # If not found in current/target window, search across all open application windows
            if not target_ctrl:
                for w in root.GetChildren():
                    w_name = (w.Name or "").lower()
                    if any(ignored in w_name for ignored in ["program manager", "taskbar", "powershell", "cmd.exe"]):
                        continue
                    find_match(w)
                    if target_ctrl:
                        force_foreground_focus(w.NativeWindowHandle)
                        w.SetActive()
                        time.sleep(0.3)
                        break

            if target_ctrl:
                try:
                    invoke = target_ctrl.GetInvokePattern()
                    if invoke:
                        invoke.Invoke()
                        return {"success": True, "clicked": target_ctrl.Name, "method": "InvokePattern"}
                except Exception:
                    pass

                try:
                    target_ctrl.Click()
                    return {"success": True, "clicked": target_ctrl.Name, "method": "Click"}
                except Exception:
                    r = target_ctrl.BoundingRectangle
                    if r.width() > 0 and r.height() > 0:
                        auto.Click(r.xcenter(), r.ycenter())
                        return {"success": True, "clicked": target_ctrl.Name, "method": "CoordClick"}

            # Smart fallback for IDEs when accepting changes (e.g. Antigravity / VS Code)
            if any(w in query_lower for w in ["accept", "proceed", "keep"]):
                # Bring target window to focus if known
                if win:
                    force_foreground_focus(win.NativeWindowHandle)
                    time.sleep(0.2)
                auto.SendKeys('{Alt}a')
                time.sleep(0.3)
                auto.SendKeys('{Ctrl}{Enter}')
                return {"success": True, "clicked": f"Accepted changes via shortcut in {app_name or 'IDE'}", "method": "Shortcut"}

            return {"success": False, "message": f"Element matching '{semantic_query}' not found."}

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

    def browser_action(self, action: str) -> dict:
        """
        Performs browser manipulation on the active browser window:
        - new_tab, close_tab, next_tab, prev_tab, reopen_tab, refresh
        - scroll_down, scroll_up, scroll_top, scroll_bottom
        - zoom_in, zoom_out, zoom_reset
        """
        action_map = {
            "new_tab": "{Ctrl}t",
            "close_tab": "{Ctrl}w",
            "next_tab": "{Ctrl}{Tab}",
            "prev_tab": "{Ctrl}{Shift}{Tab}",
            "reopen_tab": "{Ctrl}{Shift}t",
            "refresh": "{F5}",
            "scroll_down": "{PageDown}",
            "scroll_up": "{PageUp}",
            "scroll_top": "{Home}",
            "scroll_bottom": "{End}",
            "zoom_in": "{Ctrl}+",
            "zoom_out": "{Ctrl}-",
            "zoom_reset": "{Ctrl}0"
        }
        shortcut = action_map.get(action.lower())
        if not shortcut:
            return {"success": False, "message": f"Unsupported browser action: '{action}'"}

        return self.send_keyboard_shortcut(shortcut)

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

                    legacy = wake_chrome_accessibility(win)
                    doc = legacy.GetChildren()[0] if legacy and legacy.GetChildren() else win

                    win_rect = win.BoundingRectangle
                    mid_x = win_rect.left + win_rect.width() * 0.5

                    # 1. Look for contact directly in the visible chat list on the left with mouse hover & click
                    contact_ctrl = None
                    def find_contact(ctrl, d=0):
                        nonlocal contact_ctrl
                        if contact_ctrl or d > 25: return
                        nm = (ctrl.Name or '').strip().lower()
                        if ctrl.ControlTypeName in ['TreeItemControl', 'ListItemControl', 'TextControl'] and contact_name.lower() in nm:
                            r = ctrl.BoundingRectangle
                            if r.left < mid_x and r.top > 80:
                                contact_ctrl = ctrl
                                return
                        for sub in ctrl.GetChildren():
                            find_contact(sub, d + 1)
                    find_contact(doc)

                    if contact_ctrl:
                        r = contact_ctrl.BoundingRectangle
                        auto.MoveTo(r.xcenter(), r.ycenter())
                        time.sleep(0.12)
                        auto.Click(r.xcenter(), r.ycenter())
                        time.sleep(0.8)
                    else:
                        # Fallback: search box
                        auto.SendKeys('{Ctrl}e')
                        time.sleep(0.2)
                        auto.SendKeys('{Ctrl}a{Back}')
                        auto.SendKeys(contact_name, waitTime=0.03)
                        time.sleep(0.8)
                        auto.SendKeys('{Enter}')
                        time.sleep(1.0)

                    # 2. In chat header, hover mouse to Audio call / Video call button and click
                    target_call = "video call" if call_type.lower() == "video" else "audio call"
                    call_btn = None
                    for _ in range(5):
                        for c in doc.GetChildren():
                            def find_call(ctrl, d=0):
                                nonlocal call_btn
                                if call_btn or d > 25: return
                                nm = (ctrl.Name or '').strip().lower()
                                if ctrl.ControlTypeName == 'ButtonControl' and target_call in nm:
                                    call_btn = ctrl
                                    return
                                for sub in ctrl.GetChildren():
                                    find_call(sub, d + 1)
                            find_call(c)
                        if call_btn:
                            break
                        time.sleep(0.3)

                    if call_btn:
                        r = call_btn.BoundingRectangle
                        auto.MoveTo(r.xcenter(), r.ycenter())
                        time.sleep(0.15)
                        try:
                            call_btn.Click()
                        except Exception:
                            auto.Click(r.xcenter(), r.ycenter())
                    else:
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
                # Find WhatsApp Web / Desktop window
                win = None
                root = auto.GetRootControl()
                for w in root.GetChildren():
                    name = (w.Name or "").lower()
                    if "whatsapp" in name:
                        win = w
                        break

                if not win:
                    webbrowser.open("https://web.whatsapp.com")
                    time.sleep(2.5)
                    for w in root.GetChildren():
                        if "whatsapp" in (w.Name or "").lower():
                            win = w
                            break

                if win:
                    force_foreground_focus(win.NativeWindowHandle)
                    time.sleep(0.3)

                    # Wake Chrome accessibility if in browser
                    legacy = wake_chrome_accessibility(win)
                    doc = win
                    if legacy:
                        for c in legacy.GetChildren():
                            if c.ControlTypeName == 'DocumentControl':
                                doc = c
                                break

                    # Find and click WhatsApp Search box
                    search_edit = None
                    for c in doc.GetChildren():
                        def find_se(ctrl, d=0):
                            nonlocal search_edit
                            if search_edit or d > 12: return
                            if ctrl.ControlTypeName == 'EditControl' and ('search' in (ctrl.Name or '').lower() or not search_edit):
                                search_edit = ctrl
                                return
                            for sub in ctrl.GetChildren():
                                find_se(sub, d + 1)
                        find_se(c)
                        if search_edit:
                            break

                    if search_edit:
                        r = search_edit.BoundingRectangle
                        auto.MoveTo(r.xcenter(), r.ycenter())
                        time.sleep(0.1)
                        try:
                            search_edit.Click()
                        except Exception:
                            auto.Click(r.xcenter(), r.ycenter())
                        time.sleep(0.2)
                    else:
                        auto.SendKeys('{Ctrl}{Alt}/')
                        time.sleep(0.2)

                    auto.SendKeys('{Ctrl}a{Back}')
                    time.sleep(0.15)
                    auto.SendKeys(contact_name, waitTime=0.03)
                    time.sleep(0.8)
                    auto.SendKeys('{Enter}')
                    time.sleep(1.0)

                    # Click Call Button in Chat Header (RIGHT side of window, NOT left nav tab)
                    btn_name = "video call" if call_type.lower() == "video" else "voice call"
                    win_rect = win.BoundingRectangle
                    min_header_x = win_rect.left + win_rect.width() * 0.4
                    call_btn = None
                    for _ in range(6):
                        for c in doc.GetChildren():
                            def find_btn(ctrl, d=0):
                                nonlocal call_btn
                                if call_btn or d > 12: return
                                if ctrl.ControlTypeName == 'ButtonControl':
                                    nm = (ctrl.Name or '').strip().lower()
                                    r = ctrl.BoundingRectangle
                                    if nm == btn_name and r.left > min_header_x:
                                        call_btn = ctrl
                                        return
                                for sub in ctrl.GetChildren():
                                    find_btn(sub, d + 1)
                            find_btn(c)
                        if call_btn:
                            break
                        time.sleep(0.3)

                    if call_btn:
                        r = call_btn.BoundingRectangle
                        auto.MoveTo(r.xcenter(), r.ycenter())
                        time.sleep(0.15)
                        try:
                            call_btn.Click()
                        except Exception:
                            try:
                                call_btn.GetInvokePattern().Invoke()
                            except Exception:
                                auto.Click(r.xcenter(), r.ycenter())
                        time.sleep(1.5)

                        # Auto-accept Chrome camera/mic permission if prompted
                        for c in win.GetChildren():
                            def check_allow(ctrl, d=0):
                                if d > 8: return False
                                name = (ctrl.Name or "").lower()
                                if ctrl.ControlTypeName == 'ButtonControl' and any(k in name for k in ['allow while visiting', 'allow this time', 'allow']):
                                    ctrl.Click()
                                    return True
                                for sub in ctrl.GetChildren():
                                    if check_allow(sub, d + 1): return True
                                return False
                            if check_allow(c): break

                        return {
                            "success": True,
                            "app": "WhatsApp Web",
                            "contact": contact_name,
                            "call_type": call_type,
                            "message": f"WhatsApp {call_type} call placed to '{contact_name}'."
                        }
                    else:
                        return {
                            "success": False,
                            "app": "WhatsApp Web",
                            "contact": contact_name,
                            "message": f"Contact '{contact_name}' open ho gaya hai, lekin chat header mein call button nahi mila."
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

                    legacy = wake_chrome_accessibility(win)
                    doc = legacy.GetChildren()[0] if legacy and legacy.GetChildren() else win

                    win_rect = win.BoundingRectangle
                    mid_x = win_rect.left + win_rect.width() * 0.5

                    # 1. Look for contact directly in the visible chat list on the left with mouse hover & click
                    contact_ctrl = None
                    def find_contact(ctrl, d=0):
                        nonlocal contact_ctrl
                        if contact_ctrl or d > 25: return
                        nm = (ctrl.Name or '').strip().lower()
                        if ctrl.ControlTypeName in ['TreeItemControl', 'ListItemControl', 'TextControl'] and contact_name.lower() in nm:
                            r = ctrl.BoundingRectangle
                            if r.left < mid_x and r.top > 80:
                                contact_ctrl = ctrl
                                return
                        for sub in ctrl.GetChildren():
                            find_contact(sub, d + 1)
                    find_contact(doc)

                    if contact_ctrl:
                        r = contact_ctrl.BoundingRectangle
                        auto.MoveTo(r.xcenter(), r.ycenter())
                        time.sleep(0.12)
                        auto.Click(r.xcenter(), r.ycenter())
                        time.sleep(0.8)
                    else:
                        # Fallback: search box
                        auto.SendKeys('{Ctrl}e')
                        time.sleep(0.2)
                        auto.SendKeys('{Ctrl}a{Back}')
                        auto.SendKeys(contact_name, waitTime=0.03)
                        time.sleep(0.8)
                        auto.SendKeys('{Enter}')
                        time.sleep(1.0)

                    # 2. Hover mouse over Compose box ('Type a message') and click
                    compose_box = None
                    for _ in range(5):
                        for c in doc.GetChildren():
                            def find_compose(ctrl, d=0):
                                nonlocal compose_box
                                if compose_box or d > 25: return
                                nm = (ctrl.Name or '').strip().lower()
                                if ctrl.ControlTypeName == 'EditControl' and any(k in nm for k in ['type a message', 'message']):
                                    compose_box = ctrl
                                    return
                                for sub in ctrl.GetChildren():
                                    find_compose(sub, d + 1)
                            find_compose(c)
                        if compose_box:
                            break
                        time.sleep(0.3)

                    if compose_box:
                        r = compose_box.BoundingRectangle
                        auto.MoveTo(r.xcenter(), r.ycenter())
                        time.sleep(0.12)
                        auto.Click(r.xcenter(), r.ycenter())
                        time.sleep(0.15)
                    else:
                        auto.SendKeys('{Tab}')
                        time.sleep(0.2)

                    auto.SendKeys(message, waitTime=0.03)
                    time.sleep(0.3)

                    # Look for Teams Send button or use Ctrl+Enter (since modern Teams treats Enter as newline)
                    send_btn = None
                    for c in doc.GetChildren():
                        def find_send(ctrl, d=0):
                            nonlocal send_btn
                            if send_btn or d > 25: return
                            if ctrl.ControlTypeName == 'ButtonControl' and 'send' in (ctrl.Name or '').lower():
                                send_btn = ctrl
                                return
                            for sub in ctrl.GetChildren():
                                find_send(sub, d + 1)
                        find_send(c)
                        if send_btn:
                            break

                    if send_btn and send_btn.BoundingRectangle.width() > 0 and send_btn.BoundingRectangle.left > 0:
                        r = send_btn.BoundingRectangle
                        auto.MoveTo(r.xcenter(), r.ycenter())
                        time.sleep(0.12)
                        try:
                            send_btn.Click()
                        except Exception:
                            auto.Click(r.xcenter(), r.ycenter())
                    else:
                        auto.SendKeys('{Ctrl}{Enter}')

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
                    time.sleep(2.5)
                    for w in root.GetChildren():
                        if "whatsapp" in (w.Name or "").lower():
                            win = w
                            break

                if win:
                    force_foreground_focus(win.NativeWindowHandle)
                    time.sleep(0.3)

                    legacy = wake_chrome_accessibility(win)
                    doc = win
                    if legacy:
                        for c in legacy.GetChildren():
                            if c.ControlTypeName == 'DocumentControl':
                                doc = c
                                break

                    # Focus WhatsApp Search box
                    search_edit = None
                    for c in doc.GetChildren():
                        def find_se(ctrl, d=0):
                            nonlocal search_edit
                            if search_edit or d > 12: return
                            if ctrl.ControlTypeName == 'EditControl' and ('search' in (ctrl.Name or '').lower() or not search_edit):
                                search_edit = ctrl
                                return
                            for sub in ctrl.GetChildren():
                                find_se(sub, d + 1)
                        find_se(c)
                        if search_edit:
                            break

                    if search_edit:
                        r = search_edit.BoundingRectangle
                        auto.MoveTo(r.xcenter(), r.ycenter())
                        time.sleep(0.1)
                        try:
                            search_edit.Click()
                        except Exception:
                            auto.Click(r.xcenter(), r.ycenter())
                        time.sleep(0.2)
                    else:
                        auto.SendKeys('{Ctrl}{Alt}/')
                        time.sleep(0.2)

                    auto.SendKeys('{Ctrl}a{Back}')
                    time.sleep(0.15)
                    auto.SendKeys(contact_name, waitTime=0.03)
                    time.sleep(0.8)

                    # In WhatsApp Web, pressing Enter on the search result opens that chat directly
                    auto.SendKeys('{Enter}')
                    time.sleep(1.0)

                    # Find and click compose box
                    compose_box = None
                    for _ in range(6):
                        for c in doc.GetChildren():
                            def find_msg_box(ctrl, d=0):
                                nonlocal compose_box
                                if compose_box or d > 15: return
                                if ctrl.ControlTypeName == 'EditControl' and any(k in (ctrl.Name or '').lower() for k in ['type a message', 'message', 'type']):
                                    compose_box = ctrl
                                    return
                                for sub in ctrl.GetChildren():
                                    find_msg_box(sub, d + 1)
                            find_msg_box(c)
                        if compose_box:
                            break
                        time.sleep(0.3)

                    if compose_box:
                        r = compose_box.BoundingRectangle
                        auto.MoveTo(r.xcenter(), r.ycenter())
                        time.sleep(0.12)
                        try:
                            compose_box.Click()
                        except Exception:
                            auto.Click(r.xcenter(), r.ycenter())
                        time.sleep(0.2)

                    auto.SendKeys(message, waitTime=0.03)
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

    def inspect_window_content(self, target_app_or_url: Optional[str] = None, max_items: int = 40) -> dict:
        """
        Universal Screen & Window Perception Primitive:
        Extracts all visible text, items, and UI hierarchy from the target or active window.
        Pure OS extraction with ZERO hardcoded keywords, company names, or filters.
        Passes raw observed text directly to Gemini Brain for semantic reasoning.
        """
        def _inner():
            import uiautomation as auto
            root = auto.GetRootControl()

            target_win = None
            if target_app_or_url:
                target_lower = target_app_or_url.lower()
                for w in root.GetChildren():
                    name = (w.Name or "").lower()
                    cls = (w.ClassName or "").lower()
                    if target_lower in name or target_lower in cls:
                        target_win = w
                        break

            if not target_win:
                hwnd = user32.GetForegroundWindow()
                target_win = auto.ControlFromHandle(hwnd)

            if not target_win:
                return {"success": False, "items": [], "raw_text": "", "message": "No active window found on screen."}

            force_foreground_focus(target_win.NativeWindowHandle)
            target_win.SetActive()
            time.sleep(0.25)

            # Wake accessibility if browser or Electron app
            wake_chrome_accessibility(target_win)
            time.sleep(0.15)

            collected_lines = []
            seen_texts = set()

            def traverse(ctrl, depth=0):
                if depth > 22 or len(collected_lines) >= max_items:
                    return

                name = (ctrl.Name or "").strip()
                val = ""
                try:
                    val = (ctrl.GetValuePattern().Value or "").strip() if ctrl.GetValuePattern() else ""
                except Exception:
                    pass

                text = name or val
                if text and len(text) > 1:
                    clean_text = " ".join(text.split())
                    if clean_text not in seen_texts:
                        seen_texts.add(clean_text)
                        collected_lines.append(clean_text)

                for child in ctrl.GetChildren():
                    traverse(child, depth + 1)

            traverse(target_win)

            full_text = "\n".join(collected_lines)
            return {
                "success": True,
                "window_title": target_win.Name or "Active Window",
                "total_items": len(collected_lines),
                "items": collected_lines,
                "raw_text": full_text[:4000]
            }

        return self._execute_in_desktop_thread(_inner)

    def inspect_messages_in_background(self, app_name: str = "WhatsApp", contact_name: str = "") -> dict:
        """
        Background perception primitive: inspects WhatsApp, Teams, or ANY communication window/tab
        for new incoming messages WITHOUT stealing foreground focus or disrupting user's active work.
        """
        def _inner():
            import uiautomation as auto
            import psutil
            import re
            root = auto.GetRootControl()

            app_lower = (app_name or "").lower().strip()
            contact_lower = (contact_name or "").lower().strip()

            target_win = None
            target_tab = None
            browser_windows = []

            # 1. Scan all top-level desktop windows
            for w in root.GetChildren():
                name = (w.Name or "").lower()
                pid = w.ProcessId
                pname = ""
                try:
                    pname = psutil.Process(pid).name().lower() if pid else ""
                except Exception:
                    pass

                # Direct standalone window match (e.g. Teams, WhatsApp Desktop, Telegram, Discord, Slack)
                if app_lower and (app_lower in name or app_lower in pname):
                    target_win = w
                    break

                # Collect real browser windows (Chrome, Edge, Brave, Firefox, Opera)
                if any(b in pname for b in ["chrome.exe", "msedge.exe", "brave.exe", "firefox.exe", "opera.exe"]):
                    browser_windows.append(w)
                elif any(b in name for b in ["google chrome", "microsoft edge", "brave", "firefox"]):
                    browser_windows.append(w)

            # 2. If not a standalone window, search browser tabs for the web application (e.g. WhatsApp Web, Teams Web)
            if not target_win and browser_windows:
                for b_win in browser_windows:
                    def scan_tabs(ctrl, depth=0):
                        nonlocal target_win, target_tab
                        if depth > 12 or target_tab:
                            return
                        c_type = ctrl.ControlTypeName.lower()
                        c_name = (ctrl.Name or "").lower()
                        if "tabitem" in c_type and app_lower in c_name:
                            target_win = b_win
                            target_tab = ctrl
                            return
                        for child in ctrl.GetChildren():
                            scan_tabs(child, depth + 1)

                    scan_tabs(b_win)
                    if target_tab:
                        break

            if not target_win:
                return {
                    "found": False,
                    "snippet": None,
                    "has_unread": False,
                    "unread_count": 0,
                    "matched_contact": False
                }

            # 3. Determine Title & Unread Indicators
            win_title = target_win.Name or ""
            tab_title = target_tab.Name or "" if target_tab else ""
            eval_title = tab_title if tab_title else win_title

            # Check unread badge pattern like (1), (2), (99+), or [1] in tab or window title
            unread_match = re.search(r'[\(\[](\d+)\+?[\)\]]', eval_title)
            unread_count = int(unread_match.group(1)) if unread_match else 0
            has_unread = (unread_count > 0) or ("unread" in eval_title.lower())

            # 4. Deep Inspection for Contact Name and Message Snippet
            detected_snippet = None
            matched_contact = False

            if contact_lower and contact_lower in eval_title.lower():
                matched_contact = True
                detected_snippet = eval_title

            def traverse_elements(ctrl, depth=0):
                nonlocal detected_snippet, matched_contact
                if depth > 15 or (detected_snippet and matched_contact):
                    return

                nm = (ctrl.Name or "").strip()
                if nm and len(nm) > 1 and "memory usage" not in nm.lower():
                    nm_lower = nm.lower()
                    if contact_lower and contact_lower in nm_lower:
                        matched_contact = True
                        detected_snippet = nm
                        return
                    if not detected_snippet and any(u in nm_lower for u in ["unread", "new message", "unread message"]):
                        detected_snippet = nm

                for child in ctrl.GetChildren():
                    traverse_elements(child, depth + 1)

            traverse_elements(target_tab if target_tab else target_win)

            final_snippet = detected_snippet or eval_title

            return {
                "found": True,
                "window_title": win_title,
                "tab_title": tab_title,
                "snippet": final_snippet,
                "has_unread": has_unread or ("unread" in (final_snippet or "").lower()),
                "unread_count": unread_count,
                "matched_contact": matched_contact
            }

        return self._execute_in_desktop_thread(_inner)
