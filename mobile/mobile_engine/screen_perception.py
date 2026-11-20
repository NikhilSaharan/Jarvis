"""
Universal Screen Perception Engine for J.A.R.V.I.S. Mobile.
Implements the Tencent AppAgent / Set-of-Marks (SoM) architecture:
- Dumps real-time UI hierarchy from Android (`uiautomator dump --compressed`)
- Extracts all interactive/clickable elements (Buttons, Inputs, Text, Icons)
- Assigns numeric tags ([1], [2], [3]...) to each interactive component
- Allows Gemini LLM to operate any app like a human finger: tap(tag_id) or type(tag_id, text)
- Zero app-specific hardcoding. Completely universal across all 2.5M Android apps.
"""
import re
import os
import time
import base64
import xml.etree.ElementTree as ET


class ScreenPerception:
    def __init__(self, controller):
        self.ac = controller
        self.dump_file = "/data/local/tmp/window_dump.xml"
        self._cached_xml = ""
        self._cached_xml_time = 0.0
        self._last_failed_dump_time = 0.0
        # Tagged elements map: {tag_int: {"label": str, "type": str, "center": (x, y), "bounds": (x1,y1,x2,y2)}}
        self._tagged_elements = {}
        self._tagged_time = 0.0

    def capture_ui_xml(self, max_age: float = 1.0) -> str:
        """
        Dumps current screen XML hierarchy with --compressed.
        Uses fast dump and short failure backoff (2.5s) to stay responsive.
        """
        now = time.time()
        if self._cached_xml and (now - self._cached_xml_time) < max_age:
            return self._cached_xml

        if (now - self._last_failed_dump_time) < 2.5:
            return self._cached_xml or ""

        try:
            cmd = f"pkill -9 -f uiautomator 2>/dev/null; uiautomator dump --compressed {self.dump_file} >/dev/null 2>&1; cat {self.dump_file}"
            xml_content = self.ac.shell(cmd, timeout=3.0)
            if xml_content and "<node" in xml_content:
                self._cached_xml = xml_content
                self._cached_xml_time = now
                return xml_content
            else:
                self._last_failed_dump_time = now
        except Exception:
            self._last_failed_dump_time = now
            try:
                self.ac.shell("pkill -9 -f uiautomator 2>/dev/null", timeout=1)
            except Exception:
                pass

        return self._cached_xml or ""

    def invalidate_cache(self):
        """Forces next perception call to read a fresh UI dump rather than cache."""
        self._cached_xml = ""
        self._cached_xml_time = 0.0
        self._tagged_elements = {}
        self._tagged_time = 0.0

    # ─── Universal Set-of-Marks (SoM) Tagging Engine ────────────

    def get_tagged_elements(self, force_refresh: bool = False) -> dict:
        """
        Parses live screen layout and assigns unique numeric tags ([1], [2], ...)
        to all interactive/clickable components.
        Returns: {1: {"label": "Search", "type": "SearchView", "center": (x,y), "id": "..."}, ...}
        """
        now = time.time()
        if not force_refresh and self._tagged_elements and (now - self._tagged_time) < 1.0:
            return self._tagged_elements

        xml_str = self.capture_ui_xml()
        if not xml_str:
            return self._tagged_elements or {}

        w, h = self.ac.get_screen_size()
        tagged = {}
        tag_counter = 1

        try:
            root = ET.fromstring(xml_str)
            for node in root.iter("node"):
                t = (node.attrib.get("text") or "").strip()
                cd = (node.attrib.get("content-desc") or "").strip()
                raw_rid = node.attrib.get("resource-id") or ""
                rid = raw_rid.split("/")[-1] if "/" in raw_rid else raw_rid
                cls_name = node.attrib.get("class", "").split(".")[-1]
                clickable = node.attrib.get("clickable", "false") == "true"
                checkable = node.attrib.get("checkable", "false") == "true"
                focusable = node.attrib.get("focusable", "false") == "true"

                # Filter out pure layout wrappers that aren't interactive
                is_interactive = clickable or checkable or (focusable and "EditText" in cls_name)

                # Skip completely empty non-clickable containers
                if not (is_interactive or t or cd):
                    continue

                bounds = node.attrib.get("bounds", "")
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
                if not m:
                    continue

                x1, y1, x2, y2 = map(int, m.groups())
                # Ignore off-screen or zero-size elements
                if x2 <= x1 or y2 <= y1 or x1 < 0 or y1 < 0 or x2 > w or y2 > h:
                    continue

                # Ignore full-screen background views
                if (x2 - x1) >= int(w * 0.98) and (y2 - y1) >= int(h * 0.95):
                    continue

                # Human-friendly label extraction
                label = t or cd or rid
                if not label:
                    if "ImageButton" in cls_name or "ImageView" in cls_name:
                        # Guess likely icon from position or type
                        if y1 > int(h * 0.75) and x1 > int(w * 0.65):
                            label = "Add / Action button (+)"
                        elif y1 < int(h * 0.15) and x1 < int(w * 0.20):
                            label = "Back button"
                        else:
                            label = "Icon button"
                    elif "Button" in cls_name:
                        label = "Button"
                    else:
                        continue

                cx = (x1 + x2) // 2
                cy = (y1 + y2) // 2

                tagged[tag_counter] = {
                    "tag": tag_counter,
                    "label": label,
                    "type": cls_name,
                    "id": rid,
                    "center": (cx, cy),
                    "bounds": (x1, y1, x2, y2),
                    "is_input": "EditText" in cls_name or "SearchView" in cls_name
                }
                tag_counter += 1
                if tag_counter > 30:  # Cap to top 30 elements to keep context crisp
                    break

        except Exception as e:
            print(f"[Screen Perception Tagging Warning]: {e}")

        self._tagged_elements = tagged
        self._tagged_time = now
        return tagged

    def get_tagged_elements_prompt(self) -> list:
        """
        Returns clean, concise list of tagged elements for the Gemini prompt:
        e.g. ["[1] 'Search' (SearchView) at (540, 150)", "[2] 'Abhh' (TextView) at (300, 420)"]
        """
        elements = self.get_tagged_elements()
        if not elements:
            return ["(No interactive UI elements detected via accessibility tree)"]

        summary = []
        for tag, info in elements.items():
            label = info["label"]
            el_type = info["type"]
            cx, cy = info["center"]
            is_input = " [INPUT]" if info.get("is_input") else ""
            summary.append(f"[{tag}] \"{label}\" ({el_type}){is_input} at ({cx}, {cy})")

        return summary

    def get_tag_center(self, tag: int) -> tuple:
        """Returns the physical center pixel coordinates (x, y) for a given tag number."""
        elements = self.get_tagged_elements()
        if tag in elements:
            return elements[tag]["center"]
        return None

    def find_element_center(self, text_or_desc_or_id: str) -> tuple:
        """
        Matches an element by text, description, or id among tagged elements or raw XML.
        Returns (center_x, center_y) tuple or None.
        """
        if not text_or_desc_or_id:
            return None

        # Check tagged elements first
        target = text_or_desc_or_id.lower().strip()
        elements = self.get_tagged_elements()
        for info in elements.values():
            lbl = info["label"].lower()
            rid = info.get("id", "").lower()
            if target in lbl or target in rid or lbl in target:
                return info["center"]

        # Raw XML scan fallback
        xml_str = self.capture_ui_xml()
        if not xml_str:
            return None

        try:
            root = ET.fromstring(xml_str)
            for node in root.iter("node"):
                t = (node.attrib.get("text") or "").lower()
                cd = (node.attrib.get("content-desc") or "").lower()
                rid = (node.attrib.get("resource-id") or "").lower()

                if target in t or target in cd or target in rid:
                    bounds = node.attrib.get("bounds", "")
                    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
                    if m:
                        x1, y1, x2, y2 = map(int, m.groups())
                        if x2 > x1 and y2 > y1 and x1 >= 0 and y1 >= 0:
                            return ((x1 + x2) // 2, (y1 + y2) // 2)
        except Exception:
            pass

        return None

    def find_first_input_field(self) -> tuple:
        """Finds the first visible EditText or search input on screen."""
        elements = self.get_tagged_elements()
        for info in elements.values():
            if info.get("is_input"):
                return info["center"]

        xml_str = self.capture_ui_xml()
        if not xml_str:
            return None
        try:
            root = ET.fromstring(xml_str)
            for node in root.iter("node"):
                cls = node.attrib.get("class", "")
                if any(input_cls in cls for input_cls in ["EditText", "AutoCompleteTextView", "SearchView"]):
                    bounds = node.attrib.get("bounds", "")
                    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
                    if m:
                        x1, y1, x2, y2 = map(int, m.groups())
                        if x2 > x1 and y2 > y1:
                            return ((x1 + x2) // 2, (y1 + y2) // 2)
        except Exception:
            pass
        return None

    def get_visible_elements_summary(self) -> list:
        """Backward-compatible helper that returns visible element dictionaries."""
        tagged = self.get_tagged_elements()
        return list(tagged.values())

    # ─── Visual Perception & Screenshots ─────────────────────────

    def take_screenshot(self, output_path: str = "/data/local/tmp/screen.png") -> str:
        """Captures hardware frame buffer screenshot via SurfaceFlinger."""
        try:
            self.ac.shell(f"screencap -p {output_path} && chmod 644 {output_path}", timeout=6)
            return output_path
        except Exception as e:
            print(f"[Screen Perception Error] Screenshot failed: {e}")
            return ""

    def get_screenshot_base64(self) -> str:
        """Returns base64-encoded string of the current device screen for multimodal vision."""
        temp_path = "/data/local/tmp/jarvis_vision.png"
        res_path = self.take_screenshot(temp_path)
        if not res_path:
            return ""

        try:
            if os.path.exists(temp_path):
                with open(temp_path, "rb") as f:
                    return base64.b64encode(f.read()).decode("utf-8")
        except Exception:
            pass

        try:
            b64 = self.ac.shell(f"base64 -w 0 {temp_path} 2>/dev/null || base64 {temp_path}", timeout=4)
            if b64 and len(b64) > 1000:
                return b64.replace("\n", "").replace("\r", "")
        except Exception:
            pass

        return ""

    def locate_with_vision(self, target_desc: str, brain) -> tuple:
        """Fallback: asks Gemini Vision to visually locate an element by description."""
        if not brain:
            return None
        b64 = self.get_screenshot_base64()
        if not b64:
            return None
        w, h = self.ac.get_screen_size()
        res = brain.locate_element_with_vision(b64, target_desc, screen_width=w, screen_height=h)
        if res and "x" in res and "y" in res:
            x, y = int(res["x"]), int(res["y"])
            if 0 <= x <= w and 0 <= y <= h:
                return (x, y)
        return None
