"""Fail-closed checks for the legacy image's measured kernel-page-size fallback."""
import subprocess
import unittest

from observe_device_runtime import may_use_smaps, positive_integer, smaps_page_size


class DeviceRuntimeObservationTest(unittest.TestCase):
    def test_consistent_kernel_observations_are_measured_in_bytes(self):
        self.assertEqual(smaps_page_size("KernelPageSize:        4 kB\nRss: 8 kB\nKernelPageSize:        4 kB\n"), 4096)
        self.assertEqual(smaps_page_size("KernelPageSize:       16 kB\n"), 16384)

    def test_missing_malformed_and_ambiguous_observations_fail(self):
        for value in ("", "Rss: 4 kB", "KernelPageSize: unknown", "KernelPageSize: 0 kB", "KernelPageSize: 3 kB", "KernelPageSize: 4 kB\nKernelPageSize: 16 kB"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                smaps_page_size(value)

    def test_only_missing_legacy_getconf_can_fall_back(self):
        missing = subprocess.CompletedProcess([], 127, "", "/system/bin/sh: getconf: not found\r\n")
        self.assertTrue(may_use_smaps(26, missing))
        self.assertFalse(may_use_smaps(36, missing))
        self.assertFalse(may_use_smaps(26, subprocess.CompletedProcess([], 1, "", "device offline")))
        self.assertFalse(may_use_smaps(26, subprocess.CompletedProcess([], 127, "", "adb: not found")))
        self.assertFalse(may_use_smaps(26, subprocess.CompletedProcess([], 1, "", "getconf: not found")))

    def test_getconf_requires_one_positive_decimal_value(self):
        self.assertEqual(positive_integer("16384\r\n", "page size"), 16384)
        for value in ("", "0", "4096 16384", "4096\n4096", "16 kB"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                positive_integer(value, "page size")


if __name__ == "__main__":
    unittest.main()
