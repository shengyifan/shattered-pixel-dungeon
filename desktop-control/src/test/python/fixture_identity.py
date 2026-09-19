"""Post-execution identity assertions for explicitly isolated CLI 6 fixtures only.

This module never provides destinations, targets, or other gameplay choices. It
reads only the fixture's public handle registry to compare wire identities with
the same observation's test-only canonical checkpoints.
"""
from pathlib import Path
from contextlib import closing
import re
import sqlite3


_PREFIX = {"scope": "s", "revision": "r", "activity": "a", "session": "t", "save": "p", "map": "m"}


def fixture_canonical_identity(profile, kind, value):
    if kind not in _PREFIX or not isinstance(value, str) or not value:
        raise ValueError("An explicit fixture identity kind and nonempty value are required")
    if not re.fullmatch(_PREFIX[kind] + r"[1-9a-z][0-9a-z]*", value):
        return value  # Existing canonical unit-test fixtures require no registry access.
    build = Path(__file__).resolve().parents[4] / "desktop-control/build"
    profile = Path(profile).resolve()
    allowed = [build / name for name in ("fixtures", "smoke", "package-check")]
    if not any(profile != root and profile.is_relative_to(root) for root in allowed):
        raise ValueError("Identity assertions may read only disposable build fixture profiles")
    database = profile / "audit/public.sqlite3"
    if database.is_symlink() or (profile / "audit").is_symlink():
        raise ValueError("Fixture public identity registry cannot use symbolic links")
    if not database.is_file() or not database.resolve().is_relative_to(profile):
        raise ValueError("The fixture's public identity registry is unavailable")
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as connection:
        connection.execute("PRAGMA query_only=ON")
        version = connection.execute("SELECT value FROM metadata WHERE key='schema_version'").fetchone()
        if version != ("9",):
            raise ValueError("Fixture identity assertions require schema 9")
        row = connection.execute("SELECT canonical FROM public_handles WHERE kind=? AND handle=?", (kind, value)).fetchone()
    if row is None:
        raise AssertionError({"unknown_fixture_handle": value, "kind": kind})
    return row[0]


def fixture_identity_matches(profile, kind, canonical, wire):
    # Keep existing mock checkpoints usable without relaxing comparisons between
    # distinct real wire/canonical identities or reading any out-of-scope path.
    if not isinstance(canonical, str) or not canonical or not isinstance(wire, str) or not wire:
        return False
    return canonical == wire or canonical == fixture_canonical_identity(profile, kind, wire)


def fixture_context_matches(profile, checkpoint, state):
    kind = "activity" if str(state["state_version"]).startswith("a") and not str(state["state_version"]).startswith("activity:") else "revision"
    if str(checkpoint["state_version"]).startswith("activity:"):
        kind = "activity"
    return fixture_identity_matches(profile, kind, checkpoint["state_version"], state["state_version"]) and \
        fixture_identity_matches(profile, "scope", checkpoint["scope_id"], state["scope_id"])
