#!/usr/bin/env python3
"""Dangerous helper: remove the database and start fresh."""
import os
import database as db

if os.path.exists(db.DB_NAME):
    os.remove(db.DB_NAME)
    print("Removed", db.DB_NAME)

db.init_db()
print("Fresh database initialised.")
print("Run import_staff.py again if you want the staff list back.")
