# RULES — How We Work On This Project

These are standing rules for how code and explanations get delivered in this project.
Follow them every session, not just when reminded.

## 0. No auto mode — ask first, small steps
- Claude never works in auto mode. **Ask for the user's go-ahead before doing anything**
  (creating/editing files, running commands, git operations, installing anything).
- Work in **small steps**: propose one step, wait for approval, do it, report, then propose the next.
- Never chain multiple steps or "finish the whole segment" on one approval.

## 1. Code delivery pace
- Deliver code in **small batches**: 2–3 files at a time by default.
- If the files are small (a DTO, an enum, a small interface), up to **4 files** at a time is fine.
- Never dump an entire feature's files in one go. Stop after a batch and wait before continuing,
  unless explicitly told to keep going.

## 1a. Testing checkpoints (reminder rule)
- After each meaningful unit of logic (a service method, a repository query, a utility
  like the request-hash function) is delivered, **explicitly say it's time for a unit test**
  before moving to the next piece — don't wait to be asked.
- After a full vertical slice is wired end-to-end (e.g. a controller + service + repository
  for one feature, like Product CRUD or Checkout), **explicitly say it's time for an
  integration test** before moving to the next segment.
- Unit tests: JUnit 5 + Mockito, mock out repositories/external clients, test service logic
  in isolation.
- Integration tests: `@SpringBootTest` (+ Testcontainers for Postgres/Redis where relevant),
  test the real flow through the layers.
- These reminders are not optional flavor text — treat them as a required step in the
  delivery pace, same weight as stating the file path and rationale.

## 2. Every file must come with
- **Which file** and its **exact directory path** (feature-based package, see `ARCHITECTURE.md`)
  — e.g. `src/main/java/com/app/payment/idempotency/IdempotencyService.java`
- **Why this file exists** — a brief (2–4 sentence) explanation of its role and why it's
  structured the way it is. Not a lecture — just enough to justify the design choice.
- **Self-contained instructions** — when a step requires files or code from an earlier
  message, restate them in full rather than saying "the file from before" or "as given
  earlier." Every instruction should be actionable on its own, without needing to scroll
  back through the conversation.

## 3. Engineering standards to enforce in every batch
- **SOLID** principles — call out explicitly if a class is taking on more than one responsibility.
- **DRY** — shared behavior goes into a `BaseEntity` / base classes / shared utils, not copy-pasted.
- **Lombok** — use it to cut boilerplate (`@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor`, etc.)
  wherever it doesn't hide logic that matters for readability.
- **N+1 query awareness** — flag any relationship mapping or query that risks N+1, and default
  to fetch joins / `@EntityGraph` / explicit DTO projections over lazy-loading traps.
- **Flyway** — every schema change ships as a new versioned migration file, never a manual
  schema edit. Migration filenames and locations always stated explicitly.
- **Feature-based package structure** — packages are organized by feature/domain
  (`product/`, `cart/`, `payment/`, `idempotency/`), not by technical layer
  (`controllers/`, `services/`, `repositories/`) — see `ARCHITECTURE.md`.

## 4. Auth handling during Phase 1–4
- No real JWT/Spring Security yet. Use the faked identity header (`X-User-Id`) consistently.
- Every entity that will eventually be user-scoped (`Cart`, `Order`, `Payment`) gets a
  `user_id` column from day one, even though nothing enforces it yet.

## 5. Documentation upkeep
- After each meaningful decision or change, it gets logged in `MEMORY.md` — not left implicit.
- `TASK.md` is updated as tasks are completed or re-scoped — it should always reflect current
  reality, not the original plan if something changed.
- `PLAN.md` is the only place phase-level direction lives; don't re-litigate phase scope in
  other docs.

## 6. Git workflow (industry-style)
- Repo: **ZapMart** (GitHub, private). `main` is protected — requires a PR before merging
  (no "require approvals," since it's solo work).
- **Branch granularity**: one branch per **phase**, not per segment, starting with Phase 1
  (`feature/phase-1`) — all of Phase 1's segments (product, cart, order, payment) land as
  commits on this one branch, merged as a single PR once the whole phase is done. This
  supersedes the earlier per-segment-branch plan.
- Check for conflicts before merge (rebase or merge `main` into the branch first); resolve
  before opening/merging the PR, don't merge with unresolved conflicts.
- Commit messages describe the change, not the file — e.g. `feat(cart): add cart item quantity update`,
  not `update CartService.java`.
- After merge, delete the feature branch. Keep `main` history linear where reasonable.

## 7. Quality over quantity
- Prefer one feature (the idempotency layer) built excellently — race conditions handled,
  reclaim logic correct, tested under load — over multiple shallow features.
- When a shortcut is taken to move faster, it gets flagged explicitly as a shortcut and
  logged in `MEMORY.md`, so it doesn't silently become "the design."
