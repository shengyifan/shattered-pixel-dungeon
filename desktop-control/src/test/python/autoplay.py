#!/usr/bin/env python3
"""Interactive public-protocol client; never reads saves or audit databases.

Each gameplay action comes from an explicit operator intent and uses the current
player observation, displayed UI text, and advertised actions.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import sys
import time
import uuid

from machine_smoke import Client
from client_result import ActionResult, settle_action
import protocol9


ROOT = Path(__file__).resolve().parents[4]
PLAYTHROUGHS = ROOT / "desktop-control" / "build" / "playthroughs"
HERO_NAMES = {
    "warrior": ("warrior",), "mage": ("mage",), "rogue": ("rogue",),
    "huntress": ("huntress",), "duelist": ("duelist",), "cleric": ("cleric",),
}
FRIENDLY_NAMES = ("sad ghost", "shopkeeper", "wandmaker", "blacksmith",
                  "ambitious imp", "rat king", "ward", "lotus", "spirit hawk")
DAMAGE_DEBUFFS = ("burning", "poison", "bleed", "corros", "ooze", "caustic")


class Checkpoint(Exception):
    pass


def normalized(text):
    return str(text or "").strip().lower()


def contains(text, words):
    return any(word in normalized(text) for word in words)


def text_of(state):
    return "\n".join(str(n.get("text", n.get("label", "")))
                     for n in state.get("ui", {}).get("nodes", []))


def available(state, action):
    return any(a.get("op") == action for a in state.get("acts", []))


def activation(state, words, exact=False):
    choices = [a for a in state.get("acts", []) if a.get("op") == "click"]
    for word in words:
        for action in choices:
            label = normalized(action.get("label"))
            if (label == word if exact else word in label):
                return action
    return None


def creature_kind(entity):
    """Only recognized public interactions are safe during a continuous action."""
    if normalized(entity.get("name")) in FRIENDLY_NAMES and entity.get("context_action") == "interact":
        return "known_friendly"
    return "unknown"


def outcome_of(state):
    """Only explicit protocol settlement is authoritative; depth/HP/items never imply a win."""
    outcome = state.get("run_outcome")
    if not isinstance(outcome, dict):
        return None
    if isinstance(outcome.get("s"), str) and outcome["s"] and outcome["s"] == state.get("s") and outcome.get("result") in {"won", "lost"}:
        return outcome["result"]
    return None


class PublicClient(Client):
    def __init__(self, command, profile, public_log, maximum):
        self.public_log = public_log
        self.maximum = maximum
        self.action_count = 0
        self.last_state = None
        self.last_operation_response = None
        self.last_action_outcome = None
        self.cleanup = False
        super().__init__(command, profile)

    def log(self, kind, **fields):
        record = {"kind": kind, "at": datetime.now(timezone.utc).isoformat(), **fields}
        self.public_log.write(json.dumps(record, ensure_ascii=False) + "\n")
        self.public_log.flush()

    def request(self, op, args=None, request_id=None, scope=None, version=None):
        if op in protocol9.ACTION_OPS:
            if self.action_count >= self.maximum:
                raise Checkpoint("maximum_action_requests_reached")
            self.action_count += 1
        request_id = request_id or f"{self.prefix}.{self.counter + 1:d}"
        self.log("request", request=protocol9.request(op, args, request_id, self.scope if scope is None else scope, self.version if version is None else version),
                 action_number=self.action_count, cleanup=self.cleanup)
        response = super().request(op, args, request_id, scope, version)
        self.log("response", response=self.last_wire_response)
        if op in protocol9.ACTION_OPS:
            self.last_operation_response = response
        data = response.get("data")
        if protocol9.is_live_operation(op) and not response.get("err") and isinstance(data, dict) and "scene" in data:
            self.last_state = protocol9.frame_data(response)
            current_file = self.artifact_dir / "current-state-public.json"
            staged = self.artifact_dir / "current-state-public.json.tmp"
            staged.write_text(json.dumps(self.last_state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            staged.replace(current_file)
        return response

    def bounded_action(self, state, reason, action, **args):
        if not available(state, action):
            raise Checkpoint("missing_public_action:" + action)
        if not all(isinstance(state.get(key), str) and state[key] for key in ("s", "rev")):
            raise Checkpoint("missing_current_public_action_binding")
        self.log("decision", reason=reason, action=action, args=args,
                 observed_hero=state.get("hero"))
        result = self.request_action({"op": action, **args}, scope=state.get("s"), version=state.get("rev"))
        previous_hp = state.get("hero", {}).get("hp")
        activity_loss = 0
        started = time.monotonic()

        def observe_pending(pending):
            nonlocal previous_hp, activity_loss
            progress = self.state()
            current_hero = progress.get("hero") or {}
            if current_hero and previous_hp is not None:
                activity_loss = max(activity_loss, max(0, previous_hp - current_hero["hp"]))
                previous_hp = current_hero["hp"]
            self.log("activity_progress", rid=pending.request_id, phase=progress.get("phase"),
                     hero=current_hero, actions=progress.get("acts", []))
            threats = [e for e in progress.get("entities", []) if e.get("kind") == "character"
                       and creature_kind(e) != "known_friendly"]
            buffs = " ".join(b.get("name", "") for b in current_hero.get("buffs", []))
            unsafe = activity_loss > 0 or threats or contains(buffs, DAMAGE_DEBUFFS + ("starving",))
            overdue = time.monotonic() - started > 8
            if progress.get("phase") == "continuous_activity" and (unsafe or overdue):
                cancel = next((a for a in progress.get("acts", []) if a.get("op") == "cancel"
                               and a.get("rid") == pending.request_id), None)
                if not cancel:
                    raise Checkpoint("continuous_activity_has_no_advertised_cancel")
                self.log("decision", reason="cancel_continuous_action_after_public_risk_or_time_bound",
                         rid=pending.request_id, unsafe=bool(unsafe), overdue=overdue)
                cancellation = self.request_action({"op": "cancel", "rid": pending.request_id},
                                            scope=progress.get("s"), version=progress.get("rev"))
                if cancellation.get("err"):
                    raise Checkpoint("advertised_activity_cancel_rejected")

        settled = settle_action(self, result, action, timeout=40, poll_interval=.1, on_pending=observe_pending)
        self.last_action_outcome = settled
        if not settled.ok:
            raise Checkpoint("operation_settlement_failed:" + str(settled.failure or settled.outcome))
        if action == "quit":
            return settled.observation or state
        return settled.observation


def reach_game(client, hero_class, resume):
    selected = False
    revealed = set()
    for _ in range(35):
        state = client.state()
        scene = state.get("scene")
        if scene == "game":
            actual = state.get("hero", {}).get("class")
            if actual != hero_class:
                raise Checkpoint(f"requested_hero_unavailable:requested={hero_class},actual={actual}")
            return state
        if client.action_count >= client.maximum - 6:
            raise Checkpoint("menu_action_budget")
        if resume and scene == "start" and not state.get("ui", {}).get("modal"):
            slots = [a for a in state.get("acts", []) if a.get("op") == "click"
                     and normalized(a.get("label")).split("\n")[0] in HERO_NAMES[hero_class]
                     and "\n" in str(a.get("label", ""))]
            if len(slots) != 1:
                raise Checkpoint("resume_slot_missing_or_ambiguous_in_public_menu")
            client.bounded_action(state, "resume_existing_public_save_slot", "click", ctl=slots[0]["ctl"])
            continue
        if resume and scene == "hero_select":
            raise Checkpoint("resume_unexpected_new_game_flow")
        if scene == "hero_select" and not selected:
            choice = activation(state, HERO_NAMES[hero_class], exact=True)
            if choice:
                state = client.bounded_action(state, "select_requested_public_hero", "click", ctl=choice["ctl"])
                selected = True
                if contains(text_of(state), ("unlock",)) and not activation(state, ("enter",)):
                    raise Checkpoint("hero_locked_follow_normal_unlock_requirements")
                continue
        order = ("continue", "enter the dungeon", "enter", "play")
        order += ("continue",) if resume else ("new game", "start")
        choice = activation(state, order)
        if choice:
            client.bounded_action(state, "normal_menu_flow", "click", ctl=choice["ctl"])
        elif available(state, "reveal") and scene not in revealed:
            client.bounded_action(state, "reveal_scene_controls_through_advertised_action", "reveal")
            revealed.add(scene)
        else:
            raise Checkpoint("unrecognized_menu:" + str(scene))
    raise Checkpoint("menu_flow_limit")


def use_item(client, state, item, verb, reason):
    if client.maximum - client.action_count < 9:
        raise Checkpoint("reserve_shutdown_budget_before_item_choice")
    opened = open_inventory_item(client, state, item, reason + ":open")
    button = activation(opened, verb, exact=True)
    if button is None:
        client.bounded_action(opened, "close_unsupported_item_menu", "back")
        raise Checkpoint("item_action_missing:" + normalized(item.get("name")))
    return client.bounded_action(opened, reason, "click", ctl=button["ctl"])


def click_label(client, state, words, reason, exact=True):
    button = activation(state, words, exact=exact)
    if not button:
        raise Checkpoint("missing_visible_control:" + reason)
    return client.bounded_action(state, reason, "click", ctl=button["ctl"])


def open_inventory_item(client, state, item, reason):
    current = next((i for i in state.get("inv", [])
                    if i.get("loc") == item.get("loc") and i.get("name") == item.get("name")), None)
    if not current:
        raise Checkpoint("inventory_changed_before_open")
    return client.bounded_action(state, reason, "item", loc=current["loc"])


def checkpoint(client, attempt, state, reason):
    record = {"reason": reason, "official_outcome": outcome_of(state or {}),
              "action_requests": client.action_count, "s": client.scope,
              "rev": client.version, "state": state}
    (client.artifact_dir / "checkpoint-public.json").write_text(json.dumps(record, ensure_ascii=False, indent=2))
    if (state or {}).get("scene") == "game":
        (client.artifact_dir / "checkpoint-game-public.json").write_text(json.dumps(record, ensure_ascii=False, indent=2))
    client.log("checkpoint", **record)
    print(json.dumps({k: v for k, v in record.items() if k != "state"}, ensure_ascii=False), flush=True)


def operation_summary(operation, initial_response=None):
    """Summarize outcome evidence without printing a second complete observation."""
    derived = isinstance(operation, ActionResult)
    if not derived and initial_response is None:
        return None
    outcome = operation.outcome if derived else (initial_response or {})
    summary = {"derived_client_result": True,
               "id": operation.request_id if derived else outcome.get("id"),
               "ok": operation.ok if derived else not outcome.get("err"),
               "status": operation.status if derived else outcome.get("st")}
    error = outcome.get("err")
    if error is not None:
        summary["error"] = error
    presentation = outcome.get("pres")
    if presentation is not None:
        summary["presentation"] = presentation
    if derived and operation.receipt_response is not None:
        saves = outcome.get("save", [])
    else:
        saves = (outcome.get("data") or {}).get("persistence", {}).get("saves", [])
    if saves:
        summary["save"] = saves
    if derived and operation.failure is not None:
        failure = operation.failure
        summary["failure"] = ({"id": failure.get("id"), "error": failure.get("err")}
                              if isinstance(failure, dict) else str(failure))
    return summary


def interactive_public_session(client, attempt, state, reason="interactive_public_control", commands=None):
    """Keep the machine pipe alive while an operator resolves an actual public choice.

    This control stream accepts JSON intents, never game keyboard or pointer input.
    An unrecognized prompt or failed command remains connected and exposes its public UI.
    """
    commands = commands or sys.stdin
    def display():
        operation = getattr(client, "last_action_outcome", None)
        summary = operation_summary(operation, getattr(client, "last_operation_response", None))
        print(json.dumps({"interactive": True, "state": state,
                          "last_operation": summary,
                          "action_requests": client.action_count}, ensure_ascii=False), flush=True)
    display()
    input_ended_reported = False
    while True:
        line = commands.readline()
        if not line:
            # EOF cannot authorize discarding a consumed scroll or an unresolved game choice.
            if not input_ended_reported:
                client.log("interactive_control_waiting", reason="operator_input_ended_game_connection_preserved")
                print(json.dumps({"interactive_waiting": "operator_input_ended_game_connection_preserved"}), flush=True)
                input_ended_reported = True
            time.sleep(0.25)
            continue
        try:
            command = json.loads(line)
            operation = command.get("command")
            if operation == "state":
                state = client.state()
            elif operation == "act":
                args = dict(command.get("args", {}))
                state = client.bounded_action(state, command.get("reason", "reviewed_public_action"),
                                              command["op"], **args)
            elif operation == "use_item":
                name = normalized(command["name"])
                candidates = [i for i in state.get("inv", []) if normalized(i.get("name")) == name]
                if len(candidates) != 1:
                    raise Checkpoint("interactive_item_missing_or_ambiguous")
                state = use_item(client, state, candidates[0], tuple(command["verbs"]),
                                 command.get("reason", "reviewed_public_item_use"))
            elif operation == "choose":
                state = click_label(client, state, (normalized(command["label"]),),
                                    command.get("reason", "resolve_actual_public_choice"))
            elif operation == "checkpoint":
                checkpoint(client, attempt, state, command.get("reason", reason))
            elif operation == "stop":
                if state.get("phase") == "awaiting_input":
                    raise Checkpoint("resolve_current_visible_choice_before_stopping")
                return state, command.get("reason", "interactive_public_checkpoint")
            else:
                raise Checkpoint("unknown_interactive_command")
        except Exception as error:
            client.log("interactive_command_error", error_type=type(error).__name__, message=str(error))
            print(json.dumps({"interactive_command_error": type(error).__name__, "message": str(error),
                              "game_connection_preserved": True}), flush=True)
            state = client.last_state or state
        display()


def shutdown(client):
    client.cleanup = True
    state = client.state()
    if state.get("phase") == "awaiting_input":
        raise Checkpoint("resolve_current_visible_choice_before_shutdown")
    if state.get("phase") == "continuous_activity":
        cancel = next((a for a in state.get("acts", []) if a.get("op") == "cancel"), None)
        if not cancel:
            raise Checkpoint("cannot_close_running_activity_without_public_cancel")
        state = client.bounded_action(state, "cancel_activity_before_checkpoint_shutdown", "cancel",
                                      rid=cancel["rid"])
    if state.get("phase") == "player_ready" and available(state, "save"):
        state = client.bounded_action(state, "save_at_public_checkpoint", "save")
    if available(state, "quit"):
        client.bounded_action(state, "close_checkpoint_session", "quit")
        client.process.wait(timeout=20)
    else:
        raise Checkpoint("no_advertised_graceful_shutdown")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launcher", help="Already-built app launcher; no build is performed")
    parser.add_argument("--expect-cli-version", help="Require this public info cli_version before any game action")
    parser.add_argument("--classpath-file", type=Path, default=ROOT / "desktop-control/build/runtime-classpath.txt")
    parser.add_argument("--hero", choices=HERO_NAMES, default="warrior")
    parser.add_argument("--max-actions", type=int, default=100)
    parser.add_argument("--resume-profile", type=Path)
    parser.add_argument("--profile", type=Path, help="Existing playthrough profile; preserves normally earned character unlocks")
    parser.add_argument("--new-run", action="store_true", help="Explicitly choose New Game in --profile through the real menu")
    parser.add_argument("--interactive", action="store_true", help="Keep the public machine connection open for JSON control intents on stdin")
    args = parser.parse_args()
    if not args.interactive:
        parser.error("Use --interactive for public operator intents")
    if args.max_actions < 12:
        parser.error("--max-actions must reserve at least 12 actions for menu and safe shutdown")
    if args.profile and args.resume_profile:
        parser.error("Use either --profile or --resume-profile")
    if args.new_run and (not args.profile or args.resume_profile):
        parser.error("--new-run requires explicit --profile and must not use --resume-profile")
    PLAYTHROUGHS.mkdir(parents=True, exist_ok=True)
    existing_profile = args.resume_profile or args.profile
    resuming = bool(existing_profile and not args.new_run)
    if existing_profile:
        attempt = existing_profile.resolve()
        if not attempt.is_relative_to(PLAYTHROUGHS.resolve()) or not attempt.is_dir():
            parser.error("Resume only an existing non-fixture build/playthroughs profile")
    else:
        name = datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + args.hero + "-" + uuid.uuid4().hex[:8]
        attempt = PLAYTHROUGHS / name
        attempt.mkdir()
    artifact_dir = attempt.with_name(attempt.name + ".artifacts")
    artifact_dir.mkdir(parents=True, exist_ok=True)
    if args.launcher:
        command = [str(Path(args.launcher).resolve())]
    else:
        frozen_copy = artifact_dir / ("classpath-" + uuid.uuid4().hex[:8] + ".txt")
        shutil.copyfile(args.classpath_file, frozen_copy)
        classpath = frozen_copy.read_text().strip()
        project_entries = [Path(entry) for entry in classpath.split(":") if str(ROOT) in entry]
        if not project_entries or any("runtime-images" not in str(entry) for entry in project_entries):
            parser.error("Classpath project entries must already be frozen under runtime-images")
        command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
                   "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
                   "com.shatteredpixel.shatteredpixeldungeon.control.desktop.SpdctlLauncher"]
    log_name = "interactive-public-" + uuid.uuid4().hex[:8] + ".ndjson"
    client = None
    reason, state = "not_started", None
    with (artifact_dir / log_name).open("w", encoding="utf-8") as log:
        try:
            client = PublicClient(command, attempt, log, args.max_actions)
            client.log("attempt", profile=str(attempt), hero=args.hero, max_actions=args.max_actions,
                       resumed=resuming, explicit_new_run=args.new_run, mode="operator_intents_only",
                       capabilities=["public_state", "operator_reviewed_actions", "public_item_selection",
                                     "public_choice_selection", "explicit_checkpoint"],
                       requires_review=["vault_mechanics", "npc_quest_choices", "unknown_scroll_selectors", "boss_combat",
                                        "class_specific_advanced_abilities"])
            print(json.dumps({"attempt": str(attempt), "public_log": str(artifact_dir / log_name)}, ensure_ascii=False), flush=True)
            hello = client.request("info")
            if hello.get("err"):
                raise Checkpoint("protocol_start_failed")
            if hello.get("data", {}).get("game_version") != "4.0.2":
                raise Checkpoint("unsupported_game_version")
            if args.expect_cli_version and hello.get("data", {}).get("cli_version") != args.expect_cli_version:
                raise Checkpoint("unexpected_public_runtime_cli_version")
            state = reach_game(client, args.hero, resuming)
            state, reason = interactive_public_session(client, attempt, state)
        except Checkpoint as stopped:
            reason = str(stopped)
        except BaseException as failure:
            reason = "failure:" + type(failure).__name__ + ":" + str(failure)
            if client:
                client.log("failure", type=type(failure).__name__, message=str(failure))
        finally:
            if client:
                state = client.last_state or state
                if client.process.poll() is None and (state or {}).get("phase") == "awaiting_input":
                    client.log("awaiting_input_held_open", reason=reason)
                    state, reason = interactive_public_session(client, attempt, state, reason)
                checkpoint(client, attempt, state, reason)
                try:
                    if client.process.poll() is None:
                        shutdown(client)
                except BaseException as shutdown_error:
                    client.log("shutdown_incomplete", error=str(shutdown_error))
                    if client.process.poll() is None:
                        client.process.stdin.close()
                        try:
                            client.process.wait(timeout=15)
                        except Exception:
                            client.process.terminate()
                            client.process.wait(timeout=10)
                finally:
                    client.stderr.close()
    print(json.dumps({"attempt": str(attempt), "result": reason,
                      "official_outcome": outcome_of(state or {}),
                      "action_requests": client.action_count if client else 0}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
