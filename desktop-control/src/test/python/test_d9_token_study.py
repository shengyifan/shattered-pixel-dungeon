import unittest
from d9_token_study import Delta, ItemCatalog, QuickslotCache, apply_patch, equal, inventory_rows, inventory_tree, patch, section_lines, table_decode, table_encode


class TokenStudyCodecsTest(unittest.TestCase):
    def test_rows_preserve_absence_null_false_zero_and_order(self):
        items=[{"loc":"bag.0","name":"wand","level":None,"cursed":False},
               {"name":"wand","loc":"bag.1","level":0,"extra":[]},
               {"loc":"bag.2","name":"wand"}]
        self.assertTrue(equal(items,table_decode(table_encode(items))))

    def test_patch_deletes_and_replaces_without_conflating_false_zero(self):
        old={"hero":{"hp":8},"list":[False,None],"gone":{"value":1}}
        new={"hero":{"hp":7},"list":[0,None],"new":False}
        for recursive in (False,True):
            self.assertTrue(equal(new,apply_patch(old,patch(old,new,recursive=recursive))))

    def test_inventory_tree_uses_only_current_contiguous_locators(self):
        frame={"id":"q","data":{"inv":[{"loc":"equipment.weapon","name":"axe"},
                  {"loc":"backpack.0","name":"pouch"},{"loc":"backpack.0.0","name":"seed","qty":2},
                  {"loc":"backpack.1","name":"food"}]}}
        tree=inventory_tree(frame)["data"]["inv"]
        self.assertEqual("axe",tree["equipment"]["weapon"]["name"])
        self.assertEqual([{ "name":"seed","qty":2}],tree["backpack"][0]["inside"])
        frame["data"]["inv"][-1]["loc"]="backpack.7"
        self.assertEqual(frame,inventory_tree(frame))

    def test_catalog_emits_changed_public_names_and_preserves_instance_fields(self):
        codec=ItemCatalog()
        def frame(name):
            return {"id":"q","s":"s2","data":{"phase":"player_ready","inv":[{"loc":"bag.0","name":name,"level":None,"cursed":False}]}}
        first=codec.encode(frame("crimson potion"))
        second=codec.encode(frame("potion of healing"))
        self.assertEqual(1,first["data"]["inv"][0]["template"])
        self.assertEqual(2,second["data"]["inv"][0]["template"])
        self.assertIn("level",second["data"]["inv"][0])
        self.assertFalse(second["data"]["inv"][0]["cursed"])
        self.assertNotIn("item_definitions",codec.encode(frame("potion of healing")))

    def test_delta_resets_scope_floor_and_refresh_and_leaves_receipts_independent(self):
        codec=Delta(True,3)
        frame={"id":"q","s":"s2","rev":"r1","data":{"phase":"player_ready","hero":{"depth":1,"hp":20},"padding":"x"*500}}
        self.assertTrue(codec.encode(frame)["cache"]["full"])
        self.assertIn("patch",codec.encode(frame))
        receipt={"id":"receipt","s":"s2","data":{"id":"q","st":"COMPLETED"}}
        self.assertEqual(receipt,codec.encode(receipt))
        codec.encode(frame)
        self.assertTrue(codec.encode(frame)["cache"]["full"])
        frame["data"]["hero"]["depth"]=2
        self.assertTrue(codec.encode(frame)["cache"]["full"])
        frame["s"]="s3"
        self.assertTrue(codec.encode(frame)["cache"]["full"])

    def test_readable_framing_preserves_arbitrary_keys_and_multiline_text(self):
        frame={"id":"q","data":{"a b":"line1\nline2","quoted\"key":{"null":None}}}
        self.assertTrue(section_lines(frame).startswith("@"))
        self.assertEqual(frame,inventory_rows(frame))

    def test_quickslot_cache_keeps_controls_and_refreshes_after_24_refs(self):
        codec=QuickslotCache()
        nodes=[{"id":"c1","role":"button","ops":[{"op":"click"}],"display":{"status":"0"}}]
        context=("s2",1,"m1")
        self.assertIn("value",codec.encode(nodes,context)["quickslots"])
        for _ in range(24):
            self.assertEqual({"quickslots":{"ref":1}},codec.encode(nodes,context))
        self.assertEqual(nodes,codec.encode(nodes,context)["quickslots"]["value"])
        nodes[0]["id"]="c2"
        self.assertEqual(2,codec.encode(nodes,context)["quickslots"]["def"])
        self.assertEqual(1,codec.encode(nodes,("s2",1,"m2"))["quickslots"]["def"])


if __name__ == "__main__":
    unittest.main()
