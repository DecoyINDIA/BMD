#!/usr/bin/env python3
"""Print the actual native source revision for Android BuildConfig diagnostics."""
from native_common import source_info
print(source_info()["revision"])
