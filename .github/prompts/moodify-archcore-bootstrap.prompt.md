---
name: moodify-archcore-bootstrap
description: Load Moodify's Archcore context, summarize the current repo truth layer, and tell the agent what to use before editing code.
---

# Moodify Archcore Bootstrap

Use this prompt at the start of a Moodify session when Archcore is available.

## Do this first

1. Inspect `.archcore/` and read the current ADRs, rules, specs, and plans.
2. Summarize the active product direction in 5 bullets or fewer.
3. Identify the most relevant rule or spec for the user's next request.
4. If the needed guidance is missing, say so explicitly and recommend creating it through Archcore.

## Output format

- Current Moodify direction
- Relevant Archcore documents
- Missing context, if any
- Best next action

## Notes

This prompt is intentionally small. Its job is to load context, not to recreate docs by hand.