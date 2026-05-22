# AGENTS.md

Shared instructions for AI coding agents working in this repository.

## Scope
These rules apply to all agents unless a stricter, tool-specific file overrides them.

## Required Startup Checklist
1. Read `project_overview.md` for product and platform constraints.
2. Check available skills in `./.agents/skills`.
3. For the current task domain, read the matching `SKILL.md` file before implementation.

## Core Product Constraints
- Build an Android bicycle computer optimized for E-Ink displays.
- Use Mudita Mindful Design (MMD) components first; avoid bespoke UI when an MMD equivalent exists.
- Respect E-Ink constraints: high contrast, low refresh pressure, and no fluid animations.
- Keep the app Google-services independent (no Firebase, no Google Analytics, no GMS requirement).
- For location, use Android `LocationManager` (not Google fused provider).

## Skills Mapping
Skills are located in `./.agents/skills`:
- `android-compose-ui`
- `android-data-layer`
- `android-di-koin`
- `android-error-handling`
- `android-module-structure`
- `android-navigation`
- `android-presentation-mvi`
- `android-testing`

Each skill contains task-specific instructions in `SKILL.md`.

## Agent-Specific Notes
- GitHub Copilot: see `/.github/copilot-instructions.md` for direct Copilot instructions.
- Android Studio Gemini and other chat agents: if this file is not auto-loaded, include `AGENTS.md` and `project_overview.md` as explicit context in the prompt/session.

