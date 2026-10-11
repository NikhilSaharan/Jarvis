"""
SQLite Database manager for Jarvis.
Stores scheduled actions, entities, and quarantined alerts.
"""
import sqlite3
import os
from pathlib import Path

DB_PATH = Path(__file__).resolve().parent.parent / "data" / "jarvis.db"

class KnowledgeDatabase:
    def __init__(self, db_path: Path = DB_PATH):
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self._init_tables()

    def _init_tables(self):
        with self.conn:
            self.conn.executescript("""
            CREATE TABLE IF NOT EXISTS entities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT UNIQUE COLLATE NOCASE,
                relationship TEXT,
                phone TEXT,
                notes TEXT,
                aliases TEXT DEFAULT '',
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS life_facts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                entity_name TEXT COLLATE NOCASE,
                category TEXT DEFAULT 'general',
                fact_key TEXT,
                fact_value TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS scheduled_actions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                target_time TEXT,
                action_type TEXT,
                target_entity TEXT,
                proactive_speech_prompt TEXT,
                is_executed INTEGER DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS quarantined_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_app TEXT,
                sender TEXT,
                message_content TEXT,
                received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                is_reviewed INTEGER DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS activity_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                window_title TEXT,
                process_name TEXT,
                duration_seconds INTEGER,
                timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            """)

            # Ensure aliases and importance columns exist in existing database
            cursor = self.conn.cursor()
            cursor.execute("PRAGMA table_info(entities)")
            cols = [c[1] for c in cursor.fetchall()]
            if "aliases" not in cols:
                cursor.execute("ALTER TABLE entities ADD COLUMN aliases TEXT DEFAULT ''")
            if "importance" not in cols:
                cursor.execute("ALTER TABLE entities ADD COLUMN importance INTEGER DEFAULT 1")

    def add_person(self, name: str, relationship: str = "", phone: str = "", notes: str = "", aliases: str = "", importance: int = 1):
        """Stores or updates a person and their relationship in the Knowledge Graph with importance level."""
        with self.conn:
            self.conn.execute("""
                INSERT INTO entities (name, relationship, phone, notes, aliases, importance, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(name) DO UPDATE SET
                    relationship = CASE WHEN excluded.relationship != '' THEN excluded.relationship ELSE entities.relationship END,
                    phone = CASE WHEN excluded.phone != '' THEN excluded.phone ELSE entities.phone END,
                    notes = CASE 
                        WHEN excluded.notes != '' AND entities.notes != '' AND instr(entities.notes, excluded.notes) = 0
                        THEN entities.notes || '; ' || excluded.notes
                        WHEN excluded.notes != '' THEN excluded.notes
                        ELSE entities.notes
                    END,
                    aliases = CASE WHEN excluded.aliases != '' THEN excluded.aliases ELSE entities.aliases END,
                    importance = CASE WHEN excluded.importance > 1 THEN excluded.importance ELSE entities.importance END,
                    updated_at = CURRENT_TIMESTAMP
            """, (name.strip(), relationship.strip(), phone.strip(), notes.strip(), aliases.strip(), int(importance)))

    def get_person(self, name: str):
        """Fetches a specific person by exact name."""
        cursor = self.conn.cursor()
        cursor.execute("SELECT name, relationship, phone, notes, aliases, importance FROM entities WHERE name = ?", (name.strip(),))
        row = cursor.fetchone()
        if row:
            return {"name": row[0], "relationship": row[1], "phone": row[2], "notes": row[3], "aliases": row[4], "importance": row[5] or 1}
        return None

    def resolve_entity(self, query: str):
        """
        Intelligently resolves a person by exact name, alias, partial name, or relationship role
        (e.g. 'Dhruv', 'manager', 'friend', 'brother', 'papa', 'my colleague').
        """
        clean_q = query.strip().lower()
        for prefix in ("my ", "mera ", "meri ", "our ", "the "):
            if clean_q.startswith(prefix):
                clean_q = clean_q[len(prefix):].strip()

        cursor = self.conn.cursor()

        # 1. Exact match on name
        cursor.execute("SELECT name, relationship, phone, notes, aliases, importance FROM entities WHERE lower(name) = ?", (clean_q,))
        row = cursor.fetchone()
        if row:
            return {"name": row[0], "relationship": row[1], "phone": row[2], "notes": row[3], "aliases": row[4], "importance": row[5] or 1}

        # 2. Match on relationship (e.g. user asks for 'colleague' or 'manager')
        cursor.execute("SELECT name, relationship, phone, notes, aliases, importance FROM entities WHERE lower(relationship) LIKE ?", (f"%{clean_q}%",))
        row = cursor.fetchone()
        if row:
            return {"name": row[0], "relationship": row[1], "phone": row[2], "notes": row[3], "aliases": row[4], "importance": row[5] or 1}

        # 3. Match on aliases
        cursor.execute("SELECT name, relationship, phone, notes, aliases, importance FROM entities WHERE lower(aliases) LIKE ?", (f"%{clean_q}%",))
        row = cursor.fetchone()
        if row:
            return {"name": row[0], "relationship": row[1], "phone": row[2], "notes": row[3], "aliases": row[4], "importance": row[5] or 1}

        # 4. Partial substring match on name
        cursor.execute("SELECT name, relationship, phone, notes, aliases, importance FROM entities WHERE lower(name) LIKE ?", (f"%{clean_q}%",))
        row = cursor.fetchone()
        if row:
            return {"name": row[0], "relationship": row[1], "phone": row[2], "notes": row[3], "aliases": row[4], "importance": row[5] or 1}

        return None

    def get_all_entities(self) -> list:
        """Retrieves all stored entities in the Knowledge Graph."""
        cursor = self.conn.cursor()
        cursor.execute("SELECT name, relationship, phone, notes, aliases, importance, updated_at FROM entities ORDER BY importance DESC, name ASC")
        rows = cursor.fetchall()
        return [
            {
                "name": r[0],
                "relationship": r[1] or "Unspecified",
                "phone": r[2] or "N/A",
                "notes": r[3] or "",
                "aliases": r[4] or "",
                "importance": r[5] or 1,
                "updated_at": r[6]
            }
            for r in rows
        ]

    def log_activity_session(self, window_title: str, process_name: str, duration_seconds: int) -> int:
        """Logs user active focus time on applications."""
        return self.log_window_activity(window_title, process_name, duration_seconds)

    def log_window_activity(self, window_title: str, process_name: str, duration_seconds: int = 0) -> int:
        """Logs active window switch or application event with local timestamp."""
        if not window_title and not process_name:
            return 0
        with self.conn:
            cursor = self.conn.execute("""
                INSERT INTO activity_logs (window_title, process_name, duration_seconds, timestamp)
                VALUES (?, ?, ?, datetime('now', 'localtime'))
            """, (window_title.strip()[:250], process_name.strip()[:100], int(duration_seconds)))
            return cursor.lastrowid

    def get_recent_activity_logs(self, minutes: int = 5, limit: int = 40) -> list:
        """Fetches chronological window and application activity for the past N minutes."""
        cursor = self.conn.cursor()
        cursor.execute("""
            SELECT window_title, process_name, duration_seconds, timestamp
            FROM activity_logs
            WHERE timestamp >= datetime('now', 'localtime', '-' || ? || ' minutes')
            ORDER BY id DESC
            LIMIT ?
        """, (int(minutes), int(limit)))
        rows = cursor.fetchall()
        # Return in chronological order (oldest to newest)
        return [
            {
                "window_title": r[0],
                "process_name": r[1],
                "duration_seconds": r[2],
                "timestamp": r[3]
            }
            for r in reversed(rows)
        ]


    def delete_entity(self, name: str) -> bool:
        """Removes an entity from the Knowledge Graph."""
        with self.conn:
            cursor = self.conn.execute("DELETE FROM entities WHERE lower(name) = ?", (name.strip().lower(),))
            return cursor.rowcount > 0

    def add_life_fact(self, entity_name: str, fact_key: str, fact_value: str, category: str = "general") -> int:
        """Records an episodic fact or habit for an entity."""
        with self.conn:
            cursor = self.conn.execute("""
                INSERT INTO life_facts (entity_name, category, fact_key, fact_value)
                VALUES (?, ?, ?, ?)
            """, (entity_name.strip(), category.strip(), fact_key.strip(), fact_value.strip()))
            return cursor.lastrowid

    def get_entity_facts(self, entity_name: str) -> list:
        """Retrieves all life facts recorded for a specific entity."""
        cursor = self.conn.cursor()
        cursor.execute("""
            SELECT category, fact_key, fact_value, created_at
            FROM life_facts
            WHERE lower(entity_name) = ?
            ORDER BY created_at DESC
        """, (entity_name.strip().lower(),))
        rows = cursor.fetchall()
        return [{"category": r[0], "key": r[1], "value": r[2], "created_at": r[3]} for r in rows]


    def add_scheduled_action(self, target_time: str, action_type: str, target_entity: str, proactive_speech: str):
        with self.conn:
            cursor = self.conn.execute("""
                INSERT INTO scheduled_actions (target_time, action_type, target_entity, proactive_speech_prompt)
                VALUES (?, ?, ?, ?)
            """, (target_time, action_type, target_entity, proactive_speech))
            return cursor.lastrowid

    def get_pending_actions(self, current_time_str: str = None):
        """
        Retrieves all scheduled actions that are due.
        Supports:
        - 'HH:MM' (24-hour time of day)
        - 'YYYY-MM-DD HH:MM:SS' (precise timestamp)
        - numeric epoch timestamps
        """
        import datetime
        now = datetime.datetime.now()
        if not current_time_str:
            current_time_str = now.strftime("%H:%M")
        current_full_timestamp = now.strftime("%Y-%m-%d %H:%M:%S")

        cursor = self.conn.cursor()
        cursor.execute("""
            SELECT id, target_time, action_type, target_entity, proactive_speech_prompt
            FROM scheduled_actions
            WHERE is_executed = 0
        """)
        all_pending = cursor.fetchall()

        due_actions = []
        for row in all_pending:
            action_id, target_time, action_type, entity, speech = row
            target_str = str(target_time).strip()

            is_due = False
            if len(target_str) == 5 and ":" in target_str:
                if target_str <= current_time_str:
                    is_due = True
            elif len(target_str) >= 16:
                if target_str <= current_full_timestamp:
                    is_due = True
            elif target_str.replace(".", "", 1).isdigit():
                if float(target_str) <= now.timestamp():
                    is_due = True
            else:
                if target_str <= current_time_str:
                    is_due = True

            if is_due:
                due_actions.append(row)

        return due_actions

    def mark_action_executed(self, action_id: int):
        with self.conn:
            self.conn.execute("UPDATE scheduled_actions SET is_executed = 1 WHERE id = ?", (action_id,))

    def quarantine_message(self, source_app: str, sender: str, message_content: str) -> int:
        """Logs an incoming communication/disturbance during Rest or DND mode."""
        with self.conn:
            cursor = self.conn.execute("""
                INSERT INTO quarantined_messages (source_app, sender, message_content)
                VALUES (?, ?, ?)
            """, (source_app, sender, message_content))
            return cursor.lastrowid

    def get_unreviewed_quarantined_messages(self) -> list:
        """Retrieves all pending quarantined messages that haven't been briefed to the user."""
        cursor = self.conn.cursor()
        cursor.execute("""
            SELECT id, source_app, sender, message_content, received_at
            FROM quarantined_messages
            WHERE is_reviewed = 0
            ORDER BY received_at ASC
        """)
        rows = cursor.fetchall()
        return [
            {
                "id": r[0],
                "source_app": r[1],
                "sender": r[2],
                "message_content": r[3],
                "received_at": r[4]
            }
            for r in rows
        ]

    def mark_quarantined_messages_reviewed(self, message_ids: list = None):
        """Marks quarantined messages as reviewed/briefed."""
        with self.conn:
            if message_ids:
                placeholders = ",".join("?" for _ in message_ids)
                self.conn.execute(
                    f"UPDATE quarantined_messages SET is_reviewed = 1 WHERE id IN ({placeholders})",
                    message_ids
                )
            else:
                self.conn.execute("UPDATE quarantined_messages SET is_reviewed = 1 WHERE is_reviewed = 0")

    def get_all_quarantined_messages(self, limit: int = 20) -> list:
        """Fetches the most recent quarantined messages."""
        cursor = self.conn.cursor()
        cursor.execute("""
            SELECT id, source_app, sender, message_content, received_at, is_reviewed
            FROM quarantined_messages
            ORDER BY received_at DESC
            LIMIT ?
        """, (limit,))
        rows = cursor.fetchall()
        return [
            {
                "id": r[0],
                "source_app": r[1],
                "sender": r[2],
                "message_content": r[3],
                "received_at": r[4],
                "is_reviewed": bool(r[5])
            }
            for r in rows
        ]

