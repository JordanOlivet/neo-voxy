# Progress logs

One file per working day on this repository. **Mandatory**: no work session
ends without its log. Anyone (human or agent, on any machine, at any time) must
be able to read the latest log and know exactly what was done, what was
decided, what was verified and what comes next.

## Rules

1. **Start of session**: read the latest log (it is the resume point), then the
   relevant plan in `docs/` (e.g. `fabric-port-plan.md`), before touching code.
2. **End of session**: write or extend the day's log before the final commit,
   and commit it with the work it describes (same branch, same PR).
3. **One file per working day**: `YYYY-MM-DD.md`. A second session the same day
   extends that file; it never creates a new one. Older days are never
   rewritten, except to fix a factual error. If a later day invalidates
   something, say so in the new log.
4. If the session advanced a plan in `docs/`, tick the matching items there too
   and cite them by ID (P1.3, D2...).
5. Sessions without code (investigation, design discussion) still get a log
   when they produce a decision or a finding.
6. Write for the reader who has no context: what changed and **why**, which
   calls the user made, what was really tested versus only built.

## Structure

The body is organized by topic, not by a fixed form. Keep these anchors:

```markdown
# Progress — YYYY-MM-DD (<one-line summary of the day>)

<Two to four sentences: what the day was about and where it ended.>

## Commits

- <sha> <type> — <summary>

## <Topic> (<shas>)

<What changed and why. Mark user decisions as "(user)" or under a
"user calls" heading. Mark anything shipped but not yet tested in game as
"NOT yet play-tested".>

## Gotchas (hard-won)

<Traps that cost time and will bite again. Omit the section if none.>

## Validated

<What was actually checked: build, unit tests, solo, dedicated server, Iris,
Sodium 0.6 / 0.8, Fabric / NeoForge. Say explicitly what was NOT verified.>

## Next session

1. <Prioritized, actionable items; the first one is where to resume.>
```
