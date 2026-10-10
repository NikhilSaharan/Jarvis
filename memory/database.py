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
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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
            """)

    def add_person(self, name: str, relationship: str = "", phone: str = "", notes: str = ""):
        with self.conn:
            self.conn.execute("""
                INSERT INTO entities (name, relationship, phone, notes, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(name) DO UPDATE SET
                    relationship = excluded.relationship,
                    notes = excluded.notes,
                    updated_at = CURRENT_TIMESTAMP
            """, (name, relationship, phone, notes))

    def get_person(self, name: str):
        cursor = self.conn.cursor()
        cursor.execute("SELECT name, relationship, phone, notes FROM entities WHERE name = ?", (name,))
        row = cursor.fetchone()
        if row:
            return {"name": row[0], "relationship": row[1], "phone": row[2], "notes": row[3]}
        return None

    def add_scheduled_action(self, target_time: str, action_type: str, target_entity: str, proactive_speech: str):
        with self.conn:
            cursor = self.conn.execute("""
                INSERT INTO scheduled_actions (target_time, action_type, target_entity, proactive_speech_prompt)
                VALUES (?, ?, ?, ?)
            """, (target_time, action_type, target_entity, proactive_speech))
            return cursor.lastrowid

    def get_pending_actions(self, current_time_str: str):
        cursor = self.conn.cursor()
        cursor.execute("""
            SELECT id, target_time, action_type, target_entity, proactive_speech_prompt
            FROM scheduled_actions
            WHERE target_time <= ? AND is_executed = 0
        """, (current_time_str,))
        return cursor.fetchall()

    def mark_action_executed(self, action_id: int):
        with self.conn:
            self.conn.execute("UPDATE scheduled_actions SET is_executed = 1 WHERE id = ?", (action_id,))
