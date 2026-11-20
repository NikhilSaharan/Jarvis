"""
Disturbance Engine for J.A.R.V.I.S. Mobile.
Evaluates incoming notifications and calls against Sir's current state.
If Sir is resting or busy, quarantines non-critical alerts in SQLite.
If the alert is from Mummy, family, or critical VIP, passes through immediately.
"""
from core.state_machine import JarvisState, SystemState
from memory.database import KnowledgeDatabase

class DisturbanceEngine:
    def __init__(self, state: JarvisState, db: KnowledgeDatabase):
        self.state = state
        self.db = db

    def evaluate_incoming_event(self, source_app: str, sender: str, content: str) -> dict:
        """
        Evaluates whether to disturb Sir immediately or quarantine.
        Returns:
            {"allow_disturbance": bool, "reason": str, "is_vip": bool}
        """
        sender_lower = (sender or "").lower().strip()
        content_lower = (content or "").lower().strip()

        # Check VIP status in SQLite Memory
        is_vip = False
        vip_entities = self.db.list_entities()
        for ent in vip_entities:
            ent_name = (ent.get("name") or "").lower()
            ent_rel = (ent.get("relationship") or "").lower()
            if ent_name in sender_lower or sender_lower in ent_name:
                is_vip = True
                break
            if ent_rel in ("mother", "mummy", "father", "papa", "family", "vip", "urgent"):
                if ent_name in sender_lower:
                    is_vip = True
                    break

        # Emergency keywords
        is_emergency = any(k in content_lower for k in ["emergency", "hospital", "urgent", "turant", "jaldi", "otp"])

        # If Sir is in NORMAL mode, allow notification
        if self.state.current_state == SystemState.NORMAL:
            return {"allow_disturbance": True, "reason": "Normal operational state", "is_vip": is_vip}

        # If Sir is SLEEPING or in DND
        if self.state.current_state in (SystemState.SLEEPING, SystemState.DND):
            if is_emergency or (is_vip and "mummy" in sender_lower):
                return {
                    "allow_disturbance": True,
                    "reason": f"Urgent priority from VIP: {sender}",
                    "is_vip": is_vip
                }
            else:
                # Quarantine in database
                self.db.add_quarantined_message(
                    source_app=source_app,
                    sender=sender,
                    message_content=content
                )
                return {
                    "allow_disturbance": False,
                    "reason": f"Quarantined while Sir is in {self.state.current_state.value} mode",
                    "is_vip": is_vip
                }

        return {"allow_disturbance": True, "reason": "Default passthrough", "is_vip": is_vip}
