# GitHub-Release und Diagnose-Deployment

Stand: am 15.08.2026 nach Abschluss der Fortschrittsdiagnose aktualisiert

Diese Notiz ist der verbindliche Arbeitsablauf fuer einen neuen stabilen
Tube-NEXT-Release und bewahrt den optionalen Diagnose-Deploymentpfad fuer eine
spaetere, ausdruecklich beschlossene Kampagne.

Die Fullscreen-Fortschrittsdiagnose ist seit dem 15.08.2026 abgeschlossen und
standardmaessig deaktiviert. Der Buildtyp `diagnosticRelease` wird ohne
`"-Ptubenext.enableProgressDiagnostics=true"` nicht angelegt. Vorhandene private
Diagnosedaten werden weder beim Deaktivieren noch durch ein signiertes Update
automatisch geloescht; die App zeigt ohne aktive Diagnose aber keine Export-
oder Loeschfunktionen dafuer an.

## Zielzustand

Jede neue stabile Version wird regulaer nur als oeffentliche Release-Variante
gebaut:

| Ziel | Variante | Versionsname | Verteilung |
|---|---|---|---|
| Nutzer | `release` | zum Beispiel `1.4.3` | drei ABI-APKs auf GitHub |

Der regulaere Release hat
`BuildConfig.PROGRESS_DIAGNOSTICS_ENABLED=false`. Die Diagnosevariante hat
denselben Package-Namen `de.shakie.tubenext` und dieselbe Produktionssignatur,
aber `PROGRESS_DIAGNOSTICS_ENABLED=true`. Sie ersetzt daher die vorhandene
Produktions-/Diagnoseinstallation ohne Verlust von Profil, Login, Tabs,
Einstellungen, Android-Linkzuordnungen oder Diagnoseprotokoll.

Nur waehrend einer reaktivierten Diagnosekampagne wird zusaetzlich aus
demselben Commit und mit demselben `versionCode` ein `diagnosticRelease` mit
dem Suffix `-diagnostic` gebaut. Die Diagnose-APK wird nie als
GitHub-Release-Asset veroeffentlicht. Sie ist
eine zeitlich begrenzte, geraetespezifische Untersuchungsfassung und kein
allgemeines Nutzerangebot.

Die integrierte Updatepruefung entfernt Versionssuffixe beim Vergleich. Eine
installierte `X.Y.Z-diagnostic`-Fassung betrachtet den oeffentlichen Release
`vX.Y.Z` deshalb als denselben Versionsstand und fordert nicht zum Wechsel auf
die diagnosefreie APK auf.

## Bekannte Hardware

Das reale Testtelefon ist ein Samsung Galaxy S24 Ultra (`SM-S928B`) mit
`arm64-v8a`. Seine WLAN-ADB-Seriennummer und IP-Adresse koennen sich aendern
und duerfen nicht fest in Skripte oder Dokumentation geschrieben werden. Das
aktuell erreichbare Geraet wird bei jedem Vorgang neu mit `adb devices -l`
ermittelt. Bei mehreren erreichbaren Geraeten muss jeder ADB-Befehl mit
`-s <serial>` eindeutig auf das `SM-S928B` begrenzt werden.

Ist der ADB-Daemon beim ersten Aufruf noch nicht aktiv, kann
`adb devices -l` zunaechst nur den Daemon starten und eine leere Geraeteliste
liefern, waehrend die WLAN-ADB-Verbindung unmittelbar danach aufgebaut wird.
Die Abfrage in diesem Fall mindestens einmal direkt wiederholen. Erst wenn
auch die Wiederholung kein Geraet zeigt, den Verbindungszustand anders
diagnostizieren oder auf MTP ausweichen.

Eine APK-Installation beendet gegebenenfalls den laufenden App-Prozess. Vor
dem Update deshalb kurz ankuendigen, dass am Telefon gearbeitet wird. Ohne
Notwendigkeit keine UI-Taps, Navigation oder Wiedergabesteuerung ausloesen.

## 1. Ausgangsstand feststellen

Nicht von einem alten Chat-Handoff ausgehen. Zuerst Repository, Branch,
Arbeitsbaum, Remote, letzten Tag und die aktuelle Gradle-Version pruefen:

```powershell
git status --short --branch
git remote -v
git fetch origin --tags --prune
git log --oneline --decorate -5
git tag --sort=-version:refname | Select-Object -First 5
rg -n "versionCode|versionName" app/build.gradle.kts
gh auth status
```

Unbekannte oder nicht zum Release gehoerende Aenderungen im Arbeitsbaum nicht
ueberschreiben, loeschen oder ungefragt mitveroeffentlichen. Vor Watch- oder
Engine-Aenderungen gelten weiterhin die in `AGENTS.md` genannten
Pflichtdokumente.

## 2. Version und Release-Notiz vorbereiten

In `app/build.gradle.kts`:

1. `versionCode` strikt erhoehen,
2. `versionName` auf die neue stabile Version ohne Suffix setzen.

Fuer jede veroeffentlichte Version wird `docs/releases/vX.Y.Z.md` angelegt oder
aktualisiert. Die Nutzer-Notiz beschreibt nur ausgelieferte Funktionen und
nennt die ABI-Zuordnung. Interne Diagnosefunktionen duerfen nicht als Funktion
des regulaeren Releases dargestellt werden; falls relevant, ausdruecklich
erwaehnen, dass sie dort deaktiviert sind.

Jede Release-Notiz enthaelt im sichtbaren Fliesstext direkt anklickbare Links
auf alle drei APKs. Die Linktexte nennen nicht nur das ABI-Kuerzel, sondern
erklaeren die Zielgruppe in einfacher Sprache. Die Reihenfolge und
Grundbeschriftung bleiben konsistent:

1. `arm64-v8a` - fuer die meisten aktuellen Android-Geraete,
2. `armeabi-v7a` - fuer aeltere 32-Bit-ARM-Geraete,
3. `x86_64` - fuer entsprechende Emulatoren und Geraete.

Die Links zeigen direkt auf die versionierten APK-Assets unter
`releases/download/vX.Y.Z/` und nicht nur allgemein auf die Asset-Liste. Ein
besonderer Wiederherstellungshinweis wie in `v1.4.4` ist nur bei Bedarf
noetig; die erklaerten Direktlinks selbst bleiben Bestandteil jedes Releases.

## 3. Release aus demselben Stand pruefen und bauen

Mindestens folgende Pruefungen ausfuehren:

```powershell
$node = "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
& $node --check app/src/main/assets/web_extensions/tubenext_nav_switch/content.js
Get-Content -Raw app/src/main/assets/web_extensions/tubenext_nav_switch/manifest.json |
    ConvertFrom-Json | Out-Null
.\gradlew.bat testDebugUnitTest assembleRelease
git diff --check
```

`assembleRelease` muss an `verifyProductionReleaseSigning` haengen. Ein
Fehlschlag der Signierung darf nicht durch Debug-Signierung oder
`localRelease` umgangen werden. Waehrend einer reaktivierten Diagnosekampagne
wird zusaetzlich mit derselben Gradle-Eigenschaft gebaut:

```powershell
.\gradlew.bat "-Ptubenext.enableProgressDiagnostics=true" `
    assembleDiagnosticRelease
```

Auch `assembleDiagnosticRelease` muss dann an
`verifyProductionReleaseSigning` haengen.

Danach mindestens die arm64-Metadaten des Release mit `aapt dump badging`
pruefen:

- Package: `de.shakie.tubenext`
- Release: `versionName=X.Y.Z`

Bei reaktivierter Diagnose muessen Package und `versionCode` mit dem Release
uebereinstimmen und der Versionsname `X.Y.Z-diagnostic` lauten.

Im generierten `BuildConfig.java` muss die Diagnose beim Release `false` sein;
bei reaktivierter Diagnose muss sie im Diagnostic Release `true` sein. Alle
drei oeffentlichen Release-APKs und gegebenenfalls die zu installierende
Diagnose-APK mit
`apksigner verify --print-certs` pruefen. Der SHA-256-Zertifikat-Fingerprint
muss exakt dem ausserhalb des Repositorys verwahrten Produktionswert und dem
vorherigen stabilen Release entsprechen. Keystore, Kennwoerter und Inhalt von
`key.properties` niemals ausgeben oder committen.

## 4. Git-Stand markieren und GitHub-Release veroeffentlichen

Nur den geprueften Umfang committen. Der stabile Tag `vX.Y.Z` muss auf dem
Release-Commit liegen, der anschliessend auf `master` veroeffentlicht wird.
Je nach Ausgangsbranch ist ein Fast-Forward auf `master` vorzuziehen; niemals
einen abweichenden `master` erzwingen.

Die Platzhalter in den folgenden Beispielbefehlen muessen vorher durch die
tatsaechliche Version, Dateiliste und den festgestellten Release-Branch
ersetzt werden:

```powershell
git add -- <gepruefte Dateien>
git diff --cached --check
git commit -m "Release Tube NEXT vX.Y.Z"
git switch master
git merge --ff-only <release-branch>
git tag -a vX.Y.Z -m "Tube NEXT vX.Y.Z"
git push origin master
git push origin refs/tags/vX.Y.Z
```

Falls der Release bereits direkt auf `master` vorbereitet wurde, entfallen
Branchwechsel und Merge. Vor Tag oder Upload pruefen, dass weder Tag noch
GitHub-Release bereits existieren.

Aus `app/build/outputs/apk/release/` werden genau diese drei Dateien mit
eindeutigen Namen bereitgestellt:

- `Tube-NEXT-vX.Y.Z-arm64-v8a.apk`
- `Tube-NEXT-vX.Y.Z-armeabi-v7a.apk`
- `Tube-NEXT-vX.Y.Z-x86_64.apk`

Fuer alle drei Dateien SHA-256-Werte berechnen und als
`SHA256SUMS-vX.Y.Z.txt` beilegen. Danach den stabilen, weder als Draft noch als
Prerelease markierten GitHub-Release erzeugen:

```powershell
gh release create vX.Y.Z <drei APKs> SHA256SUMS-vX.Y.Z.txt `
    --repo ShakieVan/Tube-NEXT `
    --verify-tag `
    --title "Tube NEXT vX.Y.Z" `
    --notes-file docs/releases/vX.Y.Z.md
```

Abschliessend mit folgenden Befehlen pruefen:

```powershell
gh release view vX.Y.Z --repo ShakieVan/Tube-NEXT `
    --json tagName,name,isDraft,isPrerelease,publishedAt,url,targetCommitish,assets
gh release list --repo ShakieVan/Tube-NEXT --limit 5
```

- Release ist oeffentlich, kein Draft und kein Prerelease,
- er ist der aktuelle `Latest`-Release,
- alle vier Assets sind vollstaendig hochgeladen,
- Groessen und von GitHub gemeldete SHA-256-Digests stimmen lokal ueberein.

## 5. Nur bei reaktivierter Kampagne: Diagnosevariante aktualisieren

Dieser Abschnitt wird im aktuellen, abgeschlossenen Diagnosezustand nicht
ausgefuehrt. Eine neue Kampagne muss zuvor die Gradle-Eigenschaft explizit
aktivieren und diese Notiz um ihr konkretes Ziel, ihre Daten und ihre
Abschlussbedingung ergaenzen.

Erst nach erfolgreichem oeffentlichem Release die aus demselben Commit
erzeugte arm64-Diagnose-APK installieren. Android-SDK-Pfade portabel
ermitteln:

```powershell
$sdk = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    "$env:LOCALAPPDATA\Android\Sdk"
}
$adb = Join-Path $sdk "platform-tools\adb.exe"
& $adb devices -l
```

Bei genau einem eindeutig erkannten `SM-S928B`:

```powershell
$serial = "<Ausgabe von adb devices -l>"
& $adb -s $serial shell getprop ro.product.cpu.abi
& $adb -s $serial install -r `
    "app\build\outputs\apk\diagnosticRelease\app-arm64-v8a-diagnosticRelease.apk"
```

Verbindliche Schutzregeln:

- immer `install -r` verwenden,
- niemals vorher deinstallieren,
- niemals `pm clear` ausfuehren,
- nicht `debug` oder `localRelease` als Ersatz installieren,
- kein Downgrade mit `-d` erzwingen,
- bei Signaturkonflikt oder niedrigerem `versionCode` stoppen und Ursache
  klaeren, statt App-Daten zu opfern.

`install -r` mit derselben Produktionssignatur und einem mindestens gleich
hohen `versionCode` erhaelt insbesondere
`files/diagnostics/fullscreen-progress-layout.jsonl` sowie zugehoerige Dateien
unter `files/diagnostics/screenshots/`. Die App muss fuer die reine
Installation nicht zusaetzlich durchgeklickt werden.

Nach der Installation pruefen:

```powershell
& $adb -s $serial shell dumpsys package de.shakie.tubenext |
    Select-String "versionCode=|versionName="
& $adb -s $serial shell pm get-app-links --user cur de.shakie.tubenext
```

Erwartet werden der neue `versionCode`, `versionName=X.Y.Z-diagnostic`,
`Verification link handling allowed: true` und weiterhin aktivierte
Benutzerauswahl fuer:

- `youtube.com`
- `www.youtube.com`
- `m.youtube.com`
- `youtu.be`

Ist das Telefon nicht erreichbar, bleibt das GitHub-Release trotzdem gueltig.
Das ausstehende Diagnose-Update muss dann klar als noch offen gemeldet werden;
es darf nicht durch Installation einer anderen Variante auf einem beliebigen
Geraet ersetzt werden.

## 6. Abschlusszustand

Vor Abschluss noch einmal pruefen:

- Arbeitsbaum sauber,
- `master`, `origin/master` und der neue Tag zeigen auf den Release-Commit,
- GitHub meldet den Release als `Latest`,
- oeffentliche APKs enthalten keine aktive Fortschrittsdiagnose,
- das Testtelefon verwendet die aktuelle regulaere Releasefassung; nur bei
  reaktivierter Kampagne die gleich versionierte Diagnosefassung,
- vorhandene Diagnoseereignisse wurden nicht unbeabsichtigt geloescht oder
  zurueckgesetzt.

In der Abschlussmeldung GitHub-URL, Test-/Signaturstatus, auf dem Testtelefon
installierte Version samt Variante und Zustand der vier Linkzuordnungen nennen.
Falls einer dieser Punkte nicht verifiziert werden konnte, ihn ausdruecklich
als offen ausweisen.
