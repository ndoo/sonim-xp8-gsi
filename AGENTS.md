# Agent guide

This file covers changing this repository. To operate a user's phone (install,
unlock, restore), follow [docs/agents.md](docs/agents.md) instead.

The repository builds an Android 16 GSI and the scripts that install it on the
Sonim XP8. It is public.

## Requirements

- Working cellular data is the top acceptance criterion: SIM registration and
  mobile data, plus VoLTE/IMS for calls. Every test report states the SIM
  state, the registration result and the mobile-data result. A milestone is
  not done without them.
- Updates must work without root. Design and test the no-root path first;
  `su` is a fallback and Magisk is never required.

## Change workflow

- Never commit to `main`. Put every change on a branch and open a PR; the
  maintainer lands it with squash-and-merge.
- Never rewrite history or force-push `main`. `--force-with-lease` on a
  feature branch is fine.
- Use a git worktree for a new branch when the main checkout has work in
  progress.
- Put an independent fix in its own, earlier PR. After it lands, rebase the
  dependent PR onto `main`.
- Write the PR title as the squash subject: imperative, near 50 characters.
- `main` requires a PR whose CI jobs `lint` and `components` pass on an
  up-to-date branch. Only squash merges are allowed.
- When you squash-merge, keep GitHub's default commit title and message:
  `gh pr merge N --squash` without `--subject` or `--body`.
- Before pushing, run `reuse lint` (add a `REUSE.toml` annotation for each
  new file) and `git ls-files '*.sh' | xargs shellcheck -x -P SCRIPTDIR`.

## Commits

- Subject near 50 characters, blank line, body wrapped at 72 columns.
- Write the subject and every sentence that names a change in the imperative
  mood ("Add X", "Move Y to Z"), not past or present tense.
- Body order: the change, then the motivation, then what is deliberately left
  untouched and why. The last two are optional for small changes. One short
  paragraph each.
- Write the message from `git diff --cached`, not from memory of the plan.
- Describe the code or design change only. No test status, no "pending", no
  process notes.
- Trailers: `Assisted-by: <model name> <noreply@anthropic.com>`, then
  `Signed-off-by` last, built from a fresh `git config user.name` and
  `user.email`. Never use `Co-Authored-By`. Never combine `-s` with
  `--trailer`; pass every trailer with `--trailer`.

## Pull requests

- Use these headings in this order and drop any that do not apply:
  `### Problem` (with `fixes #N`), `### Solution`, `### Regression safety`,
  and `### Build-verified` or `### Bench-tested`.
- Write each paragraph as one unwrapped line.
- In the verification section, state in the past tense what was run and what
  was observed. Say "build-verified only" when nothing ran on a phone, with
  no reason.
- Do not add an AI attribution line.
- Show the user the draft and get approval before posting a PR, comment,
  issue or release note.

## Writing style

- No advertising in commit or PR text: no promotion of a tool or model, no
  "Generated with" lines, no marketing phrasing.
- No editorializing or hedging adjectives ("cleanly", "simply", "robust",
  "elegant", "just works"). If a word can go without changing the meaning,
  remove it. Prefer a plain statement of the mechanism to a coined intensifier
  ("always false", not "hard-false").
- Comment code only for a non-obvious reason or a trap for the next editor.
  One line. Do not narrate what the code does or describe how an external
  system behaves; that belongs in the commit message.
- Keep docs in end-user voice.
- Do not estimate durations in messages ("several minutes").
- A script or CI step that runs longer than about 30 s prints live progress
  every 5 s: `xz -v` with `kill -USR1` when stderr is not a terminal,
  `curl --progress-bar` on a terminal, `zstd` without `-q` for large files.

## Working with the phone

- Pin every adb and fastboot command to the unit's serial. Before an EDL
  write, confirm exactly one 9008 device is present and that its serial
  matches the unit. If two phones are attached, stop and ask.
- When the next step needs fastboot, EDL or power-off, ask the user to put the
  phone in that mode. Do not reset it into an unknown state (for example
  `edl reset` into a boot loop) and leave it.
- Back up partitions before any write. Store backups compressed with `zstd`
  and checksum the raw images. Never commit a backup or its contents.
- Follow the hard rules in [docs/agents.md](docs/agents.md) for every EDL and
  fastboot write.

## Releases

- Push an annotated tag `a16-YYYYMMDD` (or `a16-YYYYMMDD.N`) to run
  `.github/workflows/release.yml`. A `workflow_dispatch` run builds the
  artifacts without publishing.
- Name the release after its tag (`a16-YYYYMMDD` or `a16-YYYYMMDD.N`); the
  workflow does this. GitHub's release list truncates longer titles. The
  `[GSI][16] ...` title line belongs at the top of the notes body.
- Release notes use the same layout and headings in every release: title,
  disclaimer, changes, about, needs testing (only while testers are wanted),
  features, working, known issues, requirements, installation, updating,
  downloads, credits, sources, info block.
- Name the section "Changes", not "Changelog", and put it directly after the
  disclaimer. List only what changed since the previous release: the release's
  own entry, not the entries of earlier releases. GitHub's generated
  "What's Changed" at the end stays as generated.
  Installation and update steps are specific to the release; derive the update
  steps from what changed since the previous tag (docs only,
  `flash.sh --only system`, PC update with reassembly, or OTA). Append GitHub's
  generated notes (`gh api -X POST repos/ndoo/sonim-xp8-gsi/releases/generate-notes`)
  verbatim, then the "Built from <sha>." and "Inputs: ..." lines.
- Put the "Needs testing" section directly after "About", never between
  installation and updating. Make it a numbered list built from the PRs since
  the previous tag: item 1 is the change most likely to regress or the least
  tested, then descending risk, with cellular data and features not yet tested
  on any phone last. List a working feature (VoLTE calls, SMS, ...) only when
  a change since the previous tag can affect it. End each item with the PR
  numbers it covers, e.g. "(#16)". Say what testers should report (firmware,
  SIM state, result).
- Draft the notes and get approval before `gh release edit`.
- Builds are reproducible. To compare two builds, compare the raw
  `system.img` hash in the release's `SHA256SUMS`; do not download the
  artifacts.
- Downloading Actions artifacts to a laptop is slow. Fetch them on a
  well-connected machine and copy only small results back.
