#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
kotlinc app/src/main/java/com/calltune/app/OutgoingTuneGate.kt tests/OutgoingTuneGateTest.kt -d "$classes"
kotlin -cp "$classes" OutgoingTuneGateTestKt
