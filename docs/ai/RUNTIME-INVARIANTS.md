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
- Keep packet codecs, chunk assemblers, rate limiters, request maps, retry state, and caches bounded.
- Large texture bytes are demand-driven: advertise appearances/hashes, and send bytes only after a
  missing client requests them. Preserve the global per-tick response and upload pacing.
- Appearance snapshots and updates use bounded, session-identity-aware pacing plus exact completion
  acknowledgement. A dropped or superseded snapshot must converge through bounded retry.
- Network texture identity is the hash of the exact canonical transmitted PNG. Local cape asset IDs
  use `HashUtil.computeAssetHash(..., "cape")` and are deliberately domain-separated from skin IDs.
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
