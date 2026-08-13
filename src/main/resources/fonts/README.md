# Bundled system fonts (thumbnail engine — FEATURE_2608_05)

`SystemFontSeeder` seeds a shared system `FontAsset` (`tenantId=null`, `source=BUNDLED`,
`storageKey=fonts/system-sans.ttf`, `familyKey=SansSerif`) on startup.

Ships **Nanum Gothic Regular (OFL)** as `system-sans.ttf` — full modern-Hangul coverage
(all 11,172 syllables) plus Latin/digits/symbols, so thumbnails render Korean product and
brand names without tofu (□). `FontRegistry` loads it via `Font.createFont(TRUETYPE_FONT, ...)`
automatically; no code change. See `OFL.txt` for the license.

> If no binary is present, `FontRegistry` falls back to the JDK logical font named by
> `familyKey` (`SansSerif`), which on a slim server JRE has **no CJK glyphs** → Hangul tofu.
> Keeping a real CJK TTF here at `fonts/system-sans.ttf` is what prevents that.

## To replace the thumbnail font
1. Drop an **OFL / redistributable** TTF here as `system-sans.ttf`. It must cover the full modern
   Hangul syllable block (product names are arbitrary Korean — a subset renders partial tofu).
   Alternatives: Pretendard, Noto Sans KR. Use a genuine **TTF** (OTF may fail `TRUETYPE_FONT` load).
2. Keep the seeded `storageKey`/`familyKey` in sync (or add another `SystemFontSeeder` entry).
3. Commit the font's own license file alongside it (OFL requires redistribution terms).

⚠️ Only add fonts you have the license to redistribute (OFL permits it). Tenant-uploaded fonts go
to storage (`source=UPLOADED`), not here.
