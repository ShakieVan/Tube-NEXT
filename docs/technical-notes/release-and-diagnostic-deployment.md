# GitHub-Release und Diagnose-Deployment

Stand: am 09.08.2026 fuer `v1.4.6` aktualisiert und gegen den mit `v1.4.3`
praktisch durchgefuehrten Ablauf geprueft

Diese Notiz ist der verbindliche Arbeitsablauf fuer einen neuen stabilen
Tube-NEXT-Release, solange parallel die Fullscreen-Fortschrittsdiagnose auf
dem bekannten Testtelefon laeuft. Sie verhindert, dass der Zusammenhang nur
in einem Chat bekannt ist.

Die Diagnosekampagne gilt als aktiv, bis diese Notiz ausdruecklich auf
`abgeschlossen` gesetzt und die weitere Behandlung der gespeicherten Daten
dokumentiert wurde. Ein Themen- oder Chatwechsel beendet sie nicht.

## Zielzustand

Jede neue stabile Version wird in zwei Varianten aus demselben Commit und mit
demselben `versionCode` gebaut:

| Ziel | Variante | Versionsname | Verteilung |
|---|---|---|---|
| Nutzer | `release` | zum Beispiel `1.4.3` | drei ABI-APKs auf GitHub |
| Testtelefon | `diagnosticRelease` | zum Beispiel `1.4.3-diagnostic` | nur direkt per ADB |

Der regulaere Release hat
`BuildConfig.PROGRESS_DIAGNOSTICS_ENABLED=false`. Die Diagnosevariante hat
denselben Package-Namen `de.shakie.tubenext` und dieselbe Produktionssignatur,
aber `PROGRESS_DIAGNOSTICS_ENABLED=true`. Sie ersetzt daher die vorhandene
Produktions-/Diagnoseinstallation ohne Verlust von Profil, Login, Tabs,
Einstellungen, Android-Linkzuordnungen oder Diagnoseprotokoll.

Die Diagnose-APK wird nicht als GitHub-Release-Asset veroeffentlicht. Sie ist
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

`diagnosticRelease` erhaelt den Suffix `-diagnostic` automatisch. Fuer jede
veroeffentlichte Version wird `docs/releases/vX.Y.Z.md` angelegt oder
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

## 3. Beide Varianten aus demselben Stand pruefen und bauen

Mindestens folgende Pruefungen ausfuehren:

```powershell
$node = "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
& $node --check app/src/main/assets/web_extensions/tubenext_nav_switch/content.js
Get-Content -Raw app/src/main/assets/web_extensions/tubenext_nav_switch/manifest.json |
    ConvertFrom-Json | Out-Null
.\gradlew.bat testDebugUnitTest assembleRelease assembleDiagnosticRelease
git diff --check
```

`assembleRelease` und `assembleDiagnosticRelease` muessen wegen ihrer
Produktionsidentitaet beide an `verifyProductionReleaseSigning` haengen. Ein
Fehlschlag der Signierung darf nicht durch Debug-Signierung oder
`localRelease` umgangen werden.

Danach mindestens die arm64-Metadaten beider Varianten mit `aapt dump
badging` pruefen:

- Package: `de.shakie.tubenext`
- gleicher, neuer `versionCode`
- Release: `versionName=X.Y.Z`
- Diagnose: `versionName=X.Y.Z-diagnostic`

In den generierten `BuildConfig.java`-Dateien muss die Diagnose beim Release
`false` und beim Diagnostic Release `true` sein. Alle drei oeffentlichen
Release-APKs und die zu installierende Diagnose-APK mit
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

## 5. Diagnosevariante auf dem Testtelefon aktualisieren

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
- das Testtelefon verwendet die gleich versionierte Diagnosefassung,
- vorhandene Diagnoseereignisse wurden weder geloescht noch zurueckgesetzt.

In der Abschlussmeldung GitHub-URL, Test-/Signaturstatus, installierte
Diagnoseversion und Zustand der vier Linkzuordnungen nennen. Falls einer
dieser Punkte nicht verifiziert werden konnte, ihn ausdruecklich als offen
ausweisen.
