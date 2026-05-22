# Copilot Instructions

## First Steps For Every Task
1. Read `project_overview.md`.
2. Inspect `./.agents/skills`.
3. Open and apply the matching skill `SKILL.md` for the task domain.

## Project Rules
- This project is an E-Ink bicycle computer app.
- Prioritize Mudita Mindful Design (MMD) components and patterns.
- Avoid unnecessary UI animation; optimize for E-Ink readability and refresh behavior.
- Keep implementation de-googled:
  - no Google Play Services dependency required,
  - no Firebase/Google Analytics,
  - use Android `LocationManager` for GPS/location.

## Implementation Expectations
- Keep architecture modular and consistent with existing project patterns.
- Prefer minimal, focused changes over broad refactors.
- Add/adjust tests when behavior changes.
- If multiple skills apply, combine their guidance and call out trade-offs.

## Sources Of Truth
- Product and constraints: `project_overview.md`
- Cross-agent baseline: `AGENTS.md`
- Domain guidance: `./.agents/skills/*/SKILL.md`

