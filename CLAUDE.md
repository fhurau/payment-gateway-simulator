# payment-gateway-simulator

**`docs/DESIGN.md` is the single source of truth for this project** — architecture,
service boundaries, schemas, event contracts, API contracts, ID semantics, money
rules, guardrails, and the phase-by-phase Definition of Done all live there.

Phase prompts in this repo are intentionally short: *"Implement Phase N per
DESIGN.md §X, §Y. Satisfy the Definition of Done in §16. Obey the guardrails in
§13."* Read the referenced sections before writing any code — do not
re-derive schemas, contracts, or IDs from scratch, and do not guess at
conventions already fixed in the doc.

If a task can't be done without inventing something DESIGN.md doesn't
specify, that's a gap in the doc, not a license to improvise — update
`docs/DESIGN.md` first, then implement against it.

Key sections to know exist (don't duplicate their content here — read them):
- §0 Tech stack lock — do not swap build tool, JDK, or broker
- §2 Service boundaries — database-per-service, never cross-service DB access
- §3 Identifiers — four distinct IDs, never conflate them
- §4 Event envelope + money rules — JPY zero-decimal is load-bearing
- §9 Flyway schemas per service
- §13 Guardrails — things to never do (no AWS/K8s runtime deps, no dual-write, etc.)
- §16 Execution plan + per-phase Definition of Done
