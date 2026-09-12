"""Test-only identification setup preserves the unknown Upgrade and uses its public selection."""
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import upgrade_preview_smoke as upgrade


class UpgradeIdentifyPreparationTest(unittest.TestCase):
    def state(self, known=False, auxiliary=True):
        inventory = [{"locator": "equipment.armor", "name": "cloth armor", "level_known": True, "level": 0},
                     {"locator": "backpack.1", "name": "scroll of upgrade" if known else "scroll of NAUDIZ",
                      "type_known": known, "quantity": 1}]
        if auxiliary:
            inventory.append({"locator": "backpack.0", "name": "scroll of identify", "type_known": True, "quantity": 1})
        return {"state_version": "v1", "observation": {"hero": {"level": 1}, "inventory": inventory},
                "actions": [{"action": "ui.back"}]}

    def test_original_read_selects_the_unique_public_unknown_scroll_then_verifies_consumption(self):
        client = Mock()
        selected, identified = self.state(), self.state(known=True, auxiliary=False)
        with patch.object(upgrade, "open_item") as opened, patch.object(upgrade, "choose", side_effect=[selected, identified]) as choice, patch.object(upgrade, "verify") as verify:
            result = upgrade.identify_with_identify_scroll(client, 2)
        predicate = opened.call_args.args[1]
        self.assertTrue(predicate(selected["observation"]["inventory"][-1]))
        self.assertFalse(predicate(selected["observation"]["inventory"][1]))
        self.assertEqual(["READ", "scroll of NAUDIZ"], [call.args[1] for call in choice.call_args_list])
        self.assertEqual(1, verify.call_args_list[0].kwargs["identify_count"])
        self.assertEqual((client, identified, 0, 1, True, 2), verify.call_args_list[1].args)
        self.assertTrue(result["identify_scroll_consumed_once"])
        self.assertTrue(result["upgrade_scroll_preserved_before_upgrade"])

    def test_public_auxiliary_scroll_never_counts_as_an_extra_upgrade_scroll(self):
        client = Mock(profile=Path("unused"))
        observed = {"armor_level": 0, "seal_level": 0, "scroll_count": 1, "scroll_known": False, "upgrades_used": 0, "interface_size": 2}
        with patch.object(upgrade, "checkpoint", return_value={"item_window": {"upgrade": observed}}):
            upgrade.verify(client, self.state(), 0, 1, False, 2, identify_count=1)
            with self.assertRaises(AssertionError):
                upgrade.verify(client, self.state(), 0, 1, False, 2)
            upgrade.verify(client, self.state(auxiliary=False), 0, 1, False, 2)


if __name__ == "__main__":
    unittest.main()
