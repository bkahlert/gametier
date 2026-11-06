# Agent instructions

- Evolve this file automatically when durable project guidance changes; keep it concise and remove stale or redundant instructions.
- Express invariants and feature behavior as runnable tests rather than documentation. Document only core features.
- Organize sources by responsibility (`game`, `files`, `settings`, `ui`) beneath the existing source roots; mirror packages in tests. Keep feature fixtures with their tests and reusable support in `testsupport.filesystem` or `testsupport.workflow`.

## Testing

- Cover every observable behavior and error path in the same change; only explicitly requested drafts or prototypes are exempt. If testing is impractical, report why and the manual check performed.
- Test at the lowest effective level with minimal fixtures; use integration tests for integration behavior. Avoid sleeps, polling and incidental I/O; never sacrifice coverage or mock the subject. Reuse the existing test stack.
- Reuse `scripts/TestVolumes.psm1` and `shared/test@jvm/testsupport/filesystem/TestVolumes.kt` for NTFS/ReFS integration fixtures and their filesystem matrix.
- Name test classes after their subject (`FooTest` or `FooIntegrationTest`); organize aspects and states as nested contexts using the test framework's idioms, with concise, nonredundant names and outcome-focused claims. Prefer `on` for triggers.
- Capture the action's immediate result before asserting; use expressive Kotest matchers and group multiple assertions on the result. Keep transformations in the assertion phase.
- Put tests before helpers; extract bulky fixtures. Avoid comments and KDoc unless they explain exceptional, otherwise inexpressible knowledge.
- With MockK value-class arguments, read the underlying value and rewrap it rather than using `firstArg` with the value class.

## Git

- Never commit to `main` or `master`. Start each change on its own branch and merge via a pull request; delete the branch after merging.
- Do not include AI attribution in commit messages, including AI coauthor trailers, generated-by lines, or assistant signatures. Preserve human attribution.
- Name branches `<type>/<TICKET-ID>-<slug>`, omitting the ticket ID when none exists. Use a commit type below and a slug of two to five lowercase, hyphenated words.
- Use Conventional Commits 1.0.0: `<type>(<scope>): <description>`. Types: `feat`, `fix`, `perf`, `revert`, `refactor`, `docs`, `test`, `build`, `ci`, `style`, `chore`. Choose by user-visible effect; `feat` is minor, `fix`, `perf`, and `revert` are patch, and breaking changes are major. Other types do not appear in changelogs.
- Use a reusable lowercase noun for the scope; omit it only for cross-cutting changes. Use an imperative, lowercase description without a final period; limit the header to 72 characters.
- When needed, add a blank-line-separated body explaining what and why, wrapped at 72 characters. Put issue references and `BREAKING CHANGE: <what breaks, how to migrate>` in footers after a blank line. Use `!` only if the release tooling supports it.
- Make each commit one logical change; separate reformatting into a `style` commit. For reverts, use `revert: <original header>` and body `This reverts commit <sha>.`
- Write squash-merge PR titles as commit headers. Ensure required footers reach the squash commit, via the PR description when configured or the merge dialog otherwise.
