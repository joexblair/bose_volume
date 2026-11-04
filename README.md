# Bose Volume

An Android home-screen widget that controls the volume of a **Bose Music** smart speaker or
soundbar (Home Speaker 300/500, Portable Smart Speaker, Smart Soundbar 300/600/700/900/Ultra,
Music Amplifier, …) directly over your Wi‑Fi. You don't have to open the Bose app.

```
╭────────────────────────────────────────────╮
│  (−)      Living Room        (+)   (🔊)    │
│              34                            │
│          ━━━━━━━━──────────                │
╰────────────────────────────────────────────╯
```

* **−/+** step the volume (default 3, configurable)
* **speaker icon** toggles mute
* **number** refreshes, **speaker name** opens settings

## Install

Grab the APK from the **Build** GitHub Actions run (artifact `bose-volume-debug-apk`), or build it
yourself with Android Studio / `./gradlew :app:assembleDebug`. Then:

1. Open **Bose Volume** and **Sign in** with the Bose account your speaker is set up with.
2. **Find speakers on Wi‑Fi** and pick yours, or type its IP address.
3. Long-press the home screen → Widgets → **Bose Volume**.

## How it works (the reverse-engineered protocol)

The protocol details come from the open-source reverse-engineering of the Bose Music app in
[`pybose`](https://pypi.org/project/pybose/), which was done by sniffing the official app's
traffic. They are re-implemented in Kotlin in [`core/`](core/src/main/kotlin/com/joexblair/bosevolume/core).

**1. Discovery.** Speakers advertise `_bose-passport._tcp` over mDNS. The TXT record `GUID` is
the device id.

**2. Account token.** The speaker only obeys requests that carry a Bose *control token* (a JWT)
issued to an account that owns it. Getting one takes two steps:

| Step | Request |
|---|---|
| Sign-in | Azure AD B2C OAuth2 code + PKCE at `myboseid.bose.com/boseprodb2c.onmicrosoft.com`, policy `B2C_1A_MBI_SUSI`, the Bose Music app's client id `e284648d-…` and redirect `bosemusic://auth/callback` |
| Exchange | `POST https://id.api.bose.io/id-jwt-core/idps/aad/B2C_1A_MBI_SUSI/token` with `{"grant_type":"id_token","id_token":…}` and header `X-ApiKey` |

The app shows Bose's real sign-in page in a WebView and catches the `bosemusic://` redirect, so
your password never passes through this app's code. It stores the B2C refresh token, and when
the control token expires it gets a new one without asking you.

**3. Local control.** `wss://<speaker-ip>:8082/?product=…` with WebSocket sub-protocol `eco2`
(the speaker uses a self-signed certificate). Messages look like this:

```json
{"header": {"device": "<GUID>", "method": "PUT", "msgtype": "REQUEST", "reqID": 2,
            "resource": "/audio/volume", "status": 200, "token": "<control token>", "version": 1},
 "body": {"value": 35}}
```

`GET /audio/volume` returns `{"value", "min", "max", "muted", "properties": {"maxLimit", …}}`.
`PUT` with `{"value": n}` or `{"muted": true}` changes it. The response comes back with the same
`reqID` and `msgtype: "RESPONSE"`.

## Project layout

* `core/`: plain Kotlin/JVM library for auth, the speaker client and the volume controller. It
  has tests against a fake TLS WebSocket speaker and a fake Bose login server
  (`./gradlew -p core test`). It needs no Android SDK.
* `app/`: Android app with the setup screen, sign-in WebView, mDNS discovery and the
  `AppWidgetProvider`.

## Caveats

* This is an unofficial protocol and Bose may change it. It needs an internet connection only
  for signing in and refreshing the token; volume commands themselves stay on the LAN.
* Certificate checks are turned off **only** for the connection to the speaker, because it uses
  a self-signed certificate. Bose cloud calls use normal TLS.
* Tokens are stored in the app's private SharedPreferences.
* Not for SoundTouch-era speakers (they use a different HTTP API on port 8090) or for Bluetooth
  headphones.
