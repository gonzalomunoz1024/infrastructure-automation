"""Serve the example provider: ``run`` (the default) serves it until the process is stopped,
``publish [file]`` writes the provider file, ``submit`` sends it to the platform."""

from __future__ import annotations

import asyncio
import logging
import sys

from iaf_provider import run_worker, submit, write_file

from zones import Zones, default_zones


def main(argv: list[str]) -> int:
    p = Zones(default_zones()).provider()
    cmd = argv[1] if len(argv) > 1 else "run"
    if cmd == "run":
        logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
        asyncio.run(run_worker(p))
    elif cmd == "publish":
        file = argv[2] if len(argv) > 2 else f"providers/{p.owner}/{p.name}.yaml"
        print("Wrote", write_file(p, file))
    elif cmd == "submit":
        s = submit(p)
        print(f"Change request {s.change_request} ({s.status}) opened for {p.name} {p.version}")
    else:
        print("usage: main.py [run | publish [file] | submit]", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
