# Moodify Archcore Workflow

Moodify uses Archcore as the source of truth for product decisions, UX rules, and implementation notes.

## How to work in this repo

1. Check `.archcore/` first for relevant decisions, rules, specs, and plans.
2. If the required guidance is missing, do not invent it in code comments or random markdown files.
3. Create or update Archcore content through the Archcore workflow, not by manually scattering documentation across the repo.
4. Keep all mood, audio, social, and privacy changes aligned with the emotion-first product direction.
5. Prefer small, local edits that preserve the listening experience and the privacy-first model.

## Product principles

- Emotion-first over feature-first.
- Privacy-first and account-free.
- Gentle transitions over abrupt UI changes.
- Social features should remain low-pressure and anonymous.

## When making changes

- Before changing UI, playback, or discovery behavior, identify the matching Archcore rule or spec.
- If a change introduces a new behavior, record the decision in Archcore before broadening the implementation.
- If the repo is missing the needed Archcore docs, bootstrap them with Archcore rather than writing standalone docs by hand.
- If you need a quick entry point, use the `moodify-archcore-bootstrap` prompt to load the current repo context before editing.