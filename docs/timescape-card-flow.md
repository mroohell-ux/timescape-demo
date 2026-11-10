# Timescape card flow

The default view is a ribbon of compact card faces, arranged after `cards.png`.
Cards recede in size and center-to-center pitch together, following a continuous
ribbon path rather than a repeating lateral wave. The overview face
is slightly narrower and text previews have a 112dp minimum height. Logical
pitch uses 28% of the available width, bounded to 92-124dp before font scaling,
keeping the shorter faces closely stacked. Cards overlap their more distant
neighbors, with a 52% minimum depth scale on both sides. Logical scroll and
reading selection remain linear; the visible window and prefetch use the inverse
compressed geometry so distant cards still cover tall viewports.
Cards use real RenderNode perspective, not an affine scale or a selected-state
rotation animation. One signed depth (card index minus continuous scroll position)
drives pitch, yaw, roll, size, lateral bend and the moving rotation anchor. The same
smooth expansion weight blends the compact and reading faces. Approaching the
focus reduces tilt to zero; departing cards lie backward again.
The closer camera makes upper/lower edge lengths and all four corner trajectories
unequal. An additional four-point projective morph unfolds corners at different
rates, rather than only rotating a rigid rectangle. For a card approaching from
below, the top-left corner leads, followed by top-right, bottom-left and
bottom-right. Approaching from above vertically mirrors that order: bottom-left,
bottom-right, top-left and top-right. Corners delay a share
of the card's actual depth-scale growth, rather than adding a temporary inward
pulse: the incoming lower edge expands without first narrowing and appearing to
recede. These are curves of the same continuous distance-to-focus progress, not
separate timers or velocity-triggered states. Reversing a drag retraces the same quadrilateral.
Direction blends continuously near the focus instead of switching when the
nearest selection changes. Morphing applies to overview and continuous reading;
both compact distant faces and the focus recover their unwarped outline.
The focus recovers a front-facing rectangle in overview and reading.
Upper cards vertically mirror the lower cards' perspective without reflecting
the photographs or lettering. Default distant pitch varies smoothly from about
25.5 degrees at one card away to 28.4 degrees at six cards away below focus,
with equal negative angles above focus. Yaw is equal at matching distances;
the vertical pivot, corner order and screen roll are mirrored. Opening
continuously reduces the perspective to zero.
Yaw continues changing across the whole band using
`depth / (abs(depth) + 3 * tiltDepth)` and the existing smooth away/opening blend.
The outer edge's target screen angle is
`-maximumRollDegrees * tanh(depth / (8 * tiltDepth)) * away`.
At default settings, edges one through six cards away reach approximately
1.5, 2.9, 4.3, 5.5, 6.7 and 7.6 degrees in opposite directions above/below
a level focus. The outer edge is the lower card's bottom edge or the upper card's
top edge, so correcting it preserves a true vertical mirror of the quadrilateral.
After measuring the real four-corner/native projection, the layout
corrects the Euler roll to reach that edge angle. This prevents varying text,
portrait and landscape face heights from cancelling the visible fan progression.
The correction reuses per-card scratch coordinates, takes at most two matrix
samples, and keeps native roll within the configured limit. Extreme customized
poses retain that safety bound; a zero roll limit disables the fan correction.
Changing yaw still changes the trapezoid's side lengths, not just screen rotation.
The edge-angle correction preserves pitch, dimensions, spacing and unfolding corners.
Native regressions check the actual edges of different-height cards within
0.05 degrees of their targets, at least 0.8 degrees between consecutive edges,
and a perspective-only side-length comparison.
Subpixel translations preserve smooth motion and compensate the scale component
of a moving anchor without cancelling its perspective.
Both halves use the existing lower ribbon's lateral curve:
`(-sin(d * bendFrequency) * lateralBend + d * lateralDrift) * (1 - opening)`,
where `d = min(abs(depth), 6)`. Vertical offsets have opposite signs, while
horizontal offsets match. Upper cards therefore follow the same curved motion,
not the previous straight tangent; the distant upper arm still exits toward the
upper right. Both default depth-shrink rates are 0.10, so scale, integrated
vertical spacing and scale-derived corner growth have the same rhythm.
Layer depth and media-preview opacity also match at equal distances.
Custom callers may still override `departingShrink` independently; change both
shrink rates together to retain the mirror when tuning.
Native regressions compare all four actual projected corners of matched text,
portrait and landscape cards within one pixel in overview and reading, including
equal-and-opposite quarter, half and three-quarter-card scroll offsets. Text
continues facing the screen, and photographs retain their original orientation.
Depth changes continuously while scrolling.
Overview scrolling never automatically expands a card.

`CardFlowGeometry.kt` centralizes tuning. Pass a `CardFlowGeometry` to the layout
manager or assign `manager.geometry = manager.geometry.copy(...)` to apply it on
the next layout without resetting selection, scroll or reader offsets:

| Parameter | Default | Meaning |
| --- | --- | --- |
| `maximumPitchDegrees` / `maximumYawDegrees` / `maximumRollDegrees` | 30 / -10 / 12 | Bounded X/Y/Z rotations; roll also bounds the projected-edge fan target |
| `tiltDepth` | 1 | Full pitch/anchor blend radius in card pitches; yaw uses a soft radius of 3 times this depth, and the projected-edge fan uses 8 times it |
| `cameraDistanceInCardWidths` | 1.35 | Smaller values strengthen perspective; distance is at least 1.1 times the larger card dimension or corner radius to protect tall faces and extreme anchors |
| `minimumScale` / `maximumScale` | .52 / 1 | Compact scale range; fully open reading remains at its existing width and scale |
| `depthShrink` / `departingShrink` | .10 / .10 | Matching compact shrink per card below/above focus; independently overridable |
| `distantScaleY` | 1 | Additional Y scale away from focus; default preserves media proportions |
| `focusPivotX` / `focusPivotY` | .5 / .5 | Focus anchor fractions |
| `distantPivotX` / `distantPivotY` | .46 / .78 | Distant anchor fractions, continuously interpolated |
| `lateralBend` / `lateralDrift` / `bendFrequency` | .095 / .012 / .65 | Shared lateral curve, mirrored vertically above focus |
| `cornerStagger` | .14 | Depth-growth delay per corner; 0 disables the staggered morph |
| `cornerWidthLead` / `cornerHeightLead` | .55 / .30 | Share of horizontal/vertical scale growth delayed at each corner |
| `cornerDirectionBlendDepth` | .15 | Continuous direction-blend radius near focus, in card pitches |
| `pitchDepthVariation` | .15 | Small fractional pitch variation with distance; 0 makes distant angles uniform |
| `letteringFacingPitchDegrees` | 0 | Screen-space lettering pitch after correcting the complete card projection |
| `letteringLiftDp` | 14 | Raised separation above the card, easing to zero during opening |
| `cardOverlapFraction` | .10 | Slight compact-card overlap; adjustable from 0 to .25 |

The implementation is View/RecyclerView, so native `rotationX`, `rotationY`,
`rotation`, `scaleX/Y`, `translationX/Y`, `cameraDistance` and `pivotX/Y` provide
the GPU layer transform equivalent to Compose `graphicsLayer`/`transformOrigin`.
The corner morph uses `Matrix.setPolyToPoly` on the existing card canvas before
the native 3D transform. It warps all existing surfaces and cached layers together;
there is no Compose migration, mesh, extra bitmap or new animation clock.
The same inverse corner matrix handles taps and transformed touch coordinates,
so the rectangular slot's empty corners do not capture card clicks. Morph corners
stay inside that slot, preserving parent hit dispatch, clipping and ribbon layout.
The reading surface and media keep their height clip in the morph's source plane.
Raised preview titles and summaries instead retain their natural projected baseline
scale and may extend beyond all four slanted card edges; neither glyph containment
nor card-edge clipping reduces their size. This does not change the ribbon's depth
scale, text wrapping, media fitting or card hit region. Shared transition glyphs
follow that same overflowing plane, while surplus reading paragraphs keep their
viewport and lower fade. Text-transition and wind-opacity layers include the raised
ink rather than cutting it off at the photographic face.
Camera width ratios refer to actual screen-space projection distance, converted
to View's DPI-normalized depth pixels (the underlying camera uses 72 pixels per
unit), rather than incorrectly treating `View.cameraDistance` as screen pixels.
Changing scale also updates the inverse compressed geometry for viewport coverage
and prefetch. Parameter ranges are validated instead of silently accepting invalid
projection settings. Pose storage is reused per attached card.

For example, `manager.geometry = manager.geometry.copy(maximumPitchDegrees = 24f,
cameraDistanceInCardWidths = 1.8f, minimumScale = .48f, maximumScale = 1.05f,
distantPivotY = .7f)` changes the geometry without adding independent animators.

Text previews put the original background photograph behind a light-weight
title, up to four summary lines and a timestamp. The previous two-line preview
capacity is doubled without shrinking the font or truncating the underlying
text used by the continuous glyph transition. Photographs fill the rectangular face
with CenterCrop, lightly reduced saturation (90%) and contrast (96%) to retain
vivid source colors. The photograph
retains 72% alpha in its lightly softened central region over a lightly translucent blue glass
base. Each outer 24% side band progressively softens and becomes more transparent,
reaching 20% of the central photo opacity at the very edge. Maximum side softening
is 3.5dp; the central 52% uses 2dp softening, increasing continuously to 3.5dp
across each side band. The profile depends only on horizontal
position, never height, so the photo remains rectangular rather than forming an oval.
There is no milky overlay, vertical fade, oval vignette, dark scrim or artificial
pixel noise. Only the photograph's alpha changes, not the lettering or action
icons. The same blend renders in overview, reading and flip snapshots.
Text cards use a fine 0.8dp translucent white/cool rim, a shallow 1.2dp light-catching
bevel and quiet side reflections over the glass base, with no opaque or milky center.
Original image, handwriting and
video faces retain their shallow 2.2dp bevel: a cool dark side, a fine 0.8dp
illuminated rim and a faint inner bottom highlight suggest thickness without a
wide white frame. Both edge styles preserve each face's existing corner radius.
Edging is drawn entirely inside the outline, with a transparent center; it changes
neither media fitting nor action placement and adds no blur or animation timer.
White lettering, icon tints and user-customized text colors remain unchanged;
the existing cool glyph bevel and cast shadow preserve separation from the photo.
Lettering is a separate sibling plane, not clipped inside the photograph. It
slightly overhangs the leading edge and sits above the face with scroll-linked
parallax. Its bottom-center hinge follows each card, while a native animation
matrix cancels the complete screen-space card/ancestor projection, including
pitch, yaw, roll, perspective, four-corner morph and temporary Flow wind motion.
The default glyph plane stays rectangular and directly faces the viewer at every
scroll position; its local correction changes continuously instead of merely
negating X/Y Euler angles. No text reflow, extra raster snapshot or animator is used.
The default facing pitch is zero and the more visible 14dp lift eases away as the
card opens; both are tunable in `CardFlowGeometry`. Its camera uses the same calibrated
width ratio and safe corner radius as the card. Uniform scale is bounded analytically
by the projected card edges using actual shaped-glyph bounds, not unused container
padding, and includes a glyph-shadow margin, so upright four-line
text does not get clipped or stretched under extreme legal poses.
The text plane reserves a 38dp trailing
inset so upright glyphs stay inside the projected right edge; the
text layout gains the same 16dp of width and is fitted back into the existing
card plane, preserving the previous paragraph wrapping instead of introducing
new word crossings during the glyph morph. The small leading-edge overhang is
retained.
Glyphs have crisp illuminated faces, a shallow cool bevel and a detached
cast shadow, rather than a large blur or stronger card rotation. Summaries retain
the same compact size with or without a title, two sp below the reading preference;
titles are only modestly larger (16sp summaries, 20sp titles and 11sp timestamps
at the default setting). The edge inset eases
away during opening so preview and reading faces remain aligned.
Preview dates sit on the lower-left glass face, opposite the small type mark,
independently of the title and four-line raised summary. They follow the card's
tilt instead of standing upright with the prose. A 32dp bottom text inset and
112dp minimum text-card height keep these planes apart without a separate footer
layout row. Title-free previews contain only the summary, never a date prefix;
original image, handwriting and video previews still omit dates.

Adjacent overview cards slightly overlap. Their visual pitch derives from the
minimum card height, projected tilt and `cardOverlapFraction` (default `.10f`,
tunable from zero to `.25f`), with continuous size taper on both sides of focus.
Taller compact faces contribute their projected extra height, so four-line prose
and portrait media do not pile up excessively. Growth offsets are anchored to
the fractional scroll position, not the attached-window origin or a selected
index, keeping recycling and reversal continuous. The original logical scroll
distance, snapping and gesture thresholds remain unchanged. Window coverage and
prefetch use the same conservative base pitch; extra height only expands spacing.
Reading dates sit beside the icons in the top action strip, stay single-line and
use 10-12sp text, clear of the default left-side floating bookmark control.
Dragging the floating bookmark control saves a single global position shared by
all flows. Its position is stored as fractions of the available movement area,
restored after page recycling and app restarts, and reapplied within bounds when
the viewport changes size. Taps still navigate/save bookmarks and long presses
still replace the bookmark; moving the button does not activate either action.
A matching floating flip control starts 64dp below the bookmark's default
placement. Dragging it remembers its own global position independently of the
bookmark, including across flows, page recycling, resizing and app restarts.
It uses the existing current-card shortcut: the first tap opens a collapsed
card; subsequent taps flip its available reverse side using the physical flip
animation. Unavailable flips show an explicit message. Dragging or cancelling a
drag never opens or flips a card. Both controls fade with their owning Flow,
stay behind the inactive-pane overlay, and disappear for an empty card list.
The body starts below that strip rather than reserving a separate icon gap and
date line. Metadata and titles remain two separate rows. The strip has a 4dp top
margin, 28dp action buttons and no body top padding, placing titles about 32dp
below the card edge instead of 56dp. A one-line title row is about 24dp high.
Title-free body text retains its 4dp paragraph margin and starts about 36dp down.
Action glyphs remain 16dp with no inter-button gaps; actual button bounds are
reduced, not just their artwork. All callbacks remain intact. The title-speech
glyph is also 16dp in a 24dp target. Reading titles use 20sp without extra font
padding and still allow two lines. Body text/font padding is unchanged.
Overview type marks and the collapse glyph are
smaller without changing the collapse button's 48dp target.
Photographic reading cards keep a quieter version of the same glyph depth;
ordinary text and media retain their original reading style. Lighting preserves
custom text colors, fonts, ellipsis and accessibility text.
Resource and URI photographs share a worker-decoded background-only cache,
bounded to a 768px long edge. Filtered downsampling and normal bilinear display
provide mild source softening. A five-tap kernel adds 2dp central softening and
progressively increases it to 3.5dp in the side bands; there is no whole-card Gaussian
blur. Android 13+ uses one runtime-shader pass; bounded sampled strips provide
the same center and side treatment for software flip
snapshots and Android 12. People, objects and central detail remain recognizable.
Original media has separate cache
entries and no photo treatment. Lettering and controls remain sharp; original files
and the app wallpaper are unchanged.

Image and handwriting previews are miniature original card faces, not a thumbnail
beside a summary. They preserve the entire source aspect ratio, original colors
and rounded outline. Tall images become narrower rather than being cropped or
squeezed. Video previews use their cover image and a play indicator.
Reading images and handwriting are measured at their natural aspect ratio and
the complete card surface is uniformly fitted to the viewport height. Its outline
and action controls scale with the image instead of remaining on a wide, empty
frame. Media previews likewise scale as a whole during opening; their width and
height are never changed independently to fill the transitioning slot.

Images taller than twice their width have a width-fit reading viewport rather
than shrinking the whole long image to the screen height. Their compact overview
still shows the complete image. Opening increases the card's width while gradually
reducing the visible image window; preview and reading frames stay aligned without
stretching the source. Vertical drags inside the open image read its remaining
content. After reaching an edge, a fresh outward drag browses the card flow;
horizontal turns, taps, double taps and long presses retain their usual behavior.
Handwritten originals use the same viewport at card width whenever their page
exceeds the reading height, including both handwritten faces and image-card
handwritten reverses. Short pages remain fully visible. Overview thumbnails still
show the complete current face, and controls stay fixed outside the scrolled page.
Reading offsets are retained separately for each bound face through collapsing,
refreshes and turns; flip preparation copies those offsets and scrolling invalidates
cached snapshots. Ordinary images and video keep their existing whole-card fitting.

Text cards use the same gesture/face-offset controller when their full body exceeds
the available reading height. The body scrolls inside a bounded viewport while
the title, date, actions and photographic glass remain fixed. Short text still
hands vertical motion to the card flow. At a long body's top or bottom, a fresh
outward drag browses cards, just as with long images; horizontal motion still flips
the card. Front/back reading offsets survive turns, same-card refreshes and
collapsing/reopening, and reset when the holder binds a different card. These
viewport offsets are in-memory, matching existing media behavior.
Text-transition snapshots capture only the visible body window at its current
offset, not a reduced-resolution bitmap of the entire document. The existing
1536px/1,048,576-pixel limits, shared-glyph clips and material blending are retained.
Only visible matching glyphs join the overview transition.

Tap once to center and open a card; it grows and faces the reader while adjacent
cards move aside. Once open, scrolling enters continuous reading: the departing
card folds back into the ribbon while its neighbor unfolds in proportion to scroll
distance. Dragging back reverses the transition. On release (after any fling), the
nearest card snaps to the reading position, fully open. Expansion, perspective,
depth and neighbor spacing all interpolate without switching at the midpoint.
At the first and last cards, both overview and reading retain a bounded,
progressively resistant pull instead of a hard stop. Only the ribbon's visual
vertical offset changes: logical scroll, card count, expansion, perspective,
text wrapping and inner reading offsets stay at the endpoint. Displacement
approaches the smaller of 96dp and 12% of the viewport, so large pulls cannot
drag the cards offscreen. Reversing unwinds the pull before browsing inward.
Release and boundary flings return using the existing damped spring integration;
a new vertical drag catches the current rebound without a position jump.
Restoration, explicit selection and detachment clear this transient offset.
Only one photographic glass face is rendered throughout the transition: the
preview owns it until fully open, then the aligned reading background takes over.
The reading background and rim are suppressed while the preview owns the face,
avoiding the opacity/brightness pulse caused by overlapping two translucent copies.
Text-card root alpha stays constant with scroll depth; original media retains its
existing depth fade. The photographic face stays underneath the transition with aligned edges; overview
and reading lettering no longer fade through a blank frame. Visible matching
title and body glyph runs move on one continuous plane between the reading
and raised-preview layouts. Dates fade out/in at their separate header and
lower-face anchors rather than flying across the paragraph. This handles line wrapping without
reflowing text while dragging. Normal and raised glyph materials blend at the
same interpolated positions, preserving fonts, colors, Unicode shaping and
endpoint typography. Full-text characters not present in the four-line summary
fade away smoothly, with a soft fade ahead of the moving lower clip. Compact-only
ellipses fade in instead of replacing the entire paragraph.
Reversing the drag retraces that transition, including the former 18% boundary.
Both materials use font-relative bounds so their baselines stay aligned; line
clips prevent adjacent glyph rows from leaking into moving strips. Surplus
paragraph lines and ellipsized word suffixes stay on the original reading plane,
behind the moving shared glyphs, fading and clipping as the card recedes. They
do not inherit the summary's scaling, translation or line packing; only glyphs
retained in the summary join that continuous morph.
Each foreground strip has a cached, soft-edged footprint at its actual moving
position. It clears text behind it before its glyphs are drawn, so surplus prose
retreats locally instead of showing through the moving lettering. Reflow fragments
are composited back-to-front in reading order, with the title in front of the body:
crossing strips pass behind earlier text rather than adding unrelated glyphs
together. Cached ink bounds anticipate those contacts, fading whole rear fragments
and unmatched line segments before they collide, rather than cutting letters in
half. These bounds come from the shaped snapshots, including fallback glyphs and
custom fonts; the drag adds no text layout or rasterization. Horizontal strip clips stop at adjacent glyph advances to avoid carrying
pieces of neighboring characters into another line. Unmatched preview text and
ellipses also clear the text behind them as they appear, including when a scrolled
reading window no longer contains any of the summary's matching glyphs.

`CardTextTransition` caches transparent text-only snapshots while transitioning,
bounded to a 1536px edge and one megapixel per text view. Mapping and rasterization
are reused until text layout, dimensions, color or depth style change; caches are
released at endpoints, on detachment and before media reuse. A single transparent,
text layer bounded to the viewport plus overflowing preview/shared ink isolates
these occlusions from the photograph and glass.
The two materials of each matching run still blend additively at identical positions,
avoiding an opacity dip; different runs no longer accumulate their strokes.
Occlusion gradients are cached with the snapshots, with no new per-frame bitmap
or animation clock. Image/handwriting/video fitting,
gesture ownership, photographic treatment and card geometry are unchanged.
The first/last card remains open at the ends. Collapse or Back exits reading and
returns to overview scrolling. Existing
editing, flipping, receipt and action controls remain on the reading surface.
On a fully open card with a reverse side, drag horizontally to turn it around its
vertical axis. Both directions follow the finger, including reversing mid-turn.
Release past halfway, or flick toward the other face, to complete; a short slow
drag, opposite flick or cancelled gesture returns to the starting face. Vertical
gestures still browse the ribbon, and horizontal turns take priority over page
swiping only on eligible open cards. Video seeking keeps its own horizontal input.
Horizontal intent picks the card up with a small lift and 2.5% enlargement.
The contact position shifts its pivot, pitch and roll, while a restrained tether
follows the finger. Releasing transfers the measured turning velocity into a
damped spring, rather than starting a fixed-duration animation. A new touch can
catch the card during either its turn or landing without resetting its pose.
The ribbon's occupied height stays fixed while held; differing face heights
settle only after the turn approaches its destination. Tall backs reveal their
remaining content as that slot grows, without squeezing their images or lettering.
Lift falls slightly after rotation, with a soft shadow and quiet edge lighting.
Tap and Space use the same spring flip. Content and receipt ownership change only
after settling, not when crossing the edge-on midpoint. Prepared, bounded face
snapshots avoid image-loading gaps; back lettering is never mirrored.
Video pauses during a turn and uses its cover. System-disabled animations settle
immediately instead of running the release springs.
Action controls are unavailable on the moving snapshot and return after settling.
Videos can activate only on a fully open, settled card in the video flow; they
pause during reading scrolls and resume after settling. Space opens
the current card before flipping; arrows browse compact cards unless a card is
already open. Search results and bookmarks retain explicit navigation.

Scrolling keeps a stable logical overview width and applies depth through GPU
transforms instead of resizing and reflowing every card. Overview lettering and
reading content retain their own fixed layout widths while the physical face
scales during opening; line breaks no longer change continuously mid-gesture.
Text previews cache their intrinsic height at that fixed lettering width. Opening
and continuous reading resize only the photographic face instead of measuring
the same preview twice with alternating unspecified/exact heights on every frame.
Content, font, text-width and media-mode changes invalidate the intrinsic sizing.
The collapsed reading subtree is neither measured nor laid out. Laying out that
unmeasured subtree would consume pending remeasurement requests after a rebind or
an asynchronous image load, preserving zero-height images or a previous long
article's height. Opening measures its current content before its first layout.
Inactive and whole-image readers retain the latest viewport settings without
requesting another reading-card layout on each animation tick. Width-fit readers
still remeasure their viewport, including when a pending long image finishes loading.
At full expansion text reading faces are unscaled. Tall media reading faces retain
a uniform fit transform, including their controls and fingertip-turn geometry.
Visible text cards cache their photograph and raised lettering as separate
hardware layers, preserving scroll-linked text parallax. During reading
transitions only the stable reading card is cached, not the independently fading
collapse control. Caches are limited to the visible region plus a half-pitch
warmup margin and released offscreen, on detachment and before the fully open
interactive face. Video/image/handwriting faces do not use these raster caches.
Unchanged-size cards move through offsets rather than repeated child layout;
adjacent prefetch includes the next/previous row before the attached window advances.
Empty-area taps use inverse card transforms so hit boxes follow rendered size,
not the larger cached layout boxes.

On Android 13 and later photographic softening and edge transparency share a
lightweight direct image shader, without intermediate blur or masking surfaces
whenever a reading face changes size. A five-sample kernel adds 2dp softening in
the center and ramps to 3.5dp in the left/right fading strips. Android 12/12L and
software flip snapshots use the same sampling profile and horizontal alpha mask,
without an additional whole-face RenderEffect.
Non-fading softening retains its existing RenderEffect fallback.
Switching back to original media disables
both effects and releases shader references.

The default app wallpaper is an original vector interpretation of the luminous
Xperia-style wave reference in `bg.png`: a bright horizontal core, two smooth
translucent peaks, fine inner filaments and a soft surrounding glow. The source
screenshot, clock, launcher icons and other UI are not bundled or copied.
The upper and lower space fades smoothly into near-black. These dark regions
remain fully painted background, not letterboxing; card photographs are not darkened.
The drawer keeps global search directly accessible and groups the remaining
controls into four independently collapsible sections: Appearance, Reading &
sound, Reminders & sync, and Data management. All sections start collapsed on a
fresh launch. Expanded sections survive drawer closing and activity recreation,
without changing user preferences. Headers expose expanded/collapsed state and
matching accessibility actions. Existing controls and their behavior are retained;
clear/reset actions sit at the bottom of their sections.
Appearance offers blue, gold, purple, green and rose wallpaper thumbnails.
All five palettes share the same vector geometry, alpha and light core; only
RGB channels are remapped. Presets persist by stable string keys, not resource
numbers. Selecting a custom photo clears the preset highlight; resetting selects
blue. These choices do not replace the card-background image collection.
The static vector is cached by Android, scales to the window and drawer preview,
and has no animation timer or per-frame blur.
Window startup, the root background, drawer preview and reset action use the
same blue default. All preset-wallpaper toolbar and system-bar icons are light;
custom wallpapers retain the previous dark-icon appearance and are not replaced.

The selected app wallpaper fills the viewport using centered, uniform cover
scaling; excess image content is cropped rather than stretched or letterboxed.
Album decoding honors EXIF orientation and caps decoded dimensions to the viewport
long edge. The drawer thumbnail uses the same matte-free display image.
The transform updates whenever the root bounds change, including rotation and
split-screen resizing. Narrow, mostly black/transparent source matte bands are
removed from the display bitmap, allowing small screenshot status/navigation
glyph interruptions; all-dark images and regions exceeding 20% of an edge are
preserved. The selected URI and original album file are never modified.

Legacy automatically-focused state is ignored until saved with
`timescapeRibbonVersion: 1`; positions and content are preserved.

Flow labels use one long-press gesture for both sorting and merging; there is no
reorder-mode switch. Drag to a label edge or gap for an insertion marker, then
release to commit the new order. Hover over the middle of another label for
600ms to arm merging, with a highlighted target, haptic feedback and a release
hint. Releasing then opens the existing merge confirmation; it never merges
immediately. Moving away disarms the target. Releasing outside the label strip,
cancelling, refreshing the labels or leaving the activity changes neither order
nor content. Reordering also preserves the currently viewed flow by ID.
The label strip scrolls at its edges during a drag, without arming merges while
scrolling. Ordinary taps, double taps and moving individual cards between flows
retain their existing actions.

While moving an individual card, hold it within the outer 12% of either pager
edge for 650ms to switch to the adjacent flow. Brief edge crossings do not
switch. Preparation and the full existing wind animation must finish before a
new 650ms dwell starts, so holding an edge advances deliberately one flow at a
time rather than chaining rapid switches. RTL mirrors the physical edges.
Leaving the edge, dropping, cancelling, rebuilding labels or stopping the
activity clears the pending dwell. A pager-area drop during a transition goes
to its requested destination without cutting the animation short; dropping on
an individual flow label still uses that label's explicit destination.

The flow switcher is a compact, bottom-centered floating dock, sized to its
contents with a 600dp maximum width. Its translucent blue-glass strip and fine
horizontal highlights fade to transparent at both sides, matching `cards.png`,
without an opaque capsule, perimeter stroke or elevation shadow. White labels
sit above the wallpaper; only the selected flow has a sliding translucent highlight.
Other labels are unfilled, with 48dp touch targets and ellipsized long names.
An icon-only, accessibility-labeled grid button opens All flows, separated from
the scrolling labels. The label viewport always fades both physical edges to
transparent, including short lists, scroll endpoints and RTL; the library button
and floating drag hint stay outside that mask. On tablets (smallest width
at least 600dp) the dock stays available in a quieter resting state; phones retain
temporary visibility. Interaction restores full emphasis.
During sorting, neighboring labels gently make room around the insertion marker.
A capsule-shaped progress ring fills over the target during the exact 600ms merge
dwell, then highlights and confirms haptically. Short hints float above the dock
without changing its height or the touch coordinates. The held label lifts above
the finger on a readable blue-glass surface with its own soft shadow, leaving the target and progress visible.

Single-flow horizontal switching uses individual wind-carried cards rather than
a rigid sliding/scaling page. The pager's continuous position drives each card's
horizontal travel, curved vertical drift, pitch/yaw/roll, slight depth shrink and
fade. Stable card IDs and their row positions determine distinct paths and pivots;
there is no per-frame randomness or independent per-card animator. Reversing the
finger retraces the same paths, while incoming cards rotate back onto their own
flow's existing layout. Native animation matrices and whole-card group opacity compose
with, rather than overwrite, the vertical ribbon's transforms and opacity.
Pre-draw applies the latest gesture progress to newly attached/relaid-out cards.
Settling, cancellation, recycling and detachment clear temporary transforms;
reading positions, expanded faces, text wrapping and image fitting are unchanged.
The wallpaper and Flow dock stay fixed. Label/keyboard smooth navigation uses the
same transition. `FlowPagerMotionController` waits for the first incoming Flow's committed
card list, measured layout and tracked preview/reading images before starting an
approximately 500ms eased scroll. A one-pixel pager prime commits the actual
initial wind frame before starting that clock, including first-use GPU work.
Preparation proceeds through the existing bounded adjacent-page window, checking
each incoming page before advancing rather than attaching every intervening Flow.
Hidden pages keep their native positions so transformed bounds do not prevent
RecyclerView from recycling them.
The offscreen-page limit remains unchanged. Image failures retain the existing
explicit loader warnings; preparation lasting over
1500ms logs a warning and continues with available content rather than leaving
navigation blocked. Direct finger movement still uses the normal pager gesture;
native fling targeting is retained, with a distance/velocity-aware 200–350ms
ease-out after release. Slow releases use the same paced snap. The driver uses the
pager's public fake-drag API, advances on vsync and caps each step to 12% of a page.
Only the initial prime and final landing wait for a hardware frame commit; normal
steps do not serialize the UI animation behind every GPU submission. Wind poses
are applied during scroll updates, with pre-draw only correcting newly attached
or relaid-out cards, avoiding redundant redraws of the same pose.
Late callbacks cannot skip directly to the destination; distant jumps take longer without the
default pager's invisible prepositioning. Pending labels highlight immediately,
while the final selected Flow and its stored reading state remain pager-owned.
New requests, touch takeover, data changes and immediate restoration cancel
pending preparation. Repeated requests for the same pending target do not restart
it, and activity stop aligns the requested Flow before saving.
Wind matrices and lettering projections are not reapplied when all wind inputs
are unchanged, so holding a gesture does not continuously invalidate the scene.
Text-layout ink bounds are cached until layout/text changes rather than repeatedly
reshaping the same lines; scroll offsets and world projections remain live.
Static compact faces retain their native hardware caches during wind; expanded
text faces temporarily cache the photograph, reading text and controls together
at their actual view size. Standing preview lettering stays in its independent
cache and its projection remains live. Text-card fade uses a single canvas group
with canvas group alpha, instead of native transition alpha,
which otherwise rebuilds a full perspective-card RenderNode layer on each
lettering move. This keeps group opacity rather than fading overlapping children
separately. Media cards have no moving lettering projection, so they temporarily
cache the whole stable image/handwriting card and keep native transition alpha.
Video cards retain uncached native rendering because their live `VideoView`
surface cannot be captured in a whole-card raster cache. Lifted flips keep
native opacity and do not cache the whole media card, preserving overflowing
flip surfaces. Fully transparent
text cards skip drawing; media display lists still record their original content
so an initially invisible incoming cached image never remains blank.
Blur, source images, opacity and geometry remain unchanged. Completion, rebinding,
recycling, cancellation and detachment release temporary caches and restore normal
interactive rendering.
Immediate state restoration stays immediate. Two-flow landscape
keeps its existing simultaneous-pane presentation and clears any wind transforms.
`FlowWindMotionTest` checks deterministic, continuous and mirrored trajectories.
`FlowWindTransitionTest` exercises the real pager, touch dispatch from empty space,
reversed drags, interrupted smooth navigation, RTL, saved reading positions and
cleanup without extra card measurements.
It also checks actual click/release timing, delayed image readiness, bounded
distant navigation in both directions, touch takeover, repeated requests,
lifecycle alignment, native cached/original pixel equivalence, cache restoration
and the absence of redraws for an unchanged wind pose. It checks actual committed
intermediate poses and a 450–1000ms single-page click budget in the native fixture,
including the faded/scaled reading-card source used during a card move,
not only scroll callbacks, plus unchanged image pixels and fitting during media wind.
Native reference screenshots guard whole-card opacity, allowing only small
edge-antialiasing differences between layer-local and device-space compositing.

`TimescapeFlowTest` covers full-bleed backgrounds, text depth, original image
proportions, actual tap/collapse, forward/reverse reading transitions and snapping,
reading boundaries, interrupted selection, recycling, receipts and short viewports
with large text in an isolated debug host. It also measures receding pitch, the
single stack bend, slight overlap across varying card heights, monotonic incoming
lower-edge expansion, bounded coverage of tall viewports, offset dates, metadata/body
separation, compact icon dimensions and unchanged action targets/callbacks.
Collapsed long-to-short and text-to-image rebinds check first-opening text height,
height stability across two turns, and actual software/native image pixels.
`CardEdgeDrawableTest`
checks the lit rim/dark lip contrast, transparent face, rounded corners, resize
and alpha behavior. Portrait, landscape, handwriting and
long-image fixtures also check uniformly transformed outlines/actions, matching
preview/reading geometry and rejection of the empty space beside fitted media.
`GestureCardFlipTest` exercises real touch dispatch through a competing horizontal
parent, velocity/progress settling, cancellation, rebinds, vertical browsing,
loaded handwriting/image backs, blank backs and receipt-height transitions.
`FlowDragSessionTest` covers insertion geometry, RTL order and the exact 600ms
merge threshold. `FlowChipDragTest` uses native touch/drag delivery with the real
label-strip layout in an isolated host, including cancellation, confirmation,
edge scrolling and unchanged tap behavior. It also checks glass/edge alpha,
both-side fades for short and overflowing LTR/RTL strips, and lifted-label contrast.
`PhotographImageViewTest` compares hardware pixels against the prior photographic
softening kernel and measures 2dp central softening with an impulse response,
unchanged opacity, opaque top/bottom, subtle
left/right-only transparency without an oval silhouette,
hardware/software alpha agreement, effect reset and unmodified media snapshots.
`WallpaperDrawableTest` checks portrait/landscape centered coverage, changed bounds,
matte removal with screenshot glyphs, preservation of dark content and album-file
integrity/EXIF orientation.
`FlowWallpaperPresetTest` checks the five stable palette keys, unchanged vector
geometry/alpha, independent drawable instances and full portrait/landscape coverage.
`CosmicFlowBackgroundTest` checks default startup/root/drawer consistency and the
dark framing around the luminous wave.
`DrawerSectionsTest` checks collapsed defaults, independent expansion, control
grouping, retained values/actions, recreation state and accessibility actions.
`PhotographBackgroundBlurTest` checks measurable fine-detail suppression, color and
aspect preservation, transparency, bounded dimensions, separate media cache entries
and unchanged source files.
`CardFlowPerformanceTest` checks stable logical widths/text layouts, bounded
measurement/recording work, raster-cache release and early bidirectional prefetch.
The continuous text renderer caches fixed glyph corners and halo rectangles with
its snapshot runs, reuses its active-face list and refreshes hierarchy matrices
once immediately before drawing rather than during both layout and drawing.
Shared-glyph and masked-paragraph draws skip zero-opacity materials. Snapshot resolution, glyph shaping,
the two separate perspective clips, additive material blending and all glass
effects remain unchanged.
Its two profiling cases use 40 photographic text cards, 60 warmup frames and 240
scrolling frames in an isolated host, without modifying saved cards:

```sh
adb shell am instrument -w \
  -e class com.example.timescapedemo.CardFlowPerformanceTest \
  -e profileLabel device \
  com.example.timescapedemo.test/androidx.test.runner.AndroidJUnitRunner
```

Results and hardware screenshots are written to app cache as
`card-flow-device-overview` / `card-flow-device-reading` (`.json` and `.png`).
Reports include scroll CPU time, measurement/text-recording counts, animation
vsync intervals and hardware frame/render/GPU timings. Vsync intervals describe
animation cadence; FrameMetrics total/deadline values include pipeline timing
and must not be read directly as handset FPS. Ordinary regression checks avoid
hardware-specific FPS thresholds. The optional instrumentation argument
`profileDisablePreviewEffects=true` isolates photographic preview softening for
A/B diagnosis; it is not a production setting.

The shared-glyph reversal and Unicode tests optionally accept
`textReferenceMode=record|compare`. Record on the unchanged renderer first, then
compare on the same device after installing an optimized APK without clearing
app data. References in app cache include the tilted text plane and use exact
pixel equality; ordinary tests need no recorded files. Repeat timing comparisons
with an interleaved original APK control: emulator variance can exceed these
small computation savings, so a single faster run does not establish a frame-rate
improvement.
