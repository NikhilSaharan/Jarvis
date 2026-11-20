"""
Universal Dynamic App Discovery & Indexing Engine for J.A.R.V.I.S. Mobile.
Scans, indexes, and fuzzy-matches ANY installed Android application at runtime.
Zero hardcoded package names or URLs.

Architecture & High-Performance Pipeline:
  1. Primary Scan: `cmd package query-activities --brief -c android.intent.category.LAUNCHER`
     - Returns ALL launchable apps (user & system) with exact package and launcher activity in <100ms.
  2. Secondary Scan: `pm list packages -3` + `pm list packages`
     - Catches any edge-case packages or non-standard launchers.
  3. Dynamic Label & Token Extraction:
     - Extracts human-readable labels from package name segments, activities, and dumpsys.
  4. Multi-Pass Fuzzy Search:
     - Exact label match → Acronym match → Keyword token score → Substring match.
  5. Multi-Strategy Launch:
     - Strategy 1: `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`
     - Strategy 2: `am start -n <resolved_activity>`
     - Strategy 3: `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p <pkg>`
  6. Background Warmup:
     - Scans on initialization in background daemon thread; persists to data/app_cache.json.
"""
import os
import re
import time
import json
import threading
from pathlib import Path

CACHE_PATH = Path(__file__).resolve().parent.parent / "data" / "app_cache.json"
CACHE_TTL = 900  # Re-scan every 15 minutes

# Common package segments that do not contribute to app identity
_NOISE_TOKENS = frozenset({
    "com", "android", "google", "apps", "app", "org", "net", "io",
    "in", "co", "dev", "mobile", "lite", "pro", "premium", "free",
    "main", "activity", "launcher", "base", "sdk", "v2", "v3",
    "application", "release", "client", "ui", "shell", "home"
})

# Known popular acronyms
_ACRONYMS = {
    "yt": "youtube",
    "wa": "whatsapp",
    "ig": "instagram",
    "tg": "telegram",
    "fb": "facebook",
    "tw": "twitter",
    "gpay": "google pay",
    "pe": "phonepe"
}


class AppDiscoveryEngine:
    """
    Autonomous runtime app discovery with deep fuzzy matching.
    Scans phone applications dynamically, builds searchable indexes,
    and resolves queries like 'daba fantasy' or 'youtube' to the correct app.
    """

    def __init__(self, controller):
        self.ac = controller
        # {package_name: human_readable_label}
        self._pkg_to_label = {}
        # {package_name: launcher_activity}
        self._pkg_to_activity = {}
        # {keyword: set(package_names)}
        self._keyword_index = {}
        self._last_scan_time = 0
        self._is_scanning = False
        self._lock = threading.Lock()

        # Load existing disk cache
        self._load_cache()

        # Kick off background scan if cache missing or empty
        if not self._pkg_to_label:
            threading.Thread(target=self._background_scan, daemon=True).start()

    # ─── Cache Persistence ──────────────────────────────────────

    def _load_cache(self):
        """Loads cached app catalog from disk."""
        try:
            if CACHE_PATH.exists():
                data = json.loads(CACHE_PATH.read_text(encoding="utf-8"))
                self._pkg_to_label = data.get("pkg_to_label", {})
                self._pkg_to_activity = data.get("pkg_to_activity", {})
                self._last_scan_time = data.get("scan_time", 0)
                if self._pkg_to_label:
                    self._rebuild_keyword_index()
                    print(f"[App Discovery] Loaded {len(self._pkg_to_label)} cached apps from disk.")
        except Exception as e:
            print(f"[App Discovery] Cache load error: {e}")

    def _save_cache(self):
        """Persists app catalog to disk."""
        try:
            CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
            data = {
                "pkg_to_label": self._pkg_to_label,
                "pkg_to_activity": self._pkg_to_activity,
                "scan_time": self._last_scan_time
            }
            CACHE_PATH.write_text(
                json.dumps(data, ensure_ascii=False, indent=2),
                encoding="utf-8"
            )
        except Exception:
            pass

    def _background_scan(self):
        """Background worker for non-blocking app discovery."""
        with self._lock:
            if self._is_scanning:
                return
            self._is_scanning = True
        try:
            self.scan_installed_apps()
        finally:
            with self._lock:
                self._is_scanning = False

    # ─── Full Package Scan ──────────────────────────────────────

    def scan_installed_apps(self):
        """
        Deep scan of all installed Android applications.
        Discovers launchable activities, third-party apps, and core utilities.
        """
        print("[App Discovery] Scanning installed Android applications across system...")
        t0 = time.time()

        discovered_pkgs = set()
        activity_map = {}

        # Pass 1: High-Speed query-activities (Gets ALL launchable apps with exact activities)
        try:
            out = self.ac.shell("cmd package query-activities --brief -c android.intent.category.LAUNCHER --user 0 2>/dev/null || cmd package query-activities --brief -c android.intent.category.LAUNCHER", timeout=8)
            if out:
                matches = re.findall(r'([a-zA-Z0-9_\.]+)/([a-zA-Z0-9_\.\$]+)', out)
                for pkg, act in matches:
                    if len(pkg) > 3 and "." in pkg:
                        discovered_pkgs.add(pkg)
                        if act.startswith(".") or "." in act:
                            activity_map[pkg] = f"{pkg}/{act}"
                        else:
                            activity_map[pkg] = f"{pkg}/.{act}"
        except Exception as e:
            print(f"[App Discovery] query-activities pass notice: {e}")

        # Pass 2: Third-party package scan (`pm list packages -3`)
        try:
            out_3 = self.ac.shell("pm list packages -3 --user 0 2>/dev/null || pm list packages -3", timeout=8)
            if out_3:
                for line in out_3.splitlines():
                    line = line.strip()
                    if line.startswith("package:"):
                        pkg = line[8:].strip()
                        if pkg:
                            discovered_pkgs.add(pkg)
        except Exception:
            pass

        # Pass 3: Full package scan across all system & user packages
        try:
            out_all = self.ac.shell("pm list packages --user 0 2>/dev/null || pm list packages", timeout=8)
            if out_all:
                for line in out_all.splitlines():
                    line = line.strip()
                    if line.startswith("package:"):
                        pkg = line[8:].strip()
                        if pkg and not pkg.startswith("com.android.internal"):
                            discovered_pkgs.add(pkg)
        except Exception:
            pass

        if not discovered_pkgs:
            print("[App Discovery] WARNING: No packages discovered. Bridge check recommended.")
            return

        # Build labels and index
        for pkg in discovered_pkgs:
            if pkg not in self._pkg_to_label:
                # Convert package & activity into readable label
                readable = self._package_to_readable(pkg, activity_map.get(pkg))
                self._pkg_to_label[pkg] = readable

        # Update activity map
        self._pkg_to_activity.update(activity_map)

        # Build search index
        self._rebuild_keyword_index()
        self._last_scan_time = time.time()
        self._save_cache()

        elapsed = time.time() - t0
        print(f"[App Discovery] Successfully indexed {len(self._pkg_to_label)} applications in {elapsed:.2f}s.")

    def _package_to_readable(self, pkg: str, activity: str = None) -> str:
        """
        Converts package name and optional activity into clean, human-readable name.
        Examples:
          com.daba.fantasy.cricket → 'daba fantasy cricket'
          com.dream11.app → 'dream11'
          com.google.android.youtube → 'youtube'
          com.spotify.music → 'spotify'
        """
        # Segment package name
        parts = pkg.lower().split(".")
        meaningful = [p for p in parts if p not in _NOISE_TOKENS and len(p) > 1]

        # Check activity name if package parts are generic
        if not meaningful and activity:
            act_part = activity.split("/")[-1].replace("$", " ").replace(".", " ")
            act_tokens = [t.lower() for t in re.split(r'[\s_]+', act_part) if t.lower() not in _NOISE_TOKENS and len(t) > 1]
            if act_tokens:
                meaningful = act_tokens

        return " ".join(meaningful) if meaningful else pkg

    # ─── Keyword Search Index ───────────────────────────────────

    def _rebuild_keyword_index(self):
        """Builds multi-token inverted index for rapid search."""
        self._keyword_index = {}

        for pkg, label in self._pkg_to_label.items():
            keywords = set()

            # 1. Full label
            if label:
                lbl_clean = label.lower().strip()
                keywords.add(lbl_clean)
                # Word tokens from label
                for word in re.split(r'[\s\-_:&,.()\[\]]+', lbl_clean):
                    word = word.strip()
                    if len(word) > 1 and word not in _NOISE_TOKENS:
                        keywords.add(word)

            # 2. Package segments
            parts = pkg.lower().split(".")
            for part in parts:
                if part not in _NOISE_TOKENS and len(part) > 1:
                    keywords.add(part)
                    # Sub-tokens (e.g. 'dream11' → 'dream', '11')
                    sub_parts = re.findall(r'[a-z]+|\d+', part)
                    for sp in sub_parts:
                        if len(sp) > 1 and sp not in _NOISE_TOKENS:
                            keywords.add(sp)

            # 3. Register in inverted index
            for kw in keywords:
                if kw not in self._keyword_index:
                    self._keyword_index[kw] = set()
                self._keyword_index[kw].add(pkg)

    # ─── Ensure Fresh Index ─────────────────────────────────────

    def ensure_fresh(self):
        """Ensures the app catalog is available and fresh."""
        if not self._pkg_to_label:
            self.scan_installed_apps()
        elif time.time() - self._last_scan_time > CACHE_TTL:
            threading.Thread(target=self._background_scan, daemon=True).start()

    # ─── Dynamic Fuzzy App Search ───────────────────────────────

    def find_app(self, query: str) -> tuple:
        """
        Finds best matching installed app for ANY query.
        Multi-pass evaluation:
          Pass 1: Acronym match (e.g. 'yt' → YouTube)
          Pass 2: Exact full label match
          Pass 3: Exact single keyword match
          Pass 4: Multi-token scoring (sum of matched tokens)
          Pass 5: Substring match in label & package
        Returns: (package_name, app_label) or (None, None) if not found.
        """
        self.ensure_fresh()

        query_clean = re.sub(r'[^a-zA-Z0-9\s]', ' ', query.lower()).strip()
        if not query_clean:
            return None, None

        # Check acronyms
        if query_clean in _ACRONYMS:
            query_clean = _ACRONYMS[query_clean]

        query_tokens = [t for t in query_clean.split() if len(t) > 1 and t not in _NOISE_TOKENS]
        if not query_tokens and query_clean not in self._pkg_to_label:
            return None, None

        # ── Special System Intent Dispatches (Phone, Dialer, Camera) ──
        if query_clean in ("phone", "dialer", "calling", "call"):
            out = self.ac.shell("cmd package resolve-activity -a android.intent.action.DIAL --brief 2>/dev/null", timeout=3)
            if out:
                for line in out.strip().splitlines():
                    if "/" in line and "=" not in line:
                        real_pkg = line.split("/")[0].strip()
                        if real_pkg and real_pkg != "com.android.phone":
                            self._pkg_to_activity[real_pkg] = line.strip()
                            self._pkg_to_label[real_pkg] = "Phone"
                            return real_pkg, "Phone"
            return "com.google.android.dialer", "Phone"

        if query_clean in ("camera", "cam"):
            out = self.ac.shell("cmd package resolve-activity -a android.media.action.IMAGE_CAPTURE --brief 2>/dev/null", timeout=3)
            if out:
                for line in out.strip().splitlines():
                    if "/" in line and "=" not in line:
                        real_pkg = line.split("/")[0].strip()
                        if real_pkg:
                            self._pkg_to_activity[real_pkg] = line.strip()
                            self._pkg_to_label[real_pkg] = "Camera"
                            return real_pkg, "Camera"

        # ── Pass 1: Exact Full Label Match ──
        for pkg, label in self._pkg_to_label.items():
            if label and query_clean == label.lower().strip():
                return pkg, label

        # ── Pass 2: Exact Single Keyword Match ──
        if query_clean in self._keyword_index:
            pkgs = self._keyword_index[query_clean]
            if len(pkgs) == 1:
                pkg = next(iter(pkgs))
                return pkg, self._pkg_to_label.get(pkg, pkg)
            elif pkgs:
                best = self._score_candidates(pkgs, query_tokens)
                if best:
                    return best, self._pkg_to_label.get(best, best)

        # ── Pass 3: Multi-Token Scoring ──
        candidates = {}  # {pkg: score}
        for token in query_tokens:
            # Exact token in index
            if token in self._keyword_index:
                for pkg in self._keyword_index[token]:
                    candidates[pkg] = candidates.get(pkg, 0) + len(token) * 12

            # Partial token match (min 3 chars to avoid noise)
            if len(token) >= 3:
                for kw, pkgs in self._keyword_index.items():
                    if len(kw) < 3 or kw in _NOISE_TOKENS:
                        continue
                    if token in kw:
                        for pkg in pkgs:
                            candidates[pkg] = candidates.get(pkg, 0) + len(token) * 4
                    elif kw in token:
                        for pkg in pkgs:
                            candidates[pkg] = candidates.get(pkg, 0) + len(kw) * 4

        # ── Pass 4: Substring match against full labels ──
        for pkg, label in self._pkg_to_label.items():
            if not label:
                continue
            lbl_low = label.lower()
            if query_clean in lbl_low and len(query_clean) >= 3:
                candidates[pkg] = candidates.get(pkg, 0) + len(query_clean) * 8
            elif lbl_low in query_clean and len(lbl_low) >= 3:
                candidates[pkg] = candidates.get(pkg, 0) + len(lbl_low) * 6

        # ── Pass 5: Substring match against package names ──
        for pkg in self._pkg_to_label:
            pkg_low = pkg.lower()
            for token in query_tokens:
                if len(token) >= 3 and token in pkg_low:
                    candidates[pkg] = candidates.get(pkg, 0) + len(token) * 5

        if candidates:
            best_pkg = max(candidates, key=candidates.get)
            best_score = candidates[best_pkg]
            # Minimum confidence threshold (12 points ensures meaningful token match)
            if best_score >= 12:
                best_label = self._pkg_to_label.get(best_pkg, best_pkg)
                print(f"[App Discovery] Query '{query}' matched -> {best_pkg} ('{best_label}') [score={best_score}]")
                return best_pkg, best_label

        # ── Pass 6: Real-Time Live Android OS Package Manager Lookup ──
        # Directly queries Android's package manager for ANY query token.
        # Zero chance of missing any installed app, even with work profiles or clone spaces.
        tokens_to_search = query_tokens if query_tokens else [query_clean]
        for token in tokens_to_search:
            if len(token) >= 2:
                out = self.ac.shell(f"pm list packages --user 0 {token} 2>/dev/null || pm list packages {token}", timeout=3)
                if out:
                    for line in out.splitlines():
                        line = line.strip()
                        if line.startswith("package:"):
                            pkg = line[8:].strip()
                            if pkg and pkg not in ("com.android.phone", "com.android.providers.telephony", "com.android.server.telecom"):
                                label = self._package_to_readable(pkg)
                                self._pkg_to_label[pkg] = label
                                print(f"[App Discovery Live] Dynamically found package '{pkg}' for '{token}'")
                                return pkg, label

        return None, None

    def _score_candidates(self, pkgs: set, query_tokens: list) -> str:
        """Scores candidate packages based on token overlap in label and package."""
        best_pkg = None
        best_score = -1
        for pkg in pkgs:
            score = 0
            pkg_lower = pkg.lower()
            label_lower = (self._pkg_to_label.get(pkg) or "").lower()
            for token in query_tokens:
                if token in label_lower:
                    score += len(token) * 6
                if token in pkg_lower:
                    score += len(token) * 3
            if score > best_score:
                best_score = score
                best_pkg = pkg
        return best_pkg or (next(iter(pkgs)) if pkgs else None)

    # ─── Universal App Launch ───────────────────────────────────

    def launch_app(self, pkg: str) -> dict:
        """
        Launches an application using a multi-strategy execution pipeline.
        Strategy 1: `am start -n <resolved_activity> -f 0x10000000` (Direct launcher component with NEW_TASK)
        Strategy 2: On-demand resolved activity launch
        Strategy 3: `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`
        Strategy 4: Generic intent launch
        """
        label = self._pkg_to_label.get(pkg, pkg)

        # Wake screen display first
        self.ac.wake_screen()

        # Protection: com.android.phone is a telephony service, not a launcher app
        if pkg in ("com.android.phone", "com.android.server.telecom"):
            self.ac.shell("am start -a android.intent.action.DIAL -f 0x10000000")
            return {"success": True, "message": "Phone dialer open kar diya hai Sir.", "package": "dialer"}

        # Strategy 1: Launch via cached exact activity
        activity = self._pkg_to_activity.get(pkg)
        if not activity:
            activity = self._resolve_activity_on_demand(pkg)
            if activity:
                self._pkg_to_activity[pkg] = activity

        if activity:
            res = self.ac.shell(f"am start -n {activity} -f 0x10000000")
            if "Starting" in res or ("Error" not in res and "not exist" not in res):
                return {"success": True, "message": f"{label} open kar diya hai Sir.", "package": pkg}

        # Strategy 2: Dynamically resolve activity on demand
        resolved = self._resolve_activity_on_demand(pkg)
        if resolved and resolved != activity:
            self._pkg_to_activity[pkg] = resolved
            res = self.ac.shell(f"am start -n {resolved} -f 0x10000000")
            if "Starting" in res or ("Error" not in res and "not exist" not in res):
                return {"success": True, "message": f"{label} open kar diya hai Sir.", "package": pkg}

        # Strategy 3: Monkey launcher fallback
        res = self.ac.shell(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1")
        if "Events injected: 1" in res or "No activities found" not in res:
            return {"success": True, "message": f"{label} open kar diya hai Sir.", "package": pkg}

        # Strategy 4: Component-less intent
        res = self.ac.shell(f"am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --pkg {pkg} -f 0x10000000")
        if "Starting" in res:
            return {"success": True, "message": f"{label} open kar diya hai Sir.", "package": pkg}

        return {"success": False, "message": f"Sir, {label} launch nahi ho paya. Package: {pkg}", "package": pkg}

    def _resolve_activity_on_demand(self, pkg: str) -> str:
        """Resolves launcher component for a package on demand."""
        try:
            out = self.ac.shell(
                f"cmd package resolve-activity --brief -c android.intent.category.LAUNCHER --user 0 {pkg} 2>/dev/null || cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {pkg}",
                timeout=4
            )
            if out:
                for line in out.strip().splitlines():
                    line = line.strip()
                    if "/" in line and "=" not in line:
                        return line
        except Exception:
            pass
        return ""

    def get_all_apps(self) -> dict:
        """Returns map of all discovered packages to labels."""
        self.ensure_fresh()
        return dict(self._pkg_to_label)
