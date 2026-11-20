"""
Telephony and Call Control Engine for J.A.R.V.I.S. Mobile.
Gives Jarvis full autonomous authority over cellular calls:
- Direct SIM calling by contact name ("Anuj ko call lagao") or phone number.
- Automatic contact resolution via Android Contacts Content Provider.
- Universal dialer launch fallback (never crashes on com.android.phone).
- Cut or reject incoming/active calls via Android Telecom Subsystem.
- Answer ringing calls hands-free.
"""
import re
import time

class TelephonyManager:
    """Controls Cellular Calls: Contact Resolution, Dialing, Answering, and Disconnecting."""

    def __init__(self, controller, db=None):
        self.ac = controller
        self.db = db
        self._contacts_cache = {}
        self._last_contacts_sync = 0

    def sync_contacts(self) -> dict:
        """
        Queries Android's Contacts Provider via Shizuku shell.
        Returns a dictionary of {contact_name_lower: phone_number}.
        """
        try:
            # Android Contacts content provider query (requires READ_CONTACTS, standard on shell UID 2000)
            cmd = "content query --uri content://com.android.contacts/data/phones --projection display_name:data1"
            out = self.ac.shell(cmd, timeout=5)
            if not out:
                return self._contacts_cache

            contacts = {}
            # Content query output format:
            # Row: 0 display_name=Anuj, data1=+919876543210
            for line in out.splitlines():
                line = line.strip()
                if "display_name=" in line and "data1=" in line:
                    m_name = re.search(r'display_name=([^,]+)', line)
                    m_num = re.search(r'data1=([^,]+)', line)
                    if m_name and m_num:
                        name = m_name.group(1).strip()
                        num = m_num.group(1).strip()
                        if name and num:
                            contacts[name.lower()] = num

            # Also query WhatsApp specific contacts if available
            try:
                wa_cmd = "content query --uri content://com.android.contacts/data --projection display_name:data1 --where \"mimetype='vnd.android.cursor.item/vnd.com.whatsapp.profile'\""
                wa_out = self.ac.shell(wa_cmd, timeout=4)
                if wa_out:
                    for line in wa_out.splitlines():
                        if "display_name=" in line and "data1=" in line:
                            m_name = re.search(r'display_name=([^,]+)', line)
                            m_num = re.search(r'data1=([^,]+)', line)
                            if m_name and m_num:
                                name = m_name.group(1).strip()
                                num = m_num.group(1).strip()
                                if name and num and name.lower() not in contacts:
                                    contacts[name.lower()] = num
            except Exception:
                pass

            if contacts:
                self._contacts_cache = contacts
                self._last_contacts_sync = time.time()
                print(f"[Telephony Engine] Synced {len(contacts)} contacts from device.")
        except Exception as e:
            print(f"[Telephony Engine] Contact sync warning: {e}")

        return self._contacts_cache

    def resolve_contact_number(self, target: str) -> tuple:
        """
        Resolves a name or number to a callable phone number.
        Uses strict token matching and high-similarity matching.
        NEVER uses short 3-letter prefix matching (which incorrectly matched Manoj for Manish).
        Returns: (phone_number, contact_display_name) or (None, None).
        """
        if not target:
            return None, None

        cleaned_query = target.strip().lower()

        # Check SQLite KnowledgeDatabase entities first
        if self.db:
            try:
                ent = self.db.get_entity(cleaned_query)
                if ent and ent.get("phone"):
                    return ent["phone"], ent.get("name", cleaned_query).title()
            except Exception:
                pass

        # If it's already a raw phone number (7+ digits)
        digits_only = re.sub(r"[^\d+]", "", cleaned_query)
        if len(digits_only) >= 7:
            return digits_only, digits_only

        # Sync contacts if cache is empty or older than 10 minutes
        if not self._contacts_cache or (time.time() - self._last_contacts_sync > 600):
            self.sync_contacts()

        # 1. Exact match in contacts
        if cleaned_query in self._contacts_cache:
            return self._contacts_cache[cleaned_query], cleaned_query.title()

        # 2. Exact full token match in multi-word contact names (e.g. 'Manish' matches 'Manish Kumar' or 'Bhai Manish')
        query_words = [w for w in re.split(r'\s+', cleaned_query) if w]
        for name, num in self._contacts_cache.items():
            name_words = [w for w in re.split(r'\s+', name) if w]
            # If query is a single word, check if it matches any word in the contact name
            if len(query_words) == 1:
                q = query_words[0]
                if q in name_words:
                    return num, name.title()
            # If multi-word query, check if all query words are in contact name
            elif all(qw in name_words for qw in query_words):
                return num, name.title()

        # 3. Whole word startswith match (ONLY for words >= 4 chars to prevent false positives like 'man' matching 'manoj')
        if len(cleaned_query) >= 4:
            for name, num in self._contacts_cache.items():
                name_words = [w for w in re.split(r'\s+', name) if w]
                if any(w.startswith(cleaned_query) for w in name_words):
                    return num, name.title()

        # 4. Collapsed repeat characters (e.g. 'himuu' matches 'himu', 'manishh' matches 'manish')
        dedup_query = re.sub(r'(.)\1+', r'\1', cleaned_query)
        for name, num in self._contacts_cache.items():
            dedup_name = re.sub(r'(.)\1+', r'\1', name)
            if dedup_query == dedup_name:
                return num, name.title()

        # 5. High-confidence fuzzy string matching (cutoff >= 0.85)
        import difflib
        matches = difflib.get_close_matches(cleaned_query, self._contacts_cache.keys(), n=1, cutoff=0.85)
        if matches:
            best_match = matches[0]
            return self._contacts_cache[best_match], best_match.title()

        return None, None

    def call_contact(self, recipient_or_number: str) -> dict:
        """
        Places a cellular call to a contact name or phone number.
        Uses universal Android CALL intent with automatic DIAL intent fallback.
        """
        target = (recipient_or_number or "").strip()
        if not target:
            # Simply open the dialer
            return self.open_dialer()

        phone_num, display_name = self.resolve_contact_number(target)

        if phone_num:
            cleaned = re.sub(r"[^\d+]", "", phone_num)
            print(f"[Telephony Engine] Calling {display_name} ({cleaned}) on SIM...")
            # CALL intent initiates the phone call immediately
            res = self.ac.shell(f"am start -a android.intent.action.CALL -d tel:{cleaned}")
            if "Error" in res or "SecurityException" in res:
                # If CALL requires runtime confirmation, fallback to DIAL intent
                res = self.ac.shell(f"am start -a android.intent.action.DIAL -d tel:{cleaned}")
            return {
                "success": True,
                "message": f"{display_name} ko call mila diya hai Sir.",
                "number": cleaned,
                "contact": display_name
            }
        else:
            # Contact not found in address book, open dialer with query
            print(f"[Telephony Engine] Contact '{target}' not found in address book. Opening dialer...")
            self.ac.shell("am start -a android.intent.action.DIAL")
            time.sleep(1.0)
            # Try to search or type the name into dialer
            self.ac.type_text(target, press_enter=True)
            return {
                "success": True,
                "message": f"Sir, '{target}' contacts mein nahi mila, dialer open kar diya hai."
            }

    def open_dialer(self) -> dict:
        """Opens the device's default phone dialer application safely."""
        self.ac.shell("am start -a android.intent.action.DIAL")
        return {
            "success": True,
            "message": "Phone dialer open kar diya hai Sir."
        }

    def make_sim_call(self, phone_number: str) -> dict:
        """Dials directly via phone SIM card."""
        return self.call_contact(phone_number)

    def reject_or_cut_call(self) -> dict:
        """Immediately hangs up or rejects active/incoming call."""
        res = self.ac.shell("telecom end-call")
        return {
            "success": True,
            "message": "Call cut/disconnected successfully.",
            "output": res
        }

    def answer_call(self) -> dict:
        """Answers incoming ringing call."""
        res = self.ac.shell("telecom accept-ringing-call")
        return {
            "success": True,
            "message": "Call answered.",
            "output": res
        }

