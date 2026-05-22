# Gemini Context Guide

Use this file when working with Gemini in Android Studio (or any Gemini chat surface that does not auto-load repository policy files).

## Recommended Prompt Bootstrap
"Use `AGENTS.md`, `project_overview.md`, and relevant files from `./.agents/skills` as mandatory constraints for this task."

## Mandatory Context Files
- `AGENTS.md`
- `project_overview.md`
- Task-relevant `./.agents/skills/<skill>/SKILL.md`

## Core Constraints (Do Not Ignore)
- E-Ink-first UI decisions.
- Mudita Mindful Design components and patterns first.
- De-googled architecture: no Firebase, no Google Analytics, no hard GMS dependency.
- Use Android `LocationManager` for location/GPS.

