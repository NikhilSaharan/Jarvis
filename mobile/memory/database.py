"""
SQLite Database manager for J.A.R.V.I.S. Mobile.
Stores scheduled actions, entities, life facts, and quarantined alerts.
Preserved 100% from desktop core with auto-resolving mobile paths.
"""
import sqlite3
import os
from pathlib import Path

DEFAULT_DB_PATH = Path(__file__).resolve().parent.parent / "data" / "jarvis.db"

class KnowledgeDatabase:
    def __init__(self, db_path: Path = DEFAULT_DB_PATH):
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
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
                event_type TEXT,
                details TEXT,
                timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            """)

    def add_entity(self, name: str, relationship: str = None, phone: str = None, notes: str = None, aliases: str = None):
        with self.conn:
            self.conn.execute("""
            INSERT INTO entities (name, relationship, phone, notes, aliases)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(name) DO UPDATE SET
                relationship = COALESCE(excluded.relationship, entities.relationship),
                phone = COALESCE(excluded.phone, entities.phone),
                notes = COALESCE(excluded.notes, entities.notes),
                aliases = COALESCE(excluded.aliases, entities.aliases),
                updated_at = CURRENT_TIMESTAMP;
            """, (name, relationship, phone, notes, aliases or ''))

    def list_entities(self) -> list:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM entities ORDER BY name ASC")
        return [dict(row) for row in cur.fetchall()]

    def get_entity(self, name: str) -> dict:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM entities WHERE name = ? COLLATE NOCASE", (name,))
        row = cur.fetchone()
        return dict(row) if row else None

    def add_scheduled_action(self, target_time: str, action_type: str, target_entity: str, prompt: str):
        with self.conn:
            self.conn.execute("""
            INSERT INTO scheduled_actions (target_time, action_type, target_entity, proactive_speech_prompt)
            VALUES (?, ?, ?, ?)
            """, (target_time, action_type, target_entity, prompt))

    def get_due_actions(self, current_time: str) -> list:
        cur = self.conn.cursor()
        cur.execute("""
        SELECT * FROM scheduled_actions
        WHERE target_time <= ? AND is_executed = 0
        ORDER BY target_time ASC
        """, (current_time,))
        return [dict(row) for row in cur.fetchall()]

    def mark_action_executed(self, action_id: int):
        with self.conn:
            self.conn.execute("UPDATE scheduled_actions SET is_executed = 1 WHERE id = ?", (action_id,))

    def add_quarantined_message(self, source_app: str, sender: str, message_content: str):
        with self.conn:
            self.conn.execute("""
            INSERT INTO quarantined_messages (source_app, sender, message_content)
            VALUES (?, ?, ?)
            """, (source_app, sender, message_content))

    def get_unreviewed_quarantined_messages(self) -> list:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM quarantined_messages WHERE is_reviewed = 0 ORDER BY received_at ASC")
        return [dict(row) for row in cur.fetchall()]

    def mark_quarantined_messages_reviewed(self):
        with self.conn:
            self.conn.execute("UPDATE quarantined_messages SET is_reviewed = 1 WHERE is_reviewed = 0")

    def log_activity(self, event_type: str, details: str):
        with self.conn:
            self.conn.execute("INSERT INTO activity_logs (event_type, details) VALUES (?, ?)", (event_type, details))
