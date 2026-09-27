# Phase prompts for Claude Code

One file per phase; see `docs/WORKFLOW.md` for the loop. Start a fresh Claude Code
conversation per phase:

    Read CLAUDE.md, then execute @docs/prompts/phase-NN-name.md.
    Start in plan mode. Create branch phase-N-name from main.

Prompts are added one phase ahead, after the previous phase is reviewed, so each prompt
reflects what was actually built.
