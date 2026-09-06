# Moodify intelligence boundary

## Principle

YouTube Music is a provider of content, metadata, navigation, and provider-side personalization. Moodify is the layer that remembers the listener’s relationship with music across moments, contexts, scenes, and choices.

## Provider-owned intelligence

The current InnerTube library can provide or navigate to:

- public search results and search filters;
- public track information, playability, formats, and artwork;
- artist and album pages;
- public playlist contents and continuation;
- provider-related tracks;
- provider up-next/automix queues;
- Music Home and Explore shelves;
- provider suggestions;
- lyrics when the music surface exposes them;
- account-scoped library, history, subscriptions, and playlist mutations when authenticated;
- provider recommendations that are conditioned by provider context and account state.

These results should be stored as attributed observations. A provider label or placement is not automatically a Moodify fact about a track or listener.

## Moodify-owned intelligence

Moodify must build:

- canonical cross-boundary track identity;
- Track DNA with observable, derived, and evidenced inferred layers;
- local listening event semantics and bounded persistence;
- discovery memory: what was shown, searched, played, skipped, repeated, or ignored;
- user taste signals from behavior rather than only genre/artist counts;
- temporal and session context;
- a discovery graph with provenance;
- editorial Scene and Universe relationships;
- candidate generation and deterministic ranking;
- explanations for why a candidate or scene is relevant;
- personalization that can combine long-term, recent, session, and contextual preference;
- privacy and retention boundaries appropriate for a local-first product.

## Why Moodify needs to exist if YouTube Music already recommends music

The provider answers: “What might this platform show or play next?”

Moodify should answer: “What has this person returned to across their own moments, what did they reject, what belongs to this scene, and why does this next track fit their current listening thread?”

The distinction is not a larger catalog. It is ownership of memory and meaning:

| Question | Provider answer | Moodify answer |
|---|---|---|
| What is this track? | Provider metadata and navigation | Track DNA with explicit unknowns and evidence |
| What should play next? | Provider contextual recommendation | Explainable candidate ranking from provider graph + listener response |
| What does this person enjoy? | Provider account profile, if available | Local behavioral taste model with recency, repetition, completion, and context |
| Why this sequence? | Provider queue/automix | Sequence evidence plus scene/editorial intent |
| What does this song mean here? | Provider surface placement | Moodify Scene/Universe relationship, if curated or evidenced |
| Can it follow the listener across sources? | Usually provider-specific | Moodify canonical identity and event history |

## Boundary rules

1. Do not copy provider recommendations directly into Moodify taste.
2. Do not treat a search impression as a play or preference.
3. Do not treat a provider relationship as audio similarity without evidence.
4. Do not infer emotion, culture, language, era, or personality from title text alone.
5. Keep provider account data opt-in and separate from anonymous local listening history.
6. Keep expiring playback URLs and continuation tokens out of durable intelligence records.
7. Store provenance and observation time for provider graph edges.
8. Let an unknown value remain unknown.

## Ownership map

```text
Provider
  -> retrieval, public metadata, provider graph, provider context
Provider Adapter
  -> normalization, retry, source-specific parsing, provenance
Canonical Track Model
  -> stable Moodify boundary model
Track DNA
  -> observable facts + deterministic derivations + attributed optional evidence
Listening Events
  -> listener behavior and session evidence
Discovery Graph
  -> dated relationships and exposure context
Taste Model
  -> explainable listener affinities
Candidate Generator / Ranker
  -> Moodify selection policy
Personal State (local)
  -> coherent view of evidence; no Moodify account
Home Model
  -> section data contract for a future editorial UI
Experience
  -> scenes, sequences, and explanations
```

## Current foundation status

The Provider Adapter and Discovery Graph Store are now implemented on the server. They retain only canonical nodes, relationship edges, provenance, and bounded dated snapshots; raw InnerTube payloads, continuation handles, and expiring media URLs remain outside durable intelligence records. Listening events accept optional discovery context. Personal State V1 and Home Model V1 assemble local evidence for a future UI; they do not authenticate users or copy InnerTube Home into taste.

## Security and privacy boundary

Provider cookies/OAuth credentials, signed media URLs, and account history must never be placed in Track DNA or shared with the UI as generic metadata. Local Moodify events should remain bounded and export/delete-capable. Any server persistence needs an explicit account, consent, retention, and deletion design.
