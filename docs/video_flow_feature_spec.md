# Video Flow feature spec

## Core concept

Video Flow indexes videos from one user-selected source folder and presents them as Timescape-style cinematic cards. Source media remains in-place; the app stores only indexed metadata, generated cover cache artifacts, and user interaction state.

## Functional scope

### 1) Source and scanning
- Single source folder via SAF tree URI.
- Persisted long-term URI permission.
- Optional recursive scanning of subfolders.
- Supported format detection via MIME + extension fallback.
- Metadata captured per video:
  - file name
  - duration
  - modified date
  - resolution
  - aspect ratio
  - file size
- Rescan triggers:
  - manual refresh
  - startup refresh when enabled
- Incremental reconciliation:
  - add newly discovered videos
  - remove missing videos
  - update cards for metadata changes

### 2) Card generation
- Stable card identity keyed by source document path hash.
- Cover generation via random frame sampling after an early-seconds skip window.
- Retry loop for dark/blank-ish frame rejection.
- Persist chosen timestamp for visual stability.
- Cache generated covers locally.
- Preserve interaction state independently of current browse mode:
  - favorite
  - hidden
  - pinned
  - watch progress

### 3) Negative-1 page UI (target)
- Dedicated leftmost Video Flow page.
- Hero card + scrollable list below.
- Browse modes:
  - Recent
  - Random
  - Favorites
  - Continue watching
  - Hidden
  - Folder
  - Date-grouped
- Video presentation affordances:
  - wider aspect cards
  - play overlay icon
  - duration badge
  - optional gradient from cover
  - focused card elevation/highlight
