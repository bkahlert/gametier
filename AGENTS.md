# Agent instructions

- Evolve this file automatically when durable project guidance changes; keep it concise and remove stale or redundant instructions.
- Express invariants and feature behavior as runnable tests rather than documentation. Document only core features.

## Git

- Never commit to `main` or `master`. Start each change on its own branch and merge via a pull request; delete the branch after merging.
- Name branches `<type>/<TICKET-ID>-<slug>`, omitting the ticket ID when none exists. Use a commit type below and a slug of two to five lowercase, hyphenated words.
- Use Conventional Commits 1.0.0: `<type>(<scope>): <description>`. Types: `feat`, `fix`, `perf`, `revert`, `refactor`, `docs`, `test`, `build`, `ci`, `style`, `chore`. Choose by user-visible effect; `feat` is minor, `fix`, `perf`, and `revert` are patch, and breaking changes are major. Other types do not appear in changelogs.
- Use a reusable lowercase noun for the scope; omit it only for cross-cutting changes. Use an imperative, lowercase description without a final period; limit the header to 72 characters.
- When needed, add a blank-line-separated body explaining what and why, wrapped at 72 characters. Put issue references and `BREAKING CHANGE: <what breaks, how to migrate>` in footers after a blank line. Use `!` only if the release tooling supports it.
- Make each commit one logical change; separate reformatting into a `style` commit. For reverts, use `revert: <original header>` and body `This reverts commit <sha>.`
- Write squash-merge PR titles as commit headers. Ensure required footers reach the squash commit, via the PR description when configured or the merge dialog otherwise.
