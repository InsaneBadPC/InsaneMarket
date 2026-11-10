# InsaneMarket

Nativní Android katalog aplikací (Kotlin, Material 3) pro veřejné repozitáře z GitHub účtu **InsaneBadPC** (výchozí, lze změnit v Nastavení).

## Co umí
- Projde repozitáře účtu přes GitHub API (stránkování, paralelně, s mezipamětí) a zobrazí jen repozitáře, jejichž latest release obsahuje APK.
- Karta aplikace: název, repo, verze, velikost APK, popis, stav instalace/aktualizace, datum vydání.
- **Stáhnout / Aktualizovat / Reinstalovat / Otevřít**, detail s release notes, ruční úprava názvu a popisu.
- Hledání a filtry **Vše / Aktualizace / Nainstalované**.
- Stahování běží přes WorkManager na pozadí: přežije zavření appky, umí pokračovat po přerušení (HTTP Range), ukazuje průběh v oznámení a po dokončení nabídne instalaci.
- Pokud GitHub u assetu uvádí `digest`, stažené APK se před instalací ověří (SHA-256).
- Rate limit se zobrazuje a chyby se už netiše nepolykají.

## Jak se pozná nainstalovaná aplikace
Párování je **výhradně přes přesný package name**. Žádné hádání podle názvu repozitáře.

Zdroj package name, v tomto pořadí:
1. dříve naučené mapování z instalace přes InsaneMarket,
2. asset `insanemarket.json` (nebo `market.json`) v release:
   ```json
   {
     "displayName": "Moje aplikace",
     "packageName": "cz.insanebadpc.mojeaplikace",
     "description": "Volitelný popis"
   }
   ```
3. ruční přiřazení v detailu aplikace („Přiřadit nainstalovanou aplikaci“).

Bez jednoho z těchto zdrojů appka nainstalovanou aplikaci **nebude tvrdit** — radši ukáže „Přiřadit“.

## Jak se pozná aktualizace
Update se hlásí jen tehdy, když ho lze doložit:
- je známý `versionCode` staženého APK a nainstalovaná verze je nižší, nebo
- je zaznamenaný tag, který se přes InsaneMarket nainstaloval, a latest release má jiný tag.

Aplikace nainstalovaná mimo InsaneMarket se tedy může tvářit jako „bez aktualizace“, dokud ji jednou nenainstaluješ přes market (nebo dokud se nedoplní metadata). To je záměr: žádné falešné poplachy.

Pokud je nainstalovaná aplikace podepsaná **jiným klíčem** než stahované APK, InsaneMarket to před instalací zjistí, upozorní a instalaci nezahájí. Řešením je aplikaci odinstalovat a nainstalovat znovu (typicky jednorázový přechod ze starého `debug-latest`).

## Build
```bash
gradle testDebugUnitTest
gradle assembleDebug
```

Release podepsaný stabilním klíčem:
```bash
export ANDROID_KEYSTORE_FILE=/cesta/insanemarket-release.jks
export ANDROID_KEYSTORE_PASSWORD=...
export ANDROID_KEY_ALIAS=insanemarket
export ANDROID_KEY_PASSWORD=...
export VERSION_CODE=42
export VERSION_NAME=0.3.42
gradle assembleRelease
```

## CI/CD
`.github/workflows/android.yml` běží na push, PR a ručně:
1. spustí unit testy,
2. sestaví release APK,
3. pokud jsou nastavené signing secrets, ověří otisk podpisového certifikátu,
4. na `main` publikuje normální (ne prerelease) GitHub release `app-v<verze>`, takže ho vidí i `/releases/latest`.

### Povinné signing secrets
Bez stabilního klíče by každý CI build vygeneroval jiný debug keystore a nová verze by nešla nainstalovat přes předchozí. Proto `main` push bez secrets schválně selže.

| Secret | Význam |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | obsah `.jks` zakódovaný do base64 |
| `ANDROID_KEYSTORE_PASSWORD` | heslo keystore |
| `ANDROID_KEY_ALIAS` | alias klíče (zde `insanemarket`) |
| `ANDROID_KEY_PASSWORD` | heslo klíče |

Nastavení přes GitHub CLI:
```bash
base64 -w0 insanemarket-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R InsaneBadPC/InsaneMarket
gh secret set ANDROID_KEYSTORE_PASSWORD -R InsaneBadPC/InsaneMarket --body "$(cat insanemarket-release.pass)"
gh secret set ANDROID_KEY_ALIAS -R InsaneBadPC/InsaneMarket --body insanemarket
gh secret set ANDROID_KEY_PASSWORD -R InsaneBadPC/InsaneMarket --body "$(cat insanemarket-release.pass)"
```

Očekávaný otisk certifikátu (SHA-256) je v workflow v `EXPECTED_CERT_SHA256`:
`90961BE18E9C1575B5DEF00C9CB50E154EA8EABC45338F499661070730FC0463`

Při prvním přechodu ze starého `debug-latest` (jiný klíč) je potřeba aplikaci jednou odinstalovat.

## Bezpečnost
- Token se ukládá jen lokálně, `allowBackup="false"` (nedostane se do cloudu).
- Token se posílá **pouze** na `api.github.com`, nikdy na download URL.
- Privátní repozitáře se do katalogu neberou.
- APK se před instalací ověří kontrolním součtem, pokud ho release nabízí.

## Omezení
- GitHub API bez tokenu dovolí jen 60 požadavků/hodinu; s tokenem 5000. Pro plný katalog je token prakticky nutný.
- `insanemarket.json` musíš přidat do release sám; appka ho nevytváří za ostatní repozitáře.
- Instalace vyžaduje povolení „instalovat z neznámých zdrojů“ pro InsaneMarket.
