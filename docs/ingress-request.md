# Ingress request for `codex/charts/iris`

**For the Codex session** (chart + deploy owner). Requested by Calvin 2026-08-26; written by the
Iris session because the Codex session had ended when the request came in. Nothing here has been
applied — `charts/iris` still ships no ingress, and the service remains ClusterIP-only.

## What Calvin asked for

> Tailnet host, **scaffold Authelia but don't enable it**.

So: reachable at a tailnet hostname now, with the Authelia wiring present in the chart behind a
values flag that defaults **off**, so it can be turned on later without a chart change.

## Shape

```yaml
ingress:
  enabled: false            # safe default; the values file for this cluster sets true
  className: ""             # load-bearing — see traps
  host: iris.tailscale
  clusterIssuer: ""         # no cert-manager on this cluster
  tlsSecretName: ""
  authelia:
    enabled: false          # scaffolded, OFF per Calvin
```

## Traps (from `UI-PLAYBOOK.md`, each learned the hard way)

1. **Omit `ingressClassName` entirely when className is empty** — guard it with
   `{{- if .Values.ingress.className }}`. An empty-string value is RFC-1123-invalid and k8s
   rejects the object outright. This bit artemis-ui across two releases.
2. `className: ""` is deliberate: no IngressClass resource exists here, and k3s's Traefik v2.4
   silently ignores named classes.
3. No cert-manager exists — a `codex-ca` clusterIssuer default is a footgun. Leave issuer and
   TLS secret empty.
4. Traefik is reached on **NodePort :61642**.
5. **Never verify on port 80.** The QNAP host's own nginx answers `200` to any Host header and
   lies — a green curl on :80 proves nothing. Verify on :61642 with the Host header set.

## The exposure, stated plainly

**Iris's API is completely unauthenticated and serves the full plaintext of every note** —
journals, financial notes, everything in the vault. Today that is contained only by the service
being ClusterIP-only. Once this ingress exists, **anything that can reach the tailnet can read
Calvin's entire vault with no login.**

That is his informed decision, made with this stated. It belongs in the chart README next to
`authelia.enabled: false` so the reason to flip it on is obvious to whoever reads it next.

Two qualifiers:

- Phase 1 has **no write path**, so the exposure is disclosure, not damage.
- When the one-folder write rule lands in phase 2, **writes must require the per-service API key
  regardless of ingress auth.** They are independent gates; an unauthenticated ingress must never
  become an unauthenticated write path. See `vault-access-model.md`.

## Verification when deployed

```
curl -H 'Host: iris.tailscale' http://<node>:61642/health     # expect {"status":"UP",...}
```

The Iris session can verify the index and API surface behind it on request.
