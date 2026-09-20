import copy
import unittest
from standalone_token_study import decode_tree, display_metadata_projection, tree_packet


class StandaloneStudyTest(unittest.TestCase):
    def test_inventory_tree_packet_is_standalone_and_preserves_every_field(self):
        source={"v":6,"id":"q","data":{"inv":[{"loc":"equipment.weapon","name":"axe","level":None},
            {"loc":"backpack.0","name":"pouch","cursed":False},
            {"loc":"backpack.0.0","name":"seed","qty":2,"future":{"value":0}}]}}
        self.assertEqual(source,decode_tree(tree_packet(source)))

    def test_metadata_sensitivity_is_small_explicit_and_not_a_pruning_rule(self):
        source={"v":6,"id":"q","rev":"r1","data":{"phase":"player_ready","hero":{"hp":9},
            "ui":{"scene":"GameScene","display":{"language":"zh","fullscreen":False},
                  "nodes":[{"id":"c1","role":"text","text":"v3.3.8"},
                           {"id":"c2","role":"health_bar","cell":4,"health_pixels":6}]},
            "coverage":{"status":"observation_with_inspection","inspection_policy":"Use current ui controls and their action descriptors; details are read from displayed windows","details_via":{"items":"click"}},
            "persistence":{"saved":{"sid":"p1","src_id":"old"},"saves":[]},"cues":{"status":"not_rendered"}}}
        projected,paths=display_metadata_projection(source)
        self.assertEqual(["data.ui.display","data.coverage.inspection_policy"],paths)
        self.assertEqual(source["data"]["ui"]["nodes"],projected["data"]["ui"]["nodes"])
        self.assertEqual(source["data"]["persistence"],projected["data"]["persistence"])
        self.assertEqual(source["data"]["cues"],projected["data"]["cues"])
        self.assertIn("display",source["data"]["ui"])
        for change in ("modal","error","partial","future"):
            protected=copy.deepcopy(source)
            if change=="modal":protected["data"]["ui"]["modal"]=True
            elif change=="error":protected["err"]="EXECUTION_UNKNOWN"
            elif change=="partial":protected["pres"]={"st":"partial"}
            else:protected["data"]["ui"]["display"]["future"]=None
            result,removed=display_metadata_projection(protected)
            self.assertNotIn("data.ui.display",removed)
            self.assertEqual(protected["data"]["ui"]["display"],result["data"]["ui"]["display"])

    def test_original_tree_like_inventory_is_not_confused_with_generated_encoding(self):
        for source in ({"data":{"inv":{"equipment":{},"backpack":[{"name":"future"}]}}},
                       {"data":None}, {"err":"UNKNOWN"}):
            self.assertEqual(source,decode_tree(tree_packet(source)))


if __name__ == "__main__":
    unittest.main()
