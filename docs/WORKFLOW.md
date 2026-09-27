# How we build: phase by phase with Claude Code

Every phase follows the same loop. Nothing reaches `main` without a PR and green CI.

## The loop (per phase)

1. **Sync main**
   ```bash
   git checkout main && git pull
   ```
2. **Open Claude Code in VS Code** (fresh conversation per phase) and send:
   ```
   Read CLAUDE.md, then execute @docs/prompts/phase-NN-name.md.
   Start in plan mode. Create branch phase-N-name from main.
   ```
3. **Review the plan** before approving: scope matches the prompt, no extra features,
   tests listed, ADRs respected. Ask for changes if needed.
4. **Let it build task by task.** Review diffs in VS Code as they appear. Each task = one commit.
5. **Verify yourself**, don't trust "done":
   ```bash
   ./scripts/verify-stack.sh && mvn -q verify
   ```
6. **Push + PR** (Claude Code does this at the end of the phase via `gh pr create`).
7. **CI green → review on GitHub → merge.**
8. **Milestone reached?** Tag it:
   ```bash
   git checkout main && git pull
   git tag -a m1-first-replay -m "Milestone M1: first replay" && git push --tags
   ```
9. Update the roadmap status in `README.md` (next phase's PR can include it).

## One-time GitHub setup

- `gh auth login`
- Repository settings → Branches → protect `main`: require a PR and the `ci` checks to pass.

## Rules of thumb

- One phase per branch, one conversation per phase (`/clear` between phases).
- If Claude Code wants to change an ADR or add a feature outside the phase, stop and decide deliberately.
- Keep PRs reviewable: if a phase grows past ~1,500 changed lines of reviewable logic, split it.
  Count logic only: exclude tests, generated code and entity accessors. Never split code from
  its tests. For a large PR, add a review guide to the body (line counts by category, files to
  review carefully vs. skim) and keep commits 1:1 with tasks so it can be reviewed per commit.
