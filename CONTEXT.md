# Context

The canonical project handoff document is now [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md).

Use that file as the primary engineering and AI-agent context source. It reflects the current sync-first architecture:

- Android Room is the UI source of truth
- Raspberry Pi is the local backend and sync hub
- pairing triggers an immediate first sync
- live runtime verification is still partially unverified in this environment
