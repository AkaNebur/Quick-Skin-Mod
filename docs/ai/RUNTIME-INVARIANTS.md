# Runtime architecture invariants

This file is part of the repository-wide instruction set imported by `AGENTS.md`.

## Lifecycle and threading

- `ClientRuntime` and `ServerRuntime` are the composition roots for session-scoped state. Loader
  lifecycle hooks must enter and leave through them.
- Teardown must use exact connection/session identity. A delayed disconnect must never clear a
  replacement connection for the same UUID.
- Minecraft client and server state is committed on the appropriate main thread. File reads, image
  decoding, hashing, chunk assembly, and other bulk work belong on the bounded worker executors.
- Executors, queues, prepared handoffs, and pending transfers must remain bounded and must release
  leases on success, failure, cancellation, disconnect, and shutdown.

## Networking and texture identity

- Treat all packet fields, lengths, hashes, image data, metadata, and disk cache contents as
  untrusted. Validate before allocation or state mutation.
- Historical v1 packet identifiers, codecs, and bare 40-character SHA-1 content IDs are an
  immutable compatibility contract. Strong identities use separate v2 packet identifiers; never
  reinterpret a v1 channel with a different schema.
- Protocol authority belongs to the exact player UUID plus connection object, never to a UUID
  alone. Enable v2 only after that session's hello/ack exchange; a registered legacy channel is
  explicit v1 evidence, and absent Quick Skin channels remain local-only. Reject v2 traffic before
  successful negotiation and clear only the exact profile on disconnect or all profiles at
  shutdown.
- Admit protocol hellos through the exact-connection rate budget before queuing main-thread work,
  and bound cached ACK replay to the client's finite retries for that nonce. The authenticated
  hello itself is ACK-channel evidence where Forge/Architectury channel queries are unreliable.
- Before every S2C send, require authority from the recipient's exact connection profile. A
  valid v2 hello asserts that mandatory receivers were registered before it was emitted; its
  selected profile is the channel contract, and optional receivers additionally require their
  negotiated capability. A v1 profile may be established only by an exact loader-channel probe or
  an authenticated packet on the immutable v1 channel family; before that evidence, legacy peers
  still require the concrete probe. Never borrow evidence from another UUID-only or stale session.
- Translate SHA-1 and SHA-256 aliases only after the server has verified both against the same
  canonical PNG, and select the outgoing alias for each recipient's negotiated profile. Never
  derive or trust an alias from an unverified peer-provided string.
- SHA-256 is the authoritative server cache key. If multiple authenticated SHA-256 blobs share a
  SHA-1 alias, retain every strong entry but refuse to resolve or emit that ambiguous legacy alias.
- Peer-advertised texture and chunk limits may only reduce local hard caps. Codecs, assemblers,
  pacing, and caches continue to enforce the local bounds even after negotiation.
- Keep packet codecs, chunk assemblers, rate limiters, request maps, retry state, and caches bounded.
- Large texture bytes are demand-driven: advertise appearances/hashes, and send bytes only after a
  missing client requests them. Preserve the global per-tick response and upload pacing.
- Appearance snapshots and updates use bounded, session-identity-aware pacing plus exact completion
  acknowledgement. A dropped or superseded snapshot must converge through bounded retry.
- Network texture identity is the hash of the exact canonical transmitted PNG. Local cape asset IDs
  use `HashUtil.computeAssetContentId(..., "cape")` and are deliberately domain-separated from
  skin IDs. New local skin, cape, and CPM catalog primaries are canonical `sha256-...` IDs.
- Bare SHA-1 local IDs are read-only compatibility aliases. Publish and migrate an alias only after
  the complete bounded scan proves it resolves to one SHA-256 primary; an ambiguous alias must not
  resolve, select metadata, migrate a path, or be written back to configuration.
- Client caches are keyed by both hash and texture type. The same PNG bytes may validly exist as a
  skin and a cape; never collapse typed keys back to a hash-only cache or resource path.
- Renderer-confirmed skin/cape use receives only a short, bounded working-set preference. Cache
  entry, byte, and pixel caps remain hard even when every resident texture is visible.
- Animated canonical PNGs carry exact `qsMD` metadata through `PngAnimationIdentity`. Changing frame
  timing must change the transmitted identity even when pixels are unchanged.
- Animation slots are visibility/staleness based, not arrival ordered. A visible network animation
  without a slot must use its separately bounded first-frame texture (or render nothing while that
  frame is prepared); never expose the stacked atlas as a static cape fallback.
- Server animation metadata is immutable after the first accepted value for the lifetime of the
  backing cape. Delivery-cache eviction must not delete the persistent identity binding; backing
  texture deletion must remove metadata, authority, and identity together.
- Appearance and animation convergence depends on exact acknowledgements and bounded retry. Do not
  replace it with optimistic send-once synchronization.

## Files, images, and persistence

- Use `BoundedFileReader`, `SafeImageReader`, and the established GIF preflight path. Do not add
  production `ImageIO.read`, unbounded `Files.readAllBytes`/`readString`, or decode-before-dimension
  validation.
- Resolve content-addressed paths through the containment helpers. Reject invalid content IDs,
  symbolic-link targets, and paths outside the configured root.
- Persist mutable state using a temporary file plus atomic replace where supported. Keep the
  fallback contained and clean stale temporary files during initialization.
- Local identity migration must copy/write and validate the strong-ID destination before removing
  a legacy file or preference. Preserve the legacy source whenever destination verification fails
  or an existing strong destination differs.
- Keep cache accounting weighted by bytes/pixels where relevant, not only entry count. Active server
  appearance blobs remain pinned only within the hard global pinned-byte budget; reject an
  over-budget appearance gracefully instead of weakening the cap.
- Server cache deletion belongs on the bounded cache-I/O executor. Remove a cache entry from the
  live namespace before scheduling deletion so a concurrent replacement cannot be deleted.

## Optional integrations

- CPM, Ears, CustomNPCs, and 3D Skin Layers are optional. Guard their entry points and preserve the
  normal skin/cape path when an optional mod or API is absent.
- Compatibility failures must degrade locally; they must not break base mod initialization or
  dedicated-server startup.

## Public E2E evidence

- A public screenshot is valid only when a successful packaged `result.json` references it and its
  recorded SHA-256 and dimensions match the PNG. Do not infer scenario, role, or step from a
  filename, and do not let sets or duplicate labels collapse two frames into false coverage.
- `e2e/visual-catalog.json` and `EXPECTED_SCREENSHOT_STEPS` must cover exactly the same semantic
  checkpoints. Add or remove a capture in both places and update its expectation in the same
  change.
- The current catalog is deliberately a cross-version parity contract: every supported loader and
  version publishes every checkpoint. Do not add a version-only capture without first extending
  the catalog schema and protected validator with explicit applicability rules.
- Public evidence is bound to source run/branch/SHA and final run/branch/SHA. Pages may select a
  bundle only when its authenticated originating target run and manifest both match the current
  release-branch head; a later protected Pages run may only roll that already validated bundle
  into cache.
- Retention is current-state, not longitudinal history. Keep exactly one durable Pages cache per
  release branch. Treat raw packaged-E2E uploads, `pages-e2e-<branch>`, Pages fan-in, and the deploy
  artifact as short-lived handoffs. Rotation happens in a separate protected workflow after the
  owning Pages run is `completed/success`; it must recheck run provenance, the release head,
  replacement artifact identity, and every deletion ID before retiring the exact consumed handoff,
  Pages-run intermediates, and caches older than the successful replacement. Raw packaged-E2E
  proof expires after one day; do not delete it during promotion because a concurrent branch
  attestation may still consume it. A failed E2E, deployment, validation, or rotation must preserve
  the previous usable cache, and a delayed rotation must never delete a concurrent newer generation.
- Discovery records one protected `master` SHA for the Pages run. Every collection and render job
  checks out that exact implementation revision; an advancing `master` may affect only a later run.
- Treat downloaded artifacts and their JSON as untrusted. Require the exact curated tree, exact
  schemas, complete catalog and comparison products, canonical identities, one loader per branch
  loader, and one JAR digest per artifact. Reject traversal, symlinks, unknown catalog entries,
  duplicate identities, non-pass lanes, stale SHAs, invalid PNGs, arbitrary nested fields, and
  size-limit violations. Protected rendering must decode and recompute screenshot/comparison pixel
  metrics before publishing. Presentation code must use escaped/text DOM APIs and local assets.
- Optimized gallery images are derivatives, not the source proof. Publish separate source and
  derivative hashes/dimensions, and content-address each public image URL with the bytes actually
  served. Original PNGs may exist only in the one-day `pages-e2e-*` handoff. Protected conversion
  must revalidate source bytes and metrics before atomically producing the WebP-only fan-in/cache;
  every later cache/render read must revalidate the retained source record, derivative bytes,
  derivative metrics, and derivative comparisons.
- Pages is an advisory, atomic publication surface. Failure must preserve the previous site and
  must not weaken or replace the required Build and Packaged E2E gates.
