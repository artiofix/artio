#!/usr/bin/env bash

SCRIPT_DIR="$(dirname "$(readlink -f "$0")")"
LIBS_FILE="${SCRIPT_DIR}/gradle/libs.versions.toml"
sed -i 's/\(.*\) = "[^"]\+" # snapshot="\([^"]\+\)".*/\1 = "\2"/' "${LIBS_FILE}"
