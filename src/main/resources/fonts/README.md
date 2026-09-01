# Bundled system fonts (thumbnail engine + detail @font-face)

`SystemFontSeeder` seeds one shared system `FontAsset` per entry in its `SYSTEM_FONTS` list
(`tenantId=null`, `source=BUNDLED`, `storageKey=fonts/<file>.ttf`) on startup, then uploads each
binary once to shared storage so detail pages can emit an `@font-face` (`webUrl`).

Bundled fonts (all **SIL OFL 1.1**, licenses in `licenses/`):

| familyKey | file | Hangul coverage | note |
|---|---|---|---|
| `SansSerif` | `system-sans.ttf` (Nanum Gothic) | 11,172 / 11,172 | default template font — do not replace with a partial face |
| `Pretendard` | `pretendard.ttf` | 11,172 / 11,172 | body sans |
| `NanumMyeongjo` | `nanum-myeongjo.ttf` | 11,172 / 11,172 | serif |
| `BlackHanSans` | `black-han-sans.ttf` | **2,581 / 11,172** | display only |
| `Jua` | `jua.ttf` | **2,367 / 11,172** | display only |
| `DoHyeon` | `do-hyeon.ttf` | **2,437 / 11,172** | display only |

> ⚠️ The three display faces cover roughly a fifth of the modern Hangul syllable block. Product names
> are arbitrary Korean, so an uncommon syllable renders as tofu (□). They are fine as a deliberate
> title font but must never become the default template font — that stays `SansSerif`.

## To add a font
1. Drop an **OFL / redistributable** TTF here, lowercase-hyphen filename. Use a genuine **TTF**
   (an OTF renamed to `.ttf` may fail `Font.createFont(TRUETYPE_FONT, ...)`; the seeder test catches it).
2. Commit its license as `licenses/<Font>-OFL.txt` (OFL requires redistribution terms).
3. Add one `SystemFont` entry to `SystemFontSeeder.SYSTEM_FONTS`.
4. Redeploy — the row is seeded and the binary promoted to a public URL on the next boot (idempotent).

⚠️ Three traps for the next person:
- **Do not change an existing `familyKey`.** It is the seed lookup key; a new value creates a second
  row instead of updating the existing dev/prod one.
- **`storageKey` stays a classpath path.** `FontRegistry` loads BUNDLED binaries through it; an S3 URL
  there falls back to a JDK logical font and breaks CJK thumbnail rendering. Public URLs go in `webUrl`.
- **A declared font with no bundled binary is skipped, not seeded.** Seeding it anyway would put a
  dropdown entry in front of users that silently renders in a different face.

⚠️ Only add fonts you have the license to redistribute — `@font-face` republishes the binary to every
buyer. Tenant-uploaded fonts go to storage (`source=UPLOADED`), not here.
