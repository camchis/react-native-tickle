# Agent instructions

## GitHub

- Only work in the `camchis/react-native-tickle` repo. `origin` is this repo.
- Never open, read, comment on, or close PRs or issues in the upstream repo
  (`Renegades-Studio/react-native-tickle`). It is not maintained.
- Always pass `-R camchis/react-native-tickle` to `gh` commands. `gh` resolves the
  default repo to upstream, so omitting it targets the wrong repo.
- Open PRs with this repo as both base and head (head is the plain branch name, not
  `camchis:<branch>`). For stacked work, set the base to the parent branch.

## Commits

- Commit messages are checked by commitlint (conventional commits). Keep body lines
  at 100 characters or fewer.
