import copy
import unittest
import standalone_text_codec as codec


class StandaloneTextCodecTest(unittest.TestCase):
    def test_repeated_events_keep_multiplicity_order_and_identity(self):
        message = "You cannot target yourself because the path is blocked."
        frame = {"ui":{"nodes":[{"id":"c1","text":message}, {"id":"c2","text":message}]}}
        encoded = codec.encode(frame)
        self.assertEqual([message], encoded["strings"])
        self.assertEqual(frame, codec.decode(encoded))
        self.assertEqual(2, len(codec.decode(encoded)["ui"]["nodes"]))

    def test_literal_reference_like_strings_and_unicode_are_escaped(self):
        frame = {"values":["$0", "$$0", "$bad", "$٠", "雪🙂\nline", None, False, 0, 0.0]}
        self.assertEqual(frame, codec.decode(codec.encode(frame)))

    def test_opaque_originals_and_sources_are_never_rewritten(self):
        frame = {"raw":{"id":"$0"}, "reply":["$7"], "text_sources":{"text":"$0"},
                 "text":"A repeated ordinary long description", "label":"A repeated ordinary long description"}
        encoded = codec.encode(frame)
        self.assertEqual(frame["raw"], encoded["frame"]["raw"])
        self.assertEqual(frame["text_sources"], encoded["frame"]["text_sources"])
        self.assertEqual(frame, codec.decode(encoded))

    def test_each_packet_decodes_in_isolation_and_does_not_mutate_input(self):
        first = {"text":"first long description", "label":"first long description"}
        second = {"text":"second long description", "label":"second long description"}
        original = copy.deepcopy(first)
        encoded_first, encoded_second = codec.encode(first), codec.encode(second)
        self.assertEqual(second, codec.decode(encoded_second))
        self.assertEqual(first, codec.decode(encoded_first))
        self.assertEqual(original, first)

    def test_missing_and_invalid_references_fail_without_an_external_lookup(self):
        for reference in ("$0", "$-1", "$01", "$x"):
            with self.subTest(reference=reference), self.assertRaises(ValueError):
                codec.decode({"$codec":codec.FORMAT,"strings":[],"frame":{"text":reference}})

    def test_keys_and_unknown_structures_keep_their_literal_values(self):
        frame = {"$codec":"existing user value", "$0":{"future":[True, {"null":None}]}}
        self.assertEqual(frame, codec.decode(codec.encode(frame)))

    def test_late_and_historical_frames_are_opaque_even_when_their_text_repeats(self):
        past={"data":{"ui":{"nodes":[{"id":"c10","text":"$0"}]}}}
        frame={key:past for key in ("history","events","outcome","late_responses","original_response")}
        packet=codec.encode(frame)
        self.assertEqual(frame,packet["frame"])
        self.assertEqual(frame,codec.decode(packet))


if __name__ == "__main__":
    unittest.main()
