# Timescape card flow

The default view is a ribbon of compact card faces, inspired by `timescape.jpg`.
Cards recede in size, shift along a gentle S-curve, and use restrained perspective:
8-10 degrees of X tilt, 1.5-4.5 degrees of Y tilt and at most 0.6 degrees of roll.
Edges stay nearly horizontal; size, overlap and luminous rims carry the depth
instead of pronounced card skew. All three angles ease to zero when opened.
The receding ribbon initially bends left; horizontal tilt and text parallax follow
the same mirrored direction without mirroring the image or lettering.
The upper cards overlap the next ones; depth changes continuously while scrolling.
Overview scrolling never automatically expands a card.

Text previews put the original background photograph behind a light-weight
title, two summary lines and a timestamp. Mild desaturation/softening, directional
blue scrims and a luminous thin rim integrate the image without erasing its subject.
Lettering is a separate sibling plane, not clipped inside the photograph. It
slightly overhangs the leading edge and sits above the face with scroll-linked
parallax. It counter-tilts against the card's backward lean, pivoting from its lower
edge to face slightly toward the viewer, then eases to neutral as the card opens.
Glyphs have crisp illuminated faces, a shallow cool bevel and a detached
cast shadow, rather than a large blur or stronger card rotation. Summaries retain
the same compact size with or without a title, two sp below the reading preference;
titles are only modestly larger (16sp summaries, 20sp titles and 11sp timestamps
at the default setting). The edge inset eases
away during opening so preview and reading faces remain aligned.
Photographic reading cards keep a quieter version of the same glyph depth;
ordinary text and media retain their original reading style. Lighting preserves
custom text colors, fonts, ellipsis and accessibility text.
Background URI requests target the viewport width
(up to 1280 pixels) instead of the old tiny, heavily blurred text-card background.

Image and handwriting previews are miniature original card faces, not a thumbnail
beside a summary. They preserve the entire source aspect ratio, original colors
and rounded outline. Tall images become narrower rather than being cropped or
squeezed. Video previews use their cover image and a play indicator.

Tap once to center and open a card; it grows and faces the reader while adjacent
cards move aside. Once open, scrolling enters continuous reading: the departing
card folds back into the ribbon while its neighbor unfolds in proportion to scroll
distance. Dragging back reverses the transition. On release (after any fling), the
nearest card snaps to the reading position, fully open. Expansion, perspective,
depth and neighbor spacing all interpolate without switching at the midpoint.
The photographic face stays underneath the transition with aligned edges; overview
lettering fades out before reading text appears, avoiding translucent duplicate cards.
The first/last card remains open at the ends. Collapse or Back exits reading and
returns to overview scrolling. Existing
editing, flipping, receipt and action controls remain on the reading surface.
Videos can activate only on a fully open, settled card in the video flow; they
pause during reading scrolls and resume after settling. Space opens
the current card before flipping; arrows browse compact cards unless a card is
already open. Search results and bookmarks retain explicit navigation.

Legacy automatically-focused state is ignored until saved with
`timescapeRibbonVersion: 1`; positions and content are preserved.

`TimescapeFlowTest` covers full-bleed backgrounds, text depth, original image
proportions, actual tap/collapse, forward/reverse reading transitions and snapping,
reading boundaries, interrupted selection, recycling, receipts and short viewports
with large text in an isolated debug host.
