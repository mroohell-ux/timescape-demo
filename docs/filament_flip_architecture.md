# Filament Flip Architecture (Timescape)

## Goal
Render card flips as real 3D geometry (thin card body) with perspective, lighting, and physical thickness using Filament.

## Runtime shape
- Recycler item keeps existing Android layout.
- A `FilamentCardFlipView` overlays card content only during flip.
- `FilamentCardFlipRenderer` owns Engine/Scene/View/Camera/Renderer/SwapChain and a card mesh entity.
- Front/back snapshots from current card content become dynamic textures uploaded to Filament.

## 3D object
- Single thin cuboid mesh (front, back, left, right, top, bottom faces).
- Separate material slots:
  - front face: textured with front snapshot
  - back face: textured with back snapshot
  - side faces: neutral laminated edge material (roughness + slight anisotropy)

## Motion
- Off-center pivot and hand-like easing.
- Slight settle without cartoony bounce.
- Real perspective from camera FOV and near/far planes.

## Integration points
- Adapter: keep existing front/back eligibility logic.
- Adapter: when flip starts, request front/back bitmaps and pass to `FilamentCardFlipView.startFlip(...)`.
- Adapter: hide legacy content while flip is active; restore on completion.

## Performance
- Reuse engine + renderer per view.
- Reuse textures/material instances and only update image data.
- Destroy Filament resources in `onViewRecycled`/detach.
