# Changelog

## 0.5.0 — unreleased

### Added

- **`BubbleGate` — optional per-speaker gate for host plugins** (`…proxchat.api`). A host
  plugin registers a `BubbleGate` with Bukkit's `ServicesManager` and ProxChat asks it, per
  message, whether that player may still turn speech into a bubble — the seam a moderation
  mute needs, without touching the plugin-wide mode. No provider registered means everyone
  may speak, and a provider that throws is treated as absent for that message and logged
  once: a missing or broken moderation plugin can never silence a server. The gate is asked
  before the rate limiter (a gated message costs the player no rate slot) and asked again
  immediately before the bubble renders, so a mute landing mid-message still drops it. A
  denial is silent, writes nothing to the conversation log, and never revives the chat
  broadcast; bubbles already in flight fade on their own lifetime.
- `/proxchat status` now reports `gate=registered`, `gate=none` or `gate=error`, so an
  operator can tell a wired gate from an absent one (both look identical while nobody is
  muted).

### Changed

- **Default `radius-blocks` is now `32.0` (was `24.0`)** — if you can see a player, you can
  read them. `view-range` moves with it to `0.6`, because the two are a **pair**: the radius
  decides who *receives* a bubble, the view range decides whether their client *draws* it
  (`view-range × 64` blocks).
- **The pairing is now enforced.** A `view-range` that culls closer than 1.2× the radius is
  raised to the paired minimum with a console warning. The failure it prevents is invisible
  from the server — the client receives the bubble and quietly declines to draw it, so players
  standing well inside the radius see nothing and edge bubbles flicker. A radius too large for
  the belt to cover at all (past ~107 blocks) caps at `view-range: 2.0` and says plainly that
  admitted viewers beyond that distance may see nothing. Widening the belt yourself costs no
  bandwidth and is never second-guessed: only admitted players ever receive the entity.
- Default `height-above-head` is now `0.3` (was `1.2`) — tuned for servers that hide
  nametags, where the taller offset leaves a visible gap between bubble and player.
  Deployed configs are never regenerated, so existing servers keep their value.

### Fixed

- The documented `height-above-head` default and the code's fallback had drifted apart in
  0.4.0: `config.yml` shipped `0.3`, but a deployed config with that key *deleted* fell back
  to `1.2`. The code default now matches the documentation, and a test pins every shipped
  default — including that the radius and view-range defaults satisfy their own pairing rule —
  so the two cannot drift again.

## 0.4.0 — unreleased

### Added

- **Optional server-side conversation log** (`conversation-log` section, **off by
  default**). One JSONL line per message: sanitized text as rendered, the players who
  could see the bubble at send time, world and position, ISO-8601 + epoch timestamps.
  With `log-admits: true` (default), players who come into range of a live bubble are
  recorded as admit events. Daily files under `plugins/ProxChat/conversation/`,
  flushed per line; `retention-days: 0` (default) keeps files forever, a positive
  value prunes older daily files by their filename date. Writing runs on a dedicated
  background thread and can never block, delay, or drop a bubble — under backpressure
  the log drops its own lines (counted, rate-limited warning) rather than touch chat.
  All keys apply live via `/proxchat reload`.

### Fixed

- Stopping the server with players online no longer logs a spurious
  `Error occurred while disabling ProxChat` — cleanup no longer tries to schedule
  tasks from a disabled plugin (bubbles were always non-persistent; the error was
  noise, not a leak).
- Per-viewer visibility updates now pre-check that the display entity is valid and
  region-reachable before scheduling, closing a rare cross-region error during
  stalled world transfers (the failure was already fail-safe: worst case a hide was
  skipped and the bubble faded naturally).
- The legacy formatting lead-in `§` (U+00A7) is now stripped by message sanitization —
  some client render paths honor §-codes even in raw display text, so it is never
  legitimate message content.

## 0.3.0 — 2026-07-17

- Initial public release: proximity speech bubbles for Folia/Paper with
  server-authoritative radius admission, three modes (`ON`/`OFF`/`SUPPRESSED`),
  a `ProxChatService` API for host plugins, hardened input sanitization,
  per-player rate limiting, live config reload, and crash-safe mode persistence.
