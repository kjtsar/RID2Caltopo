# Drive-Hosted FAA Config

> Status: superseded for the current pilot by the tracker-hosted FAA NOTAM
> proxy. The historical design remains below for context.

## Current Pilot and Paid-Tier Follow-Up

The pilot Android build uses a compile-time FAA proxy URL and token that are
independent of the tracker upload and coordination URL. The local
`faa-proxy.properties` file is ignored by Git; its values are compiled into the
APK for field qualification.

This is temporary. FAA proxy requests create hosted tracker traffic and should
be treated as a paid-tier capability when organization administration is
introduced. The paid-tier organization configuration and admin-generated QR
code should include an organization-scoped `faa_proxy_token` and the
corresponding entitlement. Free-tier configurations should omit the token, and
the app should then report the FAA proxy as unavailable rather than clear.

Before paid-tier rollout:

- replace the compile-time token with the organization `faa_proxy_token`;
- support token rotation and revocation in the tracker admin page;
- keep FAA OAuth client credentials exclusively on the proxy;
- ensure Android and Apple use the same entitlement and configuration contract;
- meter proxy use by organization without logging tokens or FAA credentials.

## Goal

Allow new RID2Caltopo organizations to use the shared FAA/NOTAM integration without baking FAA credentials into the APK and without accidentally distributing Ken's CalTopo, tracker, RID map, or mutual-aid credentials.

The FAA config should be its own config domain, separate from:

- `ct_credentials`: home org CalTopo/tracker/app settings
- `ct_ridmap`: drone Remote ID to mapped ID ownership table
- `ct_mutual_aid_credentials`: mutual-aid package template credentials

## Proposed Model

Host a single FAA config file in Google Drive and distribute a small import token or QR code to trusted RID2Caltopo administrators.

The app stores the remote FAA config token plus a local obfuscated last-good FAA payload. On normal startup it uses the local cache and does not contact Drive. It downloads the Drive-hosted FAA config only when the token is first imported, when no local payload is cached, or after FAA authentication rejects the cached credentials.

The app should not require a full reset, and should not require loading Ken's full `ct_credentials` file into another organization's device.

## Token Contents

The local token uses the `R2CFAA1:` prefix and should include:

- Google Drive file ID
- config version or generation marker
- decryption/de-obfuscation metadata
- optional display label, such as `NCSSAR shared FAA NOTAM credentials`

Do not hardcode the Drive file ID plus decrypt key directly in the APK. That would make the credential effectively public to anyone who can inspect the app.

## Drive File Contents

The Drive-hosted payload should be a dedicated FAA config type, for example:

```json
{
  "type": "ct_faa_credentials",
  "file_version": "1.0",
  "updated": "2026-05-15T00:00:00Z",
  "notam_api_base_url": "https://api-nms.aim.faa.gov/nmsapi",
  "notam_token_url": "https://api-nms.aim.faa.gov/v1/auth/token",
  "notam_client_id": "...",
  "notam_client_secret": "...",
  "notam_scope": ""
}
```

The public Drive file should not contain this object in plaintext. It should use a protected wrapper similar to org-config credential blocks, but preferably with a token-specific key rather than only an app-wide key.

Current pragmatic implementation uses app-known obfuscation. This is intended to prevent accidental plaintext disclosure in the repo, APK, Drive, logs, protobuf backups, or org bundles; it is not strong secrecy against a determined reverse engineer.

## Startup Behavior

On app startup:

1. Check whether NOTAM credentials are already available locally.
2. If missing, apply the locally cached obfuscated FAA payload.
3. If the cache is missing or marked stale, check whether a remote FAA config token is configured.
4. If configured, download the Drive file using the file ID.
5. Validate the payload type, version, and expected fields.
6. Apply only FAA/NOTAM fields.
7. Record status for diagnostics.
8. Do not periodically check Drive while the local cached credentials continue to authenticate.

Recommended status text:

- `FAA credentials: local`
- `FAA credentials: remote, last checked <timestamp>`
- `FAA credentials: remote fetch failed: <short reason>`
- `FAA credentials: not configured`

The download should not block main app startup. If the fetch fails, RID scanning, MapPane, tracker coordination, and normal app use should continue.

If FAA token or NOTAM requests return HTTP 401 or 403, mark the cached FAA payload stale. Later app starts should retry the Drive fetch until a working payload is cached again.

## Export Rules

`Export Org Config` should not include FAA credentials by default.

If an org is configured with the FAA token, export only the remote FAA config token and obfuscated FAA cache bootstrap, not the decrypted FAA client secret.

This avoids the current risk where a full `ct_credentials` export can carry unrelated secrets:

- CalTopo team ID/API credentials
- tracker API key and URL
- incident/op period defaults
- NOTAM/FAA credentials

## Local Storage

Preferred options:
- Store the remote FAA token.
- Cache an obfuscated last-good FAA payload so normal startup and offline startup do not require Google Drive.

Avoid storing decrypted FAA client secrets in normal app config backups or org export bundles.

If decrypted values must be retained locally, keep them in a clearly separate FAA credential store so org-config export code cannot accidentally include them.

## Security Notes

Google Drive "anyone with link" should be treated as public. The file ID is not a secret.

Existing org-config XOR obfuscation prevents casual plaintext exposure, but it is not strong cryptographic protection. For this FAA workflow, prefer a token-specific key or a future server-side broker if the credential needs stronger protection.
Best stronger-security option remains a small server-side FAA token broker, where RID2Caltopo devices never receive the FAA client secret at all. A NOTAM data proxy is not required for the current bandwidth-conscious design because devices continue querying FAA directly.

## Open Implementation Questions

- Should the remote FAA token be imported via QR code, JSON file, or both?
- Should the app automatically refresh the Drive-hosted config on every startup, or only after a TTL?
- Should a remote FAA config be allowed to override an existing local FAA config?
- Should org-config export include the remote FAA token when present, or require an explicit checkbox?
- What is the rotation/revocation process if the shared FAA credential changes?
