# Circaso logo

The mark is the one on circaso.com: a disc with a ring cut out and an opening to the right (a speech bubble). `bun run build.ts` rewrites everything in `logo/` (needs Chrome for the PNGs; set `CHROME` to another binary if it is elsewhere).

| Use | File |
|---|---|
| Emails, anything on a light background | `circaso-logo-black-480.png` (shown at 160 px wide, so it is sharp on phones) or `circaso-mark-black-512.png` |
| On a dark background | `circaso-logo-white-480.png` (or `-1200`) or `circaso-mark-white-512.png` |
| Avatar, link preview, app store, favicon | `circaso-icon-1024.png`, `-180.png`, `-64.png` |
| The metallic mark from the website's header | `circaso-mark.svg`, `circaso-mark-1024.png` |
| Print or the web, scalable | the `.svg` files |

The PNGs have transparent backgrounds. Use PNG in email (most mail apps do not draw SVG). The `circaso-logo-*.svg` files draw the name as text in the system font, so on a machine without it the letters may differ; the PNGs are drawn from the same file and are exact.

Keep clear space around the mark of at least a quarter of its width, do not recolour it beyond black, white and the silver version, and do not stretch it.

## Asha

Asha's icon is Circaso's ring with the same opening, and a four-point spark inside it, on a circle. The default is the silver one (`asha-icon-silver`, matching the metallic mark on the website), for the chat avatar and the welcome email. The others are `asha-icon` (black, with white details) and `asha-icon-white` (for dark backgrounds, with a hairline edge). They live in `logo/asha/` as SVG and as PNG at 1024, 192 and 96 px (silver also at 512); use 96 for a chat avatar shown at 48 px. On R2 they are under `brand/asha/`.
