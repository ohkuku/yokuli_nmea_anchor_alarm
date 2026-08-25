# Saved anchorages product contract / 收藏锚地产品约定

Status: implementation contract for `codex/develop`  
Scope: local-only saved anchorages, approach guidance and QR hand-off. This is not a social cruising database.

## 1. Product intent / 产品目标

Boat Watch helps a sailor retain places they have personally planned or used, return to them, inspect the parameters that were useful last time, and hand that local knowledge to a friend without requiring an account or cloud service.

Boat Watch 用于保存自己规划过或实际使用过的锚地，之后可以查看当时有用的参数、重新接近该位置，并通过二维码把本地资料交给朋友。它不是社区锚地评分平台，也不要求账号或云服务。

The database may keep the precise `Place → Spot → Visit` relationship, but those are implementation terms. The primary UI vocabulary is:

- **Saved anchorage / 收藏锚地** — the named area the user recognises.
- **Anchoring position / 锚泊位置** — one usable coordinate inside it.
- **Visit / 到访记录** — immutable evidence captured from an Anchor Watch session.

## 2. What we borrow from mature cruising products

NoForeignLand demonstrates useful interaction patterns: saved markers are found from a map, search is centred on the visible map, filters are explicit and removable, and a selected marker leads to a useful detail/action surface. Boat Watch adopts those interaction principles, not its community reviews, public marker editing, social feeds or online identity model.

## 3. Canonical user stories

### Browse

1. Open Saved anchorages.
2. See a map or list, never two overlapping navigation models.
3. Search names and notes. Common filters are visible: All, Favourites, Visited and Planned.
4. Region browsing is secondary and only describes where saved data is located.
5. Selecting a marker/card opens the complete saved-anchorage detail once; there is no preview-then-detail duplicate.

### Inspect and act

1. The full-screen detail starts with the saved anchorage name and one or more anchoring positions.
2. Each position exposes exactly three primary actions: Approach, open in Google Maps, and Share.
3. Stored depth, rode, alarm radius, notes, uncertainty and past visits are reference data, never a promise that current conditions are safe.
4. Edit changes the saved local reference. Delete requires confirmation and is blocked while the active Anchor Watch session references this anchorage.

### Nearby

1. The Watch setup sheet owns the single nearby prompt.
2. One nearby result is shown directly as its complete card.
3. Multiple results open a list of complete cards.
4. Starting Approach switches to its dedicated full-screen destination; it never hides behind the Anchor Watch sheet.

### Share without a cloud account

The generated Boat Watch share card contains two independently useful QR codes:

1. **Open location** — a standard Google Maps HTTPS URL. Any ordinary QR reader can use it.
2. **Import details** — the versioned Boat Watch payload containing the anchorage/position metadata. It remains local and is accepted only after the receiving user reviews it.

The card also shows the Boat Watch logo, coordinates, useful saved parameters, an explicit personal-reference warning, and `Developed aboard SV Yokuli`. No visit history, private photo, device identifier or hidden telemetry is encoded.

## 4. Logic boundaries

```text
Room GIS model (Place / Spot / Visit)
        │
        ├── Library query + viewport/search/filter
        ├── Detail editor + protected deletion
        ├── Nearby projection ──> one Watch-sheet prompt
        ├── Approach target ────> full-screen navigator
        └── Share projection
              ├── public maps URL QR
              └── versioned Boat Watch import QR
```

- `AnchoragePlaceRepository` and `AnchorageSpotRepository` own validated mutations.
- `AnchorageApproachRepository` is the compatibility/projection boundary used by Watch and approach guidance.
- Share image generation is a pure local export. It does not upload coordinates or notes.
- Duplicate imports continue to use distance/name matching and must not silently create the same saved anchorage again.

## 5. Deliberate non-goals

- No public ratings, comments, follower graph, community moderation or anonymous cloud upload.
- No claim that a previously safe anchorage is currently safe.
- No automatic Set Anchor Watch from a remote/planned coordinate; the boat must be present and current conditions must be checked.
- No raw internal enum names in user-facing UI.

## 6. Acceptance checklist

- [ ] Exactly one nearby prompt is visible on Watch.
- [ ] A saved item opens one full-screen detail surface.
- [ ] Detail supports Approach, Maps, dual-QR Share, Edit and protected Delete.
- [ ] Empty, search, map/list and filter states remain understandable in English and Chinese.
- [ ] No `Place`, `Spot`, `WANT_TO_VISIT`, `BACKUP`, `AVOID` or raw verification value is required to operate the UI.
- [ ] Map QR decodes as a Google Maps URL; import QR decodes as a valid Boat Watch payload.
- [ ] Deleting the saved anchorage used by an active watch is rejected below the UI.
- [ ] All implementation tests are written; hardware/manual verification remains explicit.
