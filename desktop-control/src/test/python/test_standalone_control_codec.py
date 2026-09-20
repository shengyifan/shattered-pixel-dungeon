import unittest
import standalone_control_codec as codec


class StandaloneControlCodecTest(unittest.TestCase):
    def test_expanded_and_packed_controls_restore_complete_original_handles(self):
        frame={"v":6,"id":"t1.10","s":"s2","rev":"r12","data":{
            "ui":{"node_shapes":[["id","role","parent","text"]],
                  "nodes":[[0,"c10","button","cz","c10"],{"id":"c11","role":"button","ops":[{"op":"click","ctl":"c11"}]}]},
            "acts":[{"op":"click","ctl":"c10"}]}}
        packet=codec.encode(frame)
        self.assertEqual(36,packet["frame"]["data"]["ui"]["nodes"][0][1])
        self.assertEqual("c10",packet["frame"]["data"]["ui"]["nodes"][0][4])
        self.assertEqual("t1.10",packet["frame"]["id"])
        self.assertEqual(frame,codec.decode(packet))

    def test_literal_numbers_null_false_and_reserved_objects_are_not_coerced(self):
        for value in (0,12,None,False,0.0,{codec.LITERAL:7},"c0001","c-1","C12"):
            frame={"data":{"ui":{"nodes":[{"id":value,"role":"button","parent":value}]},"acts":[{"ctl":value}]}}
            self.assertEqual(frame,codec.decode(codec.encode(frame)))

    def test_opaque_history_sources_and_large_handles_remain_unchanged(self):
        frame={"raw":{"ctl":"c10"},"reply":{"ui":{"nodes":[{"id":"c10"}]}},
               "text_sources":{"ctl":{"value":"c10"}},"data":{"acts":[{"ctl":"czzzzzzzzzzzzzzzzzzzz"}]}}
        self.assertEqual(frame,codec.decode(codec.encode(frame)))
        self.assertEqual(frame["raw"],codec.encode(frame)["frame"]["raw"])

    def test_independent_packets_do_not_define_new_ids(self):
        first={"data":{"acts":[{"ctl":"c10"}]}}
        second={"data":{"acts":[{"ctl":"c1"}]}}
        a,b=codec.encode(first),codec.encode(second)
        self.assertEqual(second,codec.decode(b))
        self.assertEqual(first,codec.decode(a))

    def test_late_and_outcome_controls_are_not_rewritten(self):
        past={"data":{"ui":{"nodes":[{"id":"c10","parent":"c11"}]},"acts":[{"ctl":"c10"}]}}
        frame={key:past for key in ("outcome","late_responses","original_response","source")}
        packet=codec.encode(frame)
        self.assertEqual(frame,packet["frame"])
        self.assertEqual(frame,codec.decode(packet))


if __name__ == "__main__":
    unittest.main()
