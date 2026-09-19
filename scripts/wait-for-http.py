#!/usr/bin/env python3
import sys
import time
from urllib.error import URLError
from urllib.request import urlopen

endpoint = sys.argv[1]
deadline = time.monotonic() + 90
while time.monotonic() < deadline:
    try:
        with urlopen(endpoint, timeout=3) as response:
            if response.status == 200:
                sys.exit(0)
    except (URLError, TimeoutError):
        pass
    time.sleep(1)
sys.exit(f"Readiness check timed out: {endpoint}")
