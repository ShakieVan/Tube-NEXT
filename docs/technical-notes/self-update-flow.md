# GitHub-/Sideload-Updateverwaltung

Stand: am 01.08.2026 gegen den `v1.4.0`-Release-Kandidaten und die
Git-Historie geprueft

## Distributionsmodell

Tube NEXT wird als signierte APK ueber GitHub Releases verteilt und derzeit
nicht fuer Google Play gebaut. Die integrierte Updateverwaltung ist deshalb
ein bewusster Sideload-Pfad:

1. GitHub Release finden,
2. passende ABI-APK auswaehlen,
3. auf ausdruecklichen Nutzerwunsch herunterladen,
4. Android-Systeminstaller oeffnen.

Die App installiert nichts still im Hintergrund und ersetzt nicht den
Android-Paketinstaller.

## Release-Pruefung

`GitHubReleaseClient` fragt ohne Anmeldung den Endpunkt
`/repos/ShakieVan/Tube-NEXT/releases/latest` ab. Drafts und Prereleases werden
damit nicht als `latest` behandelt.

Die Version wird aktuell aus dem Git-Tag normalisiert und numerisch mit
`BuildConfig.VERSION_NAME` verglichen. Beispiele:

- `v1.3.8` wird zu `1.3.8`,
- Suffixe nach `-` werden abgeschnitten,
- fehlende numerische Teile werden als `0` behandelt.

Entgegen einer fruehen Planung verwendet der aktuelle Client nicht
`versionCode`, weil die GitHub-Release-Antwort keinen App-`versionCode`
enthaelt. Daraus folgt: Release-Tags und `versionName` muessen streng
aufsteigend und konsistent bleiben.

## Pruefzeitpunkt

Die aktuelle App startet die automatische Pruefung in `MainActivity.onStart()`.
Ein gespeicherter Zeitstempel verhindert weitere automatische Abfragen
innerhalb von 24 Stunden.

Es gibt:

- keinen dauerhaft laufenden 24-Stunden-Handler,
- keinen WorkManager-Job,
- keine Pruefung, waehrend die App dauerhaft im Hintergrund bleibt und nicht
  erneut gestartet beziehungsweise in den Vordergrund gebracht wird.

Die Updateverwaltung kann jederzeit eine erzwungene manuelle Pruefung
ausloesen.

Historisch pruefte der Hotfix `v1.3.2` bei jedem App-Start. `v1.3.3` ersetzte
das aus Energiegruenden wieder durch die heutige Zeitstempelpruefung. Die
Release-Notiz von `v1.3.2` beschreibt daher korrekt den damaligen, nicht den
aktuellen Stand.

## Auswahl der APK

`UpdateAssetSelector`:

1. betrachtet nur Dateien mit Endung `.apk`,
2. geht `Build.SUPPORTED_ABIS` in Geraetereihenfolge durch,
3. nimmt das erste Asset, dessen Dateiname die ABI als vollstaendiges Token
   enthaelt,
4. verwendet nur als Fallback ein Asset mit `universal` im Namen.

Der Release-Vertrag verlangt daher eindeutige ABI-Namen:

- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`
- `x86`

Wenn kein passendes Asset existiert, zeigt die Updateverwaltung
`NO_COMPATIBLE_ASSET` und bietet keinen falschen Download an.
Die Token-Grenze behandelt `_` als Teil eines ABI-Namens; dadurch kann ein
`x86`-Geraet nicht versehentlich das zuerst gelistete `x86_64`-Asset erhalten.

## Notification und Berechtigung

Update-Hinweise koennen global deaktiviert oder fuer genau einen Release-Tag
ignoriert werden.

Auf Android 13 und neuer benoetigt die App `POST_NOTIFICATIONS`. Ein frueher
Fehler bestand darin, bei fehlender Berechtigung still abzubrechen. Seit
`v1.3.2` erscheint stattdessen ein In-App-Dialog mit:

- Benachrichtigungen erlauben,
- Updateverwaltung anzeigen,
- Abbrechen.

Wird die Berechtigung abgelehnt, bleibt ein Snackbar-Einstieg in die
Updateverwaltung sichtbar.

Die Notification selbst bietet:

- Updateverwaltung anzeigen,
- diese Release-Version ignorieren,
- Update-Benachrichtigungen allgemein deaktivieren.

## Download

Der Download:

- laeuft in einem eigenen Thread,
- verwendet eine `.part`-Datei,
- meldet Fortschritt,
- kann abgebrochen werden,
- verschiebt die Teildatei erst nach vollstaendigem Download auf den
  Zielnamen,
- speichert APK und Metadaten im privaten App-Verzeichnis unter
  `files/updates/<tag>/`.

Eine bereits geladene APK bleibt in der Updateverwaltung installier- oder
loeschbar, solange Datei und gespeicherter Release-Tag zusammenpassen.

## Installation und unbekannte Apps

Vor Download und erneut vor Installation prueft Tube NEXT
`PackageManager.canRequestPackageInstalls()`.

Ohne Freigabe kann der Nutzer:

- die App-spezifische Systemeinstellung
  `ACTION_MANAGE_UNKNOWN_APP_SOURCES` oeffnen,
- beim ersten Hinweis trotzdem nur herunterladen,
- die Installation spaeter erneut aus der Updateverwaltung starten.

Die APK wird per `FileProvider` als `content://`-URI mit Lesefreigabe an den
Android-Systeminstaller uebergeben.

Vor dem Installer-Start speichert Tube NEXT den Installationsversuch. Die neue
App-Version kann beim naechsten Start daran erinnern, die Installationsfreigabe
wieder zu schliessen. Der Hinweis kann einmalig fuer die aktuelle Version oder
dauerhaft ausgeblendet und in der Updateverwaltung wieder aktiviert werden.

## Release Notes in Tube NEXT

Der Link eines GitHub-Releases wird wie andere fremde HTTP(S)-Ziele an eine
externe Anwendung gegeben. Dadurch kann keine GitHub-Seite im Gecko-Tab
angezeigt werden, waehrend die Toolbar weiterhin eine alte YouTube-Adresse
zeigt.

## Sicherheits- und Robustheitsgrenzen

Der aktuelle Downloader verifiziert selbst weder:

- die SHA-256-Pruefsumme aus dem Release,
- eine erwartete APK-Signatur,
- einen aus der GitHub-API gelieferten Digest.

Android verhindert bei einem Update normalerweise die Installation einer APK
mit unpassender Signatur zur vorhandenen App. Dennoch waere eine
In-App-Pruefung von Digest und erwarteter Signatur eine zusaetzliche
Schutzschicht vor einer manipulierten oder unvollstaendig referenzierten
Release-Datei.

Weitere Grenzen:

- keine ETag-/`If-None-Match`-Nutzung beim GitHub-Abruf,
- kein persistenter Android-Hintergrundjob,
- Versionsvergleich basiert auf Release-Tag/`versionName`, nicht
  `versionCode`,
- Digest- und Signaturpruefung des Downloads sind noch nicht automatisiert.

Versionsnormalisierung, Versionsvergleich und ABI-Auswahl werden inzwischen
offline durch JVM-Tests abgedeckt. Dazu gehoeren Geraeteprioritaet,
`universal`-Fallback und die Abgrenzung `x86` zu `x86_64`.

Diese Punkte sind keine stillschweigend zugesicherten Funktionen. Sie muessen
bei einer spaeteren Haertung bewusst priorisiert und getestet werden.

## Historie

- `v1.3.0`: erste vollstaendige GitHub-Updateverwaltung.
- `v1.3.1`: erster realer Updatezyklus und verbindliche ABI-Erklaerung in
  Release Notes.
- `v1.3.2`: sichtbarer Fallback fuer fehlende Notification-Berechtigung und
  damalige Pruefung bei jedem Start.
- `v1.3.3`: energiesparende 24-Stunden-Zeitstempelpruefung in `onStart()`.
- `v1.4.0`: ABI-Namen werden als vollstaendige Tokens abgegrenzt, damit
  insbesondere `x86` nicht mit `x86_64` verwechselt wird; die Auswahl ist
  durch Offline-Unit-Tests abgesichert.

## Regressionstest

1. Alte App-Version installieren und ein neueres stabiles GitHub-Release
   bereitstellen.
2. Automatische Pruefung mit abgelaufenem und nicht abgelaufenem
   24-Stunden-Zeitstempel testen.
3. Manuelle Pruefung muss das Intervall umgehen.
4. Passende APK fuer jede unterstuetzte ABI auswaehlen.
5. Fehlendes ABI-Asset muss als inkompatibel erscheinen.
6. Notification erlaubt, abgelehnt und dauerhaft deaktiviert testen.
7. `Diese Version ignorieren` darf nur den betreffenden Release-Tag
   unterdruecken.
8. Download starten, abbrechen, fortgesetzt neu starten und Datei loeschen.
9. Installationsfreigabe vor Download und vor Installation verweigern.
10. APK spaeter aus der Updateverwaltung installieren.
11. Post-Install-Hinweis einmalig, dauerhaft und nach Reaktivierung pruefen.
12. Release Notes muessen in einem neuen Tube-NEXT-Tab oeffnen.
