# Playback settings

The Android app finds each song's audio stream itself, by asking YouTube "clients" (VISIONOS, ANDROID_VR and others) one after another. YouTube changes which clients work every few months. These settings let you change the order and the time limits without shipping a new APK.

## Endpoint

`GET /v1/playback` needs no sign-in and is the same for everyone. It is cached for an hour.

```json
{
  "clients": ["VISIONOS", "ANDROID_VR", "ANDROID_MUSIC"],
  "hedgeAfterMs": 2500,
  "perClientMs": 5000,
  "budgetMs": 10000
}
```

Every field is optional. With no configuration the server answers `{}` and the app keeps its built-in defaults (the values above, and its full client order).

| Field | Meaning | App's limits |
|---|---|---|
| `clients` | The order to ask clients in. Names the app doesn't know are ignored, so you can only reorder or drop clients, not add new ones. | At least one known name, or the field is ignored |
| `hedgeAfterMs` | When the client being asked hasn't answered after this long, the next one starts alongside it (never more than two at once). | 300 – 5,000 |
| `perClientMs` | The most one client may take, including the check that YouTube's CDN accepts the stream. | 2,000 – 20,000 |
| `budgetMs` | The most finding one song's stream may take. After that the app skips the song and says so. | 5,000 – 30,000 |

## Changing it

Set the `PLAYBACK_CONFIG` variable on the `moodify-social` Worker (dashboard › Settings › Variables and Secrets) to the JSON above, then redeploy. For example, if VISIONOS stops working:

```json
{"clients": ["ANDROID_VR", "ANDROID_MUSIC", "IOS", "VISIONOS"]}
```

The app reads the settings once each time it starts, keeps the last good copy, and uses it until a newer one arrives. Within a session it also asks the client that last worked first.
