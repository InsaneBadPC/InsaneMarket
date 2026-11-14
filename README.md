# InsaneMarket

Moderní Android katalog aplikací, webových aplikací a GitHub repozitářů (Kotlin, Material 3) pro účet **InsaneBadPC** (výchozí, lze změnit v Nastavení).

## Hlavní funkce a sekce
- **3 hlavní sekce:**
  1. **📱 APK** — Všechny Android aplikace z účtu (z GitHub Releases, pre-releases i přímo z `.apk` souborů v repozitáři) + možnost ručně přidat vlastní URL nebo repozitář. Pokud má aplikace i Web App, zobrazuje se u ní odznak **🌐 WEB APP** a tlačítko **Otevřít Web**.
  2. **🌐 Web App** — Všechny webové aplikace z účtu (GitHub Pages, `homepage`, detekované webové aplikace v repozitáři) + možnost ručně přidat libovolnou URL webové aplikace. Pokud má webová aplikace i APK, umožňuje rovnou stáhnout nebo spustit APK.
  3. **📦 Cizí** — Možnost přidat libovolný cizí GitHub repozitář zadáním jeho URL (`https://github.com/owner/repo` nebo `owner/repo`). Vytvoří kartu s názvem, ikonou a popisem repozitáře a po rozkliknutí otevře **Průzkumník repozitáře** (Přehled & README, interaktivní prohlížeč **Souborů**, seznam **Vydání / Releases** se stahováním a **Pages / Web**).
- **Hodnocení 1 až 5 hvězdiček (★) a řazení:**
  - Každou položku ve všech třech sekcích lze přímo na kartě ohodnotit **1 až 5 ★**.
  - Všechny sekce automaticky řadí položky **podle nejlepšího hodnocení** (od 5 ★ po nehodnocené, následně podle aktualizací a data vydání).
- **Automatické načítání ikon a log:**
  - Automaticky hledá v repozitáři soubory `icon.png`, `logo.png`, `icon.webp`, `app-icon.png` (v kořeni, `assets/`, `img/`, `public/`, v release assets nebo v Android `mipmap-*/ic_launcher.png`), případně použije ikonu nainstalované aplikace nebo vygeneruje moderní monogram.
  - Do libovolného repozitáře stačí přidat `icon.png` nebo `logo.png` a market ho automaticky zobrazí.
- **Podpora veřejných i soukromých repozitářů:**
  - S nastaveným GitHub tokenem automaticky načítá veřejné i soukromé repozitáře (`/user/repos`) a stahuje APK ze soukromých releases.
- **Skrývání položek:**
  - Každou položku lze jedním klepnutím **Skrýt**, aby se nezobrazovala v hlavním seznamu. Skryté položky lze kdykoli zobrazit a obnovit přes filtr **Skryté** nebo v menu **Zobrazit všechny skryté**.

## Jak přidat ikonu nebo metadata do repozitáře
- **Ikona / logo:** Přidej do kořene repozitáře (nebo do release) soubor `icon.png` nebo `logo.png` (případně URL nastav ručně v **Upravit**).
- **Volitelný soubor `insanemarket.json` (nebo `market.json` / `version.json`):**
  ```json
  {
    "displayName": "Moje aplikace",
    "packageName": "cz.insanebadpc.mojeaplikace",
    "description": "Volitelný popis",
    "webUrl": "https://insanebadpc.github.io/moje-aplikace/",
    "iconUrl": "https://raw.githubusercontent.com/InsaneBadPC/moje-aplikace/main/icon.png"
  }
  ```

## Build
```bash
gradle testDebugUnitTest
gradle assembleDebug
```
