"""The qualification gate must distinguish a referenced class from its definition."""
from __future__ import annotations

import struct
import unittest

from check_release_harness import (DELEGATE_CONSTRUCTOR, DELEGATE_RUNNER, REQUIRED_DEFINITIONS,
                                   check_class_sets, declared_test_libraries, dex_class_info)


def tiny_dex(*, define_trace: bool) -> bytes:
    # A minimal structural DEX with a method_id referring to Trace. Adding its
    # class_def is a separate action, as in the real failed release harness.
    strings = [b"Ljava/lang/Object;", b"Landroidx/tracing/Trace;"]
    count = 2 if define_trace else 1
    string_ids = 112
    type_ids = string_ids + 8
    method_ids = type_ids + 8
    class_defs = method_ids + 8
    data = bytearray(class_defs + 32 * count)
    data[:8] = b"dex\n039\0"
    for offset, value in ((36, 112), (40, 0x12345678), (56, 2), (60, string_ids),
                          (64, 2), (68, type_ids), (88, 1), (92, method_ids),
                          (96, count), (100, class_defs)):
        struct.pack_into("<I", data, offset, value)
    for index, value in enumerate(strings):
        struct.pack_into("<I", data, string_ids + index * 4, len(data))
        struct.pack_into("<I", data, type_ids + index * 4, index)
        data.extend(bytes([len(value)]) + value + b"\0")
    struct.pack_into("<H", data, method_ids, 1)
    for index in range(count):
        struct.pack_into("<I", data, class_defs + index * 32, index)
        struct.pack_into("<I", data, class_defs + index * 32 + 8, 0xFFFFFFFF if index == 0 else 0)
    struct.pack_into("<I", data, 32, len(data))
    return bytes(data)


def constructor_dex(*, defined: bool, public: bool = True) -> bytes:
    """A method reference and, optionally, a real encoded public constructor."""
    strings = [b"V", b"Ljava/lang/Class;", DELEGATE_RUNNER.encode(), b"Ljava/lang/Object;",
               b"<init>", b"VL"]
    string_ids, type_ids, proto_ids, method_ids, class_defs = 112, 136, 152, 164, 172
    parameters, code_offset = 204, 212
    data = bytearray(230)
    data[:8] = b"dex\n039\0"
    for offset, value in ((36, 112), (40, 0x12345678), (56, 6), (60, string_ids), (64, 4),
                          (68, type_ids), (72, 1), (76, proto_ids), (88, 1), (92, method_ids),
                          (96, 1), (100, class_defs)):
        struct.pack_into("<I", data, offset, value)
    for index, value in enumerate(strings):
        struct.pack_into("<I", data, string_ids + index * 4, len(data))
        data.extend(bytes([len(value)]) + value + b"\0")
    for index in range(4):
        struct.pack_into("<I", data, type_ids + index * 4, index)
    struct.pack_into("<III", data, proto_ids, 5, 0, parameters)
    struct.pack_into("<HHI", data, method_ids, 2, 0, 4)
    struct.pack_into("<I", data, parameters, 1)
    struct.pack_into("<H", data, parameters + 4, 1)
    struct.pack_into("<IIIIIIII", data, class_defs, 2, 1, 3, 0, 0xFFFFFFFF, 0, len(data), 0)
    struct.pack_into("<HHHHIIH", data, code_offset, 2, 2, 0, 0, 0, 1, 0x0E)

    def uleb(value: int) -> bytes:
        result = bytearray()
        while value >= 128:
            result.append((value & 127) | 128)
            value >>= 7
        return bytes(result + bytes([value]))

    data.extend(bytes([0, 0, int(defined), 0]))
    if defined:
        data.extend(b"\0" + uleb(0x10001 if public else 0x10002) + uleb(code_offset))
    struct.pack_into("<I", data, 32, len(data))
    return bytes(data)


class ReleaseHarnessGateTest(unittest.TestCase):
    def test_reference_is_not_a_definition(self):
        definitions, owners = dex_class_info(tiny_dex(define_trace=False))
        self.assertIn("Landroidx/tracing/Trace;", owners)
        self.assertNotIn("Landroidx/tracing/Trace;", definitions)
        self.assertIn("Landroidx/tracing/Trace;", dex_class_info(tiny_dex(define_trace=True))[0])

    def test_target_cannot_supply_a_missing_runner_class(self):
        with self.assertRaisesRegex(ValueError, "unresolvedMemberOrSupertypeOwners"):
            check_class_sets({"Lkotlin/io/CloseableKt;"}, set(REQUIRED_DEFINITIONS),
                             {"Lkotlin/io/CloseableKt;"}, set())

    def test_shared_definition_would_shadow_the_target(self):
        with self.assertRaisesRegex(ValueError, "overlappingDefinitions"):
            check_class_sets({"Landroidx/tracing/Trace;"}, set(REQUIRED_DEFINITIONS), set(), set())

    def test_sdk_definitions_resolve_framework_owners(self):
        result = check_class_sets(set(), set(REQUIRED_DEFINITIONS),
                                  {"Ljava/lang/Object;", "Landroidx/tracing/Trace;"}, {"Ljava/lang/Object;"})
        self.assertEqual([], result["unresolvedMemberOrSupertypeOwners"])

    def test_missing_required_definition_and_corrupt_tables_fail(self):
        with self.assertRaisesRegex(ValueError, "missingRequiredDefinitions"):
            check_class_sets(set(), set(), set(), set())
        data = bytearray(tiny_dex(define_trace=True))
        struct.pack_into("<I", data, 100, len(data) - 4)
        with self.assertRaisesRegex(ValueError, "table exceeds"):
            dex_class_info(bytes(data))

    def test_optional_sdk_classes_require_real_required_manifest_entries(self):
        complete = "uses-library:'android.test.base'\nuses-library:'android.test.runner'\n"
        self.assertEqual({"android.test.base", "android.test.runner"}, declared_test_libraries(complete))
        for incomplete in ("", complete.replace("uses-library:", "uses-library-not-required:", 1)):
            with self.assertRaisesRegex(ValueError, "manifest must require"):
                declared_test_libraries(incomplete)

    def test_constructor_requires_a_public_encoded_definition(self):
        for defined, public, expected in ((True, True, {DELEGATE_CONSTRUCTOR}),
                                          (False, True, set()), (True, False, set())):
            with self.subTest(defined=defined, public=public):
                constructors: set[str] = set()
                dex_class_info(constructor_dex(defined=defined, public=public),
                               public_constructors=constructors)
                self.assertEqual(expected, constructors)


if __name__ == "__main__":
    unittest.main()
