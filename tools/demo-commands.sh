#!/usr/bin/env bash
# Replayed by asciinema to produce docs/demo.gif.
# Run from: tools/demo/  (the demo.yml workflow sets working-directory there)

GREEN='\033[0;32m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

step() { echo -e "${DIM}# $*${RESET}"; sleep 0.5; }
cmd()  {
    echo -e "${GREEN}\$${RESET} ${BOLD}$*${RESET}"
    sleep 0.4
    eval "$*"
    echo ""
    sleep 1.0
}

clear
echo -e "${BOLD}VibeTags${RESET} — annotate once, all AI platforms stay in sync"
echo ""
sleep 0.8

step "1. A class annotated with three guardrails"
cmd "cat src/main/java/com/example/PaymentService.java"
sleep 0.3

step "2. Platform files are empty placeholders before compile"
cmd "wc -c CLAUDE.md .cursorrules AGENTS.md"
sleep 0.3

step "3. mvn compile — VibeTags runs as part of javac"
cmd "mvn compile -q"
sleep 0.3

step "4. CLAUDE.md now contains generated XML guardrails"
cmd "sed -n '/VIBETAGS-START/,/VIBETAGS-END/p' CLAUDE.md"
sleep 0.3

step "5. .cursorrules has the same rules in Cursor's format"
cmd "sed -n '/VIBETAGS-START/,/VIBETAGS-END/p' .cursorrules"

echo -e "${GREEN}✓ 3 platform files updated — all from a single mvn compile${RESET}"
sleep 2
