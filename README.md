# InsaneMarket

Nativní Android app market (Kotlin, Material 3) pro veřejné aplikace z GitHub účtu **InsaneBadPC** (výchozí, lze změnit v Nastavení).

## Funkce
- Při spuštění a při obnovení projde repozitáře účtu přes GitHub API.
- Do seznamu bere **jen aplikace**: latest release musí obsahovat `.apk` asset. Ostatní repozitáře nezobrazuje.
- Karty aplikací: název, repo/jazyk, verze, velikost APK, popis, stav instalace/aktualizace.
- Akce: **Stáhnout a instalovat / Aktualizovat / Reinstalovat**, **Otevřít** nainstalovanou app, **Detail**, **Upravit** název/popis.
- Vyhledávání a filtry: **Vše / Aktualizace / Nainstalované**.
- Stavy UI: načítání, progress stahování, prázdný stav, chyba.
- Lokální nastavení: GitHub účet a volitelný token (pro vyšší API limity), ukládáno jen v zařízení.
- Lokální metadata override: ruční název/popis se ukládá lokálně a přepíše údaje z GitHubu.

## Aktualizace
- Nainstalovaná app se detekuje heuristicky podle názvu repozitáře/balíčku.
- Nová verze se hlásí, když se latest release tag liší od naposledy zaznamenaného tagu po stažení/instalační akci.

## Build
```bash
gradle assembleDebug
```

## CI/CD
`.github/workflows/android.yml` běží na každý push (všechny větve), PR a ručně: sestaví debug APK, nahraje artifact; na `main` aktualizuje prerelease `debug-latest`.

## Bezpečnost
- Žádné secrets v repozitáři. Token se zadává ručně v aplikaci a ukládá lokálně.
