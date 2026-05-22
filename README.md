# InkRide

InkRide is an Android bicycle computer app designed for E-Ink displays.

## AI Agent Usage

This repository is configured to support multiple coding agents with a shared instruction model.

### Primary Instruction Files
- `project_overview.md` - product goals and hard platform constraints.
- `AGENTS.md` - cross-agent baseline and startup checklist.
- `.github/copilot-instructions.md` - GitHub Copilot-specific guidance.
- `GEMINI.md` - Gemini prompt bootstrap guidance (for sessions that do not auto-load repo files).

### Skills Catalog
- Location: `./.agents/skills`
- Each skill contains a `SKILL.md` file with domain-specific implementation guidance.

### Recommended Agent Startup Flow
1. Read `project_overview.md`.
2. Read `AGENTS.md`.
3. Identify the task domain and open the matching file from `./.agents/skills/*/SKILL.md`.
4. Apply constraints consistently (E-Ink-first UI, MMD components first, de-googled stack).

### Non-Negotiable Constraints
- E-Ink readability and low-refresh UI behavior are mandatory.
- Prefer Mudita Mindful Design (MMD) components and patterns.
- No Firebase, no Google Analytics, no hard dependency on Google Play Services.
- Use Android `LocationManager` for location/GPS.

