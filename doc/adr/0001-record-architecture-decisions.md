# 1. Record architecture decisions

Date: 2026-04-16

## Status

Accepted.

## Context

chachaml is built for the long term, with quality and maintainability as
explicit goals (see `CONTRIBUTING.md`). Design decisions accumulate
quickly in a young codebase, and the rationale fades faster than the
code. Without a written record, future contributors are forced to either
re-derive the reasoning (slow) or reverse decisions without
understanding why they were made (dangerous).

## Decision

We record significant architectural decisions in `doc/adr/` using
[Michael Nygard's
format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions):

- One Markdown file per decision, numbered sequentially:
  `NNNN-short-title.md`.
- Each ADR contains: Status, Context, Decision, Consequences.
- ADRs are append-only. A superseded ADR remains in place; the
  superseding ADR references it, and the old one's Status is updated
  to "Superseded by ADR-NNNN".

## When to write an ADR

- Picking a new core dependency.
- Changing a protocol shape or other public API contract.
- Choosing between approaches when reasonable people would disagree.
- Any decision a future contributor might be tempted to reverse without
  the original context.

## Consequences

- Onboarding is faster: new contributors can read decisions in order.
- Reviewers have a defensible bar for "should this be an ADR?".
- The directory grows over time; that's fine — old ADRs remain useful
  as historical context even after they're superseded.
