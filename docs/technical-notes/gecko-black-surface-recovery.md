# Schwarze GeckoView-Oberflaechen nach Resume oder Tabwechsel

Stand: am 25.07.2026 gegen `master` und die Git-Historie geprueft

## Symptom

Nach laengerer Hintergrundzeit oder beim Wechsel zwischen mehreren Tabs konnte
die App weiterlaufen, waehrend eine oder mehrere YouTube-Seiten nur schwarz
angezeigt wurden. Das Problem trat nicht zwingend waehrend der aktiven Nutzung
auf. Ein schwarzer Tab ist deshalb nicht automatisch ein YouTube-Ladefehler
oder ein vollstaendiger Absturz der App.

## Beobachtete Fehlerbilder

Im Verlauf wurden mehrere verwandte, aber nicht identische Ursachen gefunden:

1. Mehrere Gecko-`SurfaceView`s waren gleichzeitig am Android-Container
   angehaengt. Eine leere oder alte Surface konnte ueber dem aktiven Tab liegen
   und schwarz zeichnen.
2. Die App und `GeckoSession` liefen noch, aber der Compositor war nicht bereit.
   `GeckoView.capturePixels()` meldete dann beispielsweise
   `Compositor must be ready before pixels can be captured`.
3. Android beziehungsweise Samsung beendete Gecko-Child-Prozesse unter
   Speicher- oder Ressourcen-Druck. In einem eingefangenen Fehlerfall gab es
   `lmkd`-Meldungen, verschwundene alte Tab-Prozesse und danach neu erzeugte
   Prozesse. Dieser Pfad erzeugte nicht immer GeckoViews `onKill()`- oder
   `onCrash()`-Callback.

Ein einzelner Gecko-Tab-Prozess erreichte im damaligen Test ungefaehr 456 MB
PSS beziehungsweise 581 MB RSS. Mehrere Desktop-YouTube-Tabs koennen daher
auch auf einem leistungsfaehigen Geraet erheblichen nativen, Surface- und
GPU-Speicher belegen.

## Aktuelle Schutzschichten

### Nur die aktive View anhaengen

`attachOnlySelectedTabView()` sorgt dafuer, dass nur die View des ausgewaehlten
Tabs im `webViewContainer` liegt. Nicht aktive Views werden aus dem Container
entfernt, statt lediglich per Sichtbarkeit versteckt zu werden.

Dieser Fix stammt aus Commit `92463c2` und beseitigt ueberlagernde
`SurfaceView`s.

### Render-Healthcheck ohne vorsorglichen Reload

Nach Resume und Tabwechsel wird ein gedrosselter Healthcheck fuer den aktiven
Gecko-Tab geplant:

- `capturePixels()` fragt den aktuellen Compositor ab, ohne zu navigieren.
- Ein Capture-Fehler gilt als starkes Fehlersignal.
- Ein nahezu schwarzes Bild gilt nur als Heuristik und wird ein zweites Mal
  geprueft.
- Erst nach zwei fehlgeschlagenen Versuchen laeuft die Surface-Recovery.

`recoverVisibleGeckoSurface()` entfernt die aktive `GeckoView` kurz aus dem
Container und haengt dieselbe View wieder ein. URL, Cookies und
`GeckoSession` werden dabei nicht vorsorglich verworfen.

### Nachgewiesener Gecko-Prozessverlust

`GeckoBrowserEngine` protokolliert:

- `ContentDelegate.onCrash()`,
- `ContentDelegate.onKill()`,
- `onPaintStatusReset()`,
- `onFirstComposite()`,
- `onFirstContentfulPaint()`.

Bei einem offiziellen Crash-/Kill-Callback meldet
`onEngineProcessGone` den betroffenen Tab an `MainActivity`. Nur dieser Tab
wird neu erzeugt; ID, URL, Titel und die app-eigene Navigationshistorie werden
soweit moeglich erhalten. Die letzte URL muss danach neu geladen werden, weil
die alte `GeckoSession` nicht mehr verwendbar ist.

`ApplicationExitInfo` wird beim Resume und nach Render-Health-Fehlern unter
`TUBENEXT_EXIT` protokolliert. Das ist nachtraegliche Diagnose und kein
zuverlaessiges Vorab-Signal vor einem Android-Prozess-Kill.

### Speicherdruck

Seit `v1.3.6` kann die App entbehrliche Hintergrund-Video-Tabs bei
`onTrimMemory()` kontrolliert schlafen legen. Diese Massnahme reduziert die
Wahrscheinlichkeit unkontrollierter Gecko-Child-Prozess-Verluste. Ihre Regeln
stehen separat in `background-resource-management.md`.

## Wichtige Diagnose-Regel

Einen vorhandenen schwarzen Zustand nicht mit `force-stop`, App-Neustart oder
Neuinstallation zerstoeren, bevor mindestens Screenshot, Logcat,
Prozessliste und `ApplicationExitInfo` gesichert wurden. Ein Force-Stop
erzeugt Gecko-, GPU-, Media-, Tab-Prozesse und Surfaces neu und kann das
Symptom sofort verschwinden lassen.

## Historische Abweichung zum aktuellen Code

Im Task wurde zwischenzeitlich eine tabbezogene Recovery-Drossel und ein
zweiter Schritt mit neuer `GeckoView` an derselben `GeckoSession` implementiert
und lokal gebaut. Diese nicht separat committete Variante ist im heutigen
`master` nicht vorhanden:

- `lastGeckoSurfaceRecoveryAtMs` ist aktuell wieder global.
- Recoveries verschiedener Tabs koennen sich innerhalb von 12 Sekunden
  gegenseitig drosseln.
- `recoverVisibleGeckoSurface()` haengt nur dieselbe View erneut an.
- Ein eigener Display-/Session-Reattach-Schritt existiert derzeit nicht im
  `EngineTab`-Vertrag.

Das ist kein Beweis fuer einen aktuellen Fehler, aber eine bekannte
Regressions- beziehungsweise Verbesserungsluecke. Vor einer erneuten
Implementierung muss der Mehrtab-Fall auf dem aktuellen Stand reproduziert
und gegen die Hibernation aus `v1.3.6` bewertet werden.

## Validierung und Historie

- Commit `92463c2`: nur aktive Tab-View bleibt angehaengt.
- Commit `b6a1536`: `capturePixels()`-basierter Render-Healthcheck.
- Release `v1.3.4`: erste veroeffentlichte Healthcheck-Basis.
- Release `v1.3.5`: GeckoView-Update auf Version 151.
- Release `v1.3.6`: GeckoView 152, Crash-/Kill-/Paint-Diagnose, gezieltes
  Tab-Recreate und Speicherdruck-Hibernation.
- Testgeraet: Samsung SM-S928B, spaeter Android 16/API 36; Tube NEXT war von
  der Akkuoptimierung ausgenommen.

## Regressionstest

1. Mehrere schwere Watch-Tabs und mindestens eine Feed-Seite oeffnen.
2. Zwischen den Tabs wechseln und pruefen, dass nur die aktive View am
   Container haengt.
3. App ohne Force-Stop in den Hintergrund und wieder nach vorn bringen.
4. Auf `TUBENEXT_RENDER`, `TUBENEXT_EXIT`, `onFirstComposite` und
   `capturePixels()`-Fehler achten.
5. Bei schwarzem Zustand vor jeder Reparatur Screenshot, Prozesse und Logs
   sichern.
6. Sicherstellen, dass ein gesunder Tab keinen URL-Reload erhaelt.
7. Nach nachgewiesenem `onCrash()`/`onKill()` pruefen, dass nur der betroffene
   Tab neu aufgebaut wird.
8. Mehrere kurz nacheinander fehlschlagende Tabs beobachten; dabei die globale
   Recovery-Drossel als bekannte Grenze beruecksichtigen.
