# Gates: InsaneMarket — plná oprava jádra

OWNS: `app/**` `.github/**` `README.md` `GATES.md`

## Kritické bugy, které se opravují

1. Každý CI build měl jiný podpis (nový debug keystore na runneru). Ověřeno porovnáním certifikátů dvou APK: `86a1760d…` vs `4997b881…`. Nová verze nešla nainstalovat přes předchozí.
2. GitHub API: 1 request na repo + metadata, sekvenčně, bez cache; chyby 403 se v `fetchAppRelease` polykaly.
3. Detekce instalace hádala podle substringů názvu repozitáře a balíčků → falešné pozitivy.
4. Update se rozhodoval podle lokálně uloženého „seen“ tagu, ne podle nainstalované verze; `markSeen` běžel před dokončením instalace.
5. Stahování viselo na Activity, bez pozadí, resume a ověření obsahu.

## Gates

- [x] G1: unit testy projdou (`gradle testDebugUnitTest`)
  CHECK: `gradle testDebugUnitTest`
  EXPECT: BUILD SUCCESSFUL, 0 failed
  EVIDENCE: lokálně 10. 11. 2026 — kotlinc 2.0.21 proti `android.jar` 35, JUnitCore: `OK (15 tests)`; CI běh se zopakuje po pushi

- [ ] G2: release APK je podepsané stabilním klíčem
  CHECK: CI krok `Verify signing certificate` porovná SHA-256 certifikátu s `EXPECTED_CERT_SHA256`
  EXPECT: shoda
  EVIDENCE: pending (běží v CI)

- [x] G3: žádné fuzzy párování balíčků
  CHECK: `rg -n "contains\(n\)|substringAfterLast|guessPackage" app/src/main/java` nevrátí nic v párování instalací
  EXPECT: žádný výskyt
  EVIDENCE: lokálně 10. 11. 2026 — `rg` bez výskytu; CI zopakuje

- [x] G4: token se neposílá na download host
  CHECK: `rg -n "Authorization" app/src/main/java` — jediný výskyt je v `GitHubMarket.get` za podmínkou `host == api.github.com`
  EXPECT: žádný `Authorization` v `DownloadWorker`
  EVIDENCE: lokálně 10. 11. 2026 — jediný výskyt `GitHubMarket.kt:175`; CI zopakuje

- [ ] G5: `main` bez signing secrets neselže tiše na špatný podpis
  CHECK: workflow krok `Require signing on main`
  EXPECT: při chybějícím `ANDROID_KEYSTORE_BASE64` build na `main` selže s vysvětlením
  EVIDENCE: pending

- [ ] G6: build a release
  CHECK: `gradle assembleRelease` + publish `app-v<verze>` na `main`
  EXPECT: APK v release, `releases/latest` ho vrací (není prerelease)
  EVIDENCE: pending (běží v CI)
