#!/usr/bin/env python3
import sqlite3
import os
import sys

def init_db(db_path: str = "music_engine.db", schema_path: str = "db/schema.sql"):
    if not os.path.exists(schema_path):
        if os.path.exists("schema.sql"):
            schema_path = "schema.sql"
        else:
            print(f"Error: schema file not found at '{schema_path}'")
            sys.exit(1)

    print(f"Initializing SQLite database at '{db_path}' using '{schema_path}'...")
    with open(schema_path, "r") as f:
        schema_sql = f.read()

    conn = sqlite3.connect(db_path)
    try:
        conn.executescript(schema_sql)
    except sqlite3.OperationalError as e:
        # Schema evolved, migrate columns gracefully
        print(f"Migrating schema for '{db_path}'...")
        cursor = conn.cursor()
        for stmt in schema_sql.split(";"):
            stmt = stmt.strip()
            if stmt:
                try:
                    cursor.execute(stmt)
                except sqlite3.OperationalError:
                    pass
    conn.commit()
    conn.close()
    print("Database initialized successfully.")

if __name__ == "__main__":
    db_file = sys.argv[1] if len(sys.argv) > 1 else "music_engine.db"
    init_db(db_file)

