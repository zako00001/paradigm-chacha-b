# Engineering Principles

## Priority
Deliver the smallest complete, working solution.
Correctness and existing requirements come first.
Simplicity is the default, not an afterthought.

## Scope Control
- Make the smallest coherent change that solves the task.
- Reuse existing code, patterns, and platform capabilities.
- Do not refactor unrelated code or invent new requirements.
- Do not add dependencies, abstractions, configuration,
  files, or infrastructure without a concrete need.
- Prefer one clear execution path over multiple layers.

## Defensive Programming
- Validate inputs at real trust boundaries, not everywhere.
- Do not add speculative edge-case handling.
- Do not add compatibility shims, legacy adapters,
  retries, fallbacks, or alternate execution paths
  without an actual requirement.
- Do not silently swallow exceptions or mask failures.
- Let programming errors fail visibly.
- Preserve necessary security, authorization,
  and data-integrity checks.

## Verification
- Run the smallest relevant checks first.
- Do not automatically run exhaustive test suites.
- Add tests for changed behavior and demonstrated risks,
  not hypothetical scenarios.
- Do not create test frameworks or audit machinery
  merely to verify a small change.

## Completion
- Check whether each new abstraction, branch, helper,
  and defensive check is actually necessary.
- Remove unnecessary complexity before finishing.
- Report the files changed, checks run, and limitations.
- Once acceptance criteria are met, STOP.
- Defer optional improvements to a future version.
