# Contracts

This directory is the source of truth for behavior shared by all clients.

- `api/`: HTTP API schemas and versioning notes
- `signaling/`: WebRTC signaling schemas
- `fixtures/`: valid and invalid payload examples

Contract changes must be backwards-compatible unless an approved migration plan
is included in the same task.

- `signaling/v1.schema.json`: legacy signaling payloads without session ownership
- `signaling/v2.schema.json`: authenticated signaling payloads with access and owner tokens
