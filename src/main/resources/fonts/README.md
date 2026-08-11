# Bundled system fonts (thumbnail engine — FEATURE_2608_05)

`SystemFontSeeder` seeds a shared system `FontAsset` (`tenantId=null`, `source=BUNDLED`,
`storageKey=fonts/system-sans.ttf`, `familyKey=SansSerif`) on startup.

Until a real font binary exists at `fonts/system-sans.ttf`, `FontRegistry` falls back to the JDK
logical font named by `familyKey` (`SansSerif`), so rendering works everywhere.

## To ship a proper thumbnail font
1. Drop an **OFL / redistributable** TTF here as `system-sans.ttf` (e.g. Pretendard, Noto Sans KR).
   Korean product names need CJK glyph coverage — a Latin-only font renders tofu (□) for Hangul.
2. Keep the seeded `storageKey`/`familyKey` in sync (or add another `SystemFontSeeder` entry).
3. `FontRegistry` will `Font.createFont(TRUETYPE_FONT, ...)` it automatically; no code change.

⚠️ Only add fonts you have the license to redistribute (OFL permits it). Tenant-uploaded fonts go
to storage (`source=UPLOADED`), not here.
