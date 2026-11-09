# InsaneMarket

Nativní Android aplikace (Kotlin) – osobní market pro aplikace z GitHub účtu **InsaneBadPC**.

## Co dělá
- Při každém spuštění (a ručně tlačítkem Obnovit) projde veřejné repozitáře `InsaneBadPC` přes GitHub API.
- U každého repozitáře zkusí načíst **latest release** a najít `.apk` asset.
- Zobrazí seznam aplikací: název, repo, verzi/tag, popis, stav instalace/aktualizace.
- **Instalovat/Aktualizovat** stáhne APK z release a otevře systémový instalátor (vyžaduje povolení instalace z aplikace).
- **Upravit** umožní ručně změnit název a popis; změny se ukládají lokálně v zařízení (SharedPreferences) a přepisují data z GitHubu.

## Detekce aktualizací
- „Nová verze“ = latest release tag se liší od naposledy staženého/označeného tagu pro daný repozitář, nebo release existuje a stav ještě není synchronizovaný.
- Detekce nainstalované aplikace je heuristická podle názvu repozitáře/balíčku; pro přesné párování lze později doplnit metadata soubor `insanemarket.json` v release assetech nebo mapování package name.

## GitHub API limity
- Ve výchozím stavu běží bez tokenu (veřejné API, limit ~60 req/hod na IP).
- Token lze doplnit později do lokálního úložiště (připraveno v `AppStateStore`); do repozitáře se nikdy neukládá.

## Build
```bash
gradle assembleDebug
```
Výstup: `app/build/outputs/apk/debug/*.apk`

## CI/CD
Workflow `.github/workflows/android.yml` se spouští při **každém push** (všechny větve), PR a ručně. Sestaví debug APK a nahraje artifact. Při push do `main` navíc aktualizuje rolling prerelease `debug-latest` s APK.

## Poznámky k nasazení
- Repozitáře se aplikacemi musí publikovat APK jako asset v GitHub Release (latest).
- Aplikace zobrazuje i repozitáře bez APK, ale akce instalace je neaktivní.
