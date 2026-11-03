# Ideation Notes: audit-doc

## Candidate Approaches

- **Approach A (Symlinked canonical doc)**: Write comprehensive audit report to `audit_date.md` and create a relative symbolic link `audit_2026-11-03.md -> audit_date.md`. Track both in git. Canonical documentation stays in `audit_date.md` with zero duplication.
- **Approach B (Dual identical copies)**: Write `audit_date.md` and copy it to `audit_2026-11-03.md`. Maximizes portability if checked out on filesystems that don't support symlinks, but introduces file duplication risk if updated later.
- **Approach C (Minimal placeholder notes)**: Write minimal high-level notes without exact code snippets or command proof. Violates prompt requirements for full code audit and verification proof.

## Selected Approach
Approach A (with symlink `audit_2026-11-03.md -> audit_date.md` or hardcopy fallback if needed; symlink is standard POSIX and tracked cleanly by git).

## Pitfalls & Edge Cases
- **Verification Proof Completeness**: Must capture actual command outputs (`cargo test` 5/5 passed, `build.sh` 1.4M APK created) rather than synthetic summaries.
- **Scope Alignment**: Ensure all sub-bullets across backend (query routing, CLI delimiter, `@` prefix sanitization, `TempFilesGuard`, `MAX_BODY_SIZE`), Android (`setAllowFileAccess`, `setMixedContentMode`, `FLAG_DEBUGGABLE`, `onDestroy()` teardown, `allowBackup=false`), and frontend (`textContent`, `replace(() => block)`, `AbortSignal.timeout(30000)`, `Array.isArray()` checks, viewport meta) are documented with exact rationales.
- **Ledger & Commit Discipline**: Keep notes under 800 words, synchronize `tasks.json` and `plan.md`, verify before committing.
