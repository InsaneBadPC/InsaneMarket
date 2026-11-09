# Gates: InsaneMarket proper app market

OWNS: app/** .github/** README.md build.gradle.kts settings.gradle.kts gradle.properties GATES.md

Scope: Deliver a proper Android app market for InsaneBadPC GitHub APK releases with polished UI, install/update/open flows, search/filter, detail/edit/settings, and working CI.

- [ ] G1: only installable APK releases are listed
  CHECK: node -e "const fs=require('fs'); const g=fs.readFileSync('app/src/main/java/cz/insanebadpc/insanemarket/data/GitHubMarket.kt','utf8'); const a=fs.readFileSync('app/src/main/java/cz/insanebadpc/insanemarket/ui/AppAdapter.kt','utf8')+fs.readFileSync('app/src/main/java/cz/insanebadpc/insanemarket/ui/MainActivity.kt','utf8'); const ok=g.includes('.apk') && g.includes('return null') && !g.includes('Žádný release') && !a.includes('Repozitář nemá APK') && !a.includes('Bez APK'); console.log(ok?'G1_OK':'G1_FAIL'); process.exit(ok?0:1)"
  EXPECT: G1_OK
  EVIDENCE: pending

- [ ] G2: market UI includes search/filter, detail/edit, and user states
  CHECK: node -e "const fs=require('fs'); const files=['app/src/main/res/layout/activity_main.xml','app/src/main/res/values/strings.xml','app/src/main/java/cz/insanebadpc/insanemarket/ui/MainActivity.kt'].map(f=>fs.readFileSync(f,'utf8')).join('\n'); const ok=(files.includes('search')||files.includes('Search')) && (files.includes('Aktualizace')||files.includes('update')||files.includes('Filter')) && (files.includes('Otevřít')||files.includes('openInstalled')||files.includes('launch')) && (files.includes('empty')||files.includes('Žádné aplikace')) && (files.includes('Chyba')||files.includes('error')); console.log(ok?'G2_OK':'G2_FAIL'); process.exit(ok?0:1)"
  EXPECT: G2_OK
  EVIDENCE: pending

- [ ] G3: download/install/settings plumbing exists
  CHECK: node -e "const fs=require('fs'); const files=['app/src/main/java/cz/insanebadpc/insanemarket/util/ApkInstaller.kt','app/src/main/java/cz/insanebadpc/insanemarket/data/AppStateStore.kt','app/src/main/AndroidManifest.xml','app/src/main/java/cz/insanebadpc/insanemarket/ui/MainActivity.kt'].map(f=>fs.readFileSync(f,'utf8')).join('\n'); const ok=files.includes('FileProvider') && files.includes('REQUEST_INSTALL_PACKAGES') && files.includes('github_token') && files.includes('onProgress'); console.log(ok?'G3_OK':'G3_FAIL'); process.exit(ok?0:1)"
  EXPECT: G3_OK
  EVIDENCE: pending

- [ ] G4: required build and CI files remain present
  CHECK: node -e "const fs=require('fs'); ['app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','app/src/main/AndroidManifest.xml','.github/workflows/android.yml','app/src/main/java/cz/insanebadpc/insanemarket/ui/MainActivity.kt'].forEach(f=>fs.accessSync(f)); const w=fs.readFileSync('.github/workflows/android.yml','utf8'); console.log(w.includes('assembleDebug')&&w.includes('upload-artifact')?'G4_OK':'G4_FAIL')"
  EXPECT: G4_OK
  EVIDENCE: pending
