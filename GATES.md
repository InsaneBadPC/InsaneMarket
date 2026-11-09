# Gates: InsaneMarket fix limitations

OWNS: app/** README.md GATES.md

Scope: Fix reliable app matching, GitHub API rate-limit handling/token UX, and unknown-app install permission flow.

- [ ] G1: release metadata/package mapping is used for reliable install detection
  CHECK: node -e "const fs=require('fs'); const g=fs.readFileSync('app/src/main/java/cz/insanebadpc/insanemarket/data/GitHubMarket.kt','utf8'); const m=fs.readFileSync('app/src/main/java/cz/insanebadpc/insanemarket/model/AppEntry.kt','utf8')+fs.readFileSync('app/src/main/java/cz/insanebadpc/insanemarket/ui/MainActivity.kt','utf8'); const ok=(g.includes('insanemarket.json')||g.includes('packageName')) && (m.includes('packageName')||m.includes('manifestPackage')) && !g.includes('guessPackage') || (g.includes('insanemarket') && (m.includes('packageName'))); console.log(ok?'G1_OK':'G1_FAIL'); process.exit(ok?0:1)"
  EXPECT: G1_OK
  EVIDENCE: pending

- [ ] G2: API errors/rate limits are surfaced clearly and token settings remain available
  CHECK: node -e "const fs=require('fs'); const g=fs.readFileSync('app/src/main/java/cz/insanebadpc/insanemarket/data/GitHubMarket.kt','utf8'); const u=fs.readFileSync('app/src/main/java/cz/insanebadpc/insanemarket/ui/MainActivity.kt','utf8'); const ok=(g.includes('403')||g.includes('rate')||g.includes('limit')) && (u.includes('Nastavení')||u.includes('settings')||u.includes('token')); console.log(ok?'G2_OK':'G2_FAIL'); process.exit(ok?0:1)"
  EXPECT: G2_OK
  EVIDENCE: pending

- [ ] G3: unknown sources install permission is checked/requested before install
  CHECK: node -e "const fs=require('fs'); const f=fs.readFileSync('app/src/main/java/cz/insanebadpc/insanemarket/util/ApkInstaller.kt','utf8')+fs.readFileSync('app/src/main/java/cz/insanebadpc/insanemarket/ui/MainActivity.kt','utf8')+fs.readFileSync('app/src/main/AndroidManifest.xml','utf8'); const ok=f.includes('canRequestPackageInstalls')||f.includes('ACTION_MANAGE_UNKNOWN_APP_SOURCES')||f.includes('REQUEST_INSTALL_PACKAGES'); console.log(ok?'G3_OK':'G3_FAIL'); process.exit(ok?0:1)"
  EXPECT: G3_OK
  EVIDENCE: pending

- [ ] G4: build/CI files remain valid
  CHECK: node -e "const fs=require('fs'); ['app/build.gradle.kts','.github/workflows/android.yml','app/src/main/AndroidManifest.xml'].forEach(f=>fs.accessSync(f)); const w=fs.readFileSync('.github/workflows/android.yml','utf8'); console.log(w.includes('assembleDebug')?'G4_OK':'G4_FAIL')"
  EXPECT: G4_OK
  EVIDENCE: pending
