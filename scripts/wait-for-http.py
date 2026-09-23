#!/usr/bin/env python3
import sys
import time
from urllib.error import URLError
from urllib.request import urlopen

endpoints = sys.argv[1:]
if not endpoints:
    sys.exit("Supply at least one readiness endpoint.")
deadline = time.monotonic() + 90
for endpoint in endpoints:
    while time.monotonic() < deadline:
        try:
            with urlopen(endpoint, timeout=3) as response:
                if response.status == 200:
                    break
        except (URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(1)
    else:
        sys.exit(f"Readiness check timed out: {endpoint}")
