#!/usr/bin/env python3
"""Discover conformance suites from template files."""

from __future__ import annotations

import json
from pathlib import Path


MODULE_DIR = Path(__file__).resolve().parents[1]
TEMPLATE_PREFIX = "template_"
TEMPLATE_SUFFIX = ".yaml"
SOURCE_ROOT = Path("src") / "main" / "java"


def discover_suites(module_dir: Path = MODULE_DIR) -> tuple[str, ...]:
    """Return sorted suites with matching templates and non-empty handler packages."""
    templates = sorted(module_dir.glob(f"{TEMPLATE_PREFIX}*{TEMPLATE_SUFFIX}"))
    if not templates:
        raise SystemExit(f"No {TEMPLATE_PREFIX}<suite>{TEMPLATE_SUFFIX} files found")

    suites: list[str] = []
    for template in templates:
        suite = template.name[len(TEMPLATE_PREFIX) : -len(TEMPLATE_SUFFIX)]
        if not suite:
            raise SystemExit(f"Invalid conformance template name: {template.name}")

        handlers_dir = module_dir / SOURCE_ROOT / suite
        if not handlers_dir.is_dir():
            raise SystemExit(
                f"Template {template.name} has no matching handler package: {handlers_dir}"
            )

        if not list(handlers_dir.glob("*.java")):
            raise SystemExit(f"No handler classes found for suite {suite}: {handlers_dir}")

        suites.append(suite)

    return tuple(suites)


def main() -> None:
    print(json.dumps(discover_suites(), separators=(",", ":")))


if __name__ == "__main__":
    main()
