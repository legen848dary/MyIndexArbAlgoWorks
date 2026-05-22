# Agent Rules — ALWAYS ENFORCED

## 🚨 HARD RULES — NEVER VIOLATE

1. **NEVER run `git commit`** — the human commits. You can stage files (`git add`), write commit messages into a file, suggest commits, but never execute `git commit` or `git reset`.

2. **NEVER write to production** — no `ssh elthost` commands that modify files, no `docker exec` on production containers that changes state, no `scp` to the production server. Read-only commands (logs, ps, status checks) are OK.

## ⚡ BEFORE EVERY ACTION

Pause and ask: "Does this action involve git commit, git reset, or writing to production?" If yes, STOP. Tell the human what you want to do and let them decide.

## Git Operations — ALLOWED

- `git status`, `git log`, `git diff`, `git show` — read-only
- `git add` — stage files for human to commit
- `git branch`, `git stash` — if explicitly requested

## Git Operations — FORBIDDEN

- `git commit`, `git reset`, `git push`, `git rebase`, `git merge`
- `git commit --amend`, `git reset --hard`, `git push --force`

## Production Server — ALLOWED (read-only)

- `ssh elthost "docker ps"` — status checks
- `ssh elthost "docker logs ..."` — log inspection
- `ssh elthost "curl ..."` — health checks
- `ssh elthost "ls ..."` — file listing

## Production Server — FORBIDDEN

- `ssh elthost "docker exec ..."` — any container modification
- `ssh elthost "rm ..."` — file deletion
- `ssh elthost "chown ..."` — permission changes
- `ssh elthost "docker restart ..."` — container restarts
- `ssh elthost "docker compose ..."` — service changes
- `scp` to/from production
- Any command that writes, deletes, or modifies production state

## Local Operations — ALLOWED

- All local development: compile, test, edit, write files
- Local Docker: build, deploy, restart, clean
- `./rebuildAndDeployLocally.sh` — local only
