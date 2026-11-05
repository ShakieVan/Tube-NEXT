# Schwarze GeckoView-Oberflaechen nach Resume oder Tabwechsel

Stand: am 05.11.2026 um die Eskalation auf einen frischen Seitenaufruf
(`v1.4.13`) ergaenzt

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
4. Ein Seitenprozess lebte nach einer langen Hintergrundphase noch, zeichnete
   aber nicht mehr. Fall vom 04./05.11.2026 auf dem SM-S928B mit `v1.4.12`:
   Der Hauptprozess lief seit dem Vorabend weiter, kein Kaltstart. Vier
   App-Starts zwischen 06:06 und 06:07 zeigten jeweils Schwarz, und auch die
   Neu-laden-Taste half nicht. Erst ein erneutes Oeffnen derselben URL ueber die
   Adresszeile lud die Seite. Im Bugreport starten genau dann (06:10:23) neue
   Tab-Prozesse, und der alte Tab-Prozess mit adj 0 endet. `session.reload()`
   bleibt also im haengenden Prozess, ein frischer `loadUri()` nicht.
   Sichtbar wurde der Fall erst mit `v1.4.12`. Bis `v1.4.11` hielt der nach
   jeder Hintergrundpause weiterlaufende Medien-Foreground-Service die App
   versehentlich auf hoher Prioritaet und verdeckte das Problem, auf Kosten des
   Akkus. Seit `v1.4.12` darf Android die pausierte App wieder einfrieren oder
   zuruecksetzen. Das ist gewollt.

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

### Render-Healthcheck mit zweistufiger Reparatur

Nach Resume und Tabwechsel wird ein gedrosselter Healthcheck fuer den aktiven
Gecko-Tab geplant:

- `capturePixels()` fragt den aktuellen Compositor ab, ohne zu navigieren.
- Ein Capture-Fehler gilt als starkes Fehlersignal. Antwortet der Capture nicht
  innerhalb von 2,5 Sekunden, zaehlt das als Fehler (`capture-timeout`). Sonst
  koennte ein Compositor, der auf einen haengenden Seitenprozess wartet, die
  Pruefung lautlos beenden.
- Ein nahezu schwarzes Bild gilt nur als Heuristik und wird ein zweites Mal
  geprueft.
- Erst nach zwei fehlgeschlagenen Versuchen laeuft die Reparatur.

Die Reihenfolge entscheidet `BlankTabRecoveryPolicy`:

1. Neu einhaengen: `recoverVisibleGeckoSurface()` entfernt die aktive
   `GeckoView` kurz aus dem Container und haengt dieselbe View wieder ein.
   URL, Cookies und `GeckoSession` bleiben unveraendert. Danach folgt eine
   Pruefung mit dem Grund `post-recovery`.
2. Frisch laden, nur wenn das Neu-Einhaengen nicht geholfen hat: Das ist der
   Fall, wenn das Bild bei der `post-recovery`-Pruefung weiter leer ist oder wenn
   derselbe Tab innerhalb von 12 Sekunden nach seinem Neu-Einhaengen erneut
   leer ist (etwa bei einem zweiten App-Start). Dann laedt
   `freshLoadBlankGeckoTab()` die Seite per `loadUri()` neu, wie ein erneutes
   Oeffnen der URL. Fuer dasselbe Video gilt der Watch-Checkpoint mit `t=`, die
   Position bleibt also erhalten. Grenzen dieses Schritts:
   - hoechstens einmal pro Tab und Minute,
   - nie bei aktiver Wiedergabe des Tabs, im Querformat-Videomodus oder bei
     einer Watch-Seite im Querformat, weil dunkle Videobilder dort legitim
     sind (`SKIP_VIDEO`),
   - nie auf transienten Account- oder Consent-Seiten ohne eigene Tab-URL.

Das ist eine bewusste, vom Nutzer am 05.11.2026 freigegebene Ausnahme von der
Regel, Tabs nicht aufgrund der Schwarz-Heuristik vorsorglich neu zu laden. Sie
greift erst nach vier leeren Pruefungen und einem erfolglosen Neu-Einhaengen.
Ein gesunder Tab erhaelt weiterhin keinen Reload.

Die Neu-laden-Taste nutzt dieselbe URL-Wahl (`BlankTabRecoveryPolicy.freshLoadUrl`)
und laedt per `loadUri()` statt `session.reload()`. Nur auf transienten
Account- oder Consent-Seiten bleibt es bei `session.reload()`, damit ein
Login-Fluss nicht zu YouTube zurueckspringt. Ein `loadUri()` derselben URL legt
keinen doppelten Verlaufseintrag an; Zurueck fuehrt weiter zur vorherigen
Seite.

Protokoll unter `TUBENEXT_RENDER`: `render health ok`, `render health skipped
while loading`, `blank Gecko capture`, `recover Gecko surface`,
`fresh load blank Gecko tab`, `skip Gecko render recovery action=...` und
`manual reload ... fresh=...`. Ein Bugreport direkt nach einem Blackscreen
zeigt damit, welche Stufe gelaufen ist.

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

- Die Drossel fuer das Neu-Einhaengen ist weiterhin global.
  `lastGeckoSurfaceRecoveryTabId` merkt sich seit `v1.4.13` nur zusaetzlich den
  Tab, damit ein erneut leerer Tab zum frischen Laden eskaliert.
- Recoveries verschiedener Tabs koennen sich innerhalb von 12 Sekunden
  gegenseitig drosseln (`SKIP_THROTTLED`).
- Das Neu-Einhaengen haengt nur dieselbe View erneut an.
- Ein eigener Display-/Session-Reattach-Schritt existiert derzeit nicht im
  `EngineTab`-Vertrag. Die zweite Stufe seit `v1.4.13` ist kein solcher Schritt,
  sondern ein frischer Seitenaufruf in derselben `GeckoSession`.

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
- Release `v1.4.13`: Neu laden per `loadUri()`, Eskalation auf einen frischen
  Seitenaufruf nach erfolglosem Neu-Einhaengen, Capture-Timeout und
  zusaetzliches Protokoll. Auf dem SM-G991B (S21) liess sich der haengende
  Seitenprozess nicht nachstellen. Versucht wurden: GPU-Prozess beenden,
  Seitenprozess beenden, alle Child-Prozesse beenden, ganze App beenden,
  Nachtmodus-Wechsel sowie Seitenprozess per `SIGSTOP` anhalten plus
  GPU-Prozess beenden. Die Kette wurde dort mit einem nur im Debug-Build
  gesetzten Schwarz-Urteil geprueft.
  Bei einem gesunden Prozess blieb die Seite nach `loadUri()` im selben
  Seitenprozess. Dass Gecko einen haengenden Prozess beim frischen Aufruf
  verlaesst, ist nur durch den Bugreport vom 05.11.2026 belegt.

## Regressionstest

1. Mehrere schwere Watch-Tabs und mindestens eine Feed-Seite oeffnen.
2. Zwischen den Tabs wechseln und pruefen, dass nur die aktive View am
   Container haengt.
3. App ohne Force-Stop in den Hintergrund und wieder nach vorn bringen.
4. Auf `TUBENEXT_RENDER`, `TUBENEXT_EXIT`, `onFirstComposite` und
   `capturePixels()`-Fehler achten.
5. Bei schwarzem Zustand vor jeder Reparatur Screenshot, Prozesse und Logs
   sichern.
6. Sicherstellen, dass ein gesunder Tab keinen URL-Reload erhaelt
   (`render health ok`).
7. Nach nachgewiesenem `onCrash()`/`onKill()` pruefen, dass nur der betroffene
   Tab neu aufgebaut wird.
8. Mehrere kurz nacheinander fehlschlagende Tabs beobachten; dabei die globale
   Recovery-Drossel als bekannte Grenze beruecksichtigen.
9. Eskalation im Debug-Build mit einem voruebergehend erzwungenen
   Schwarz-Urteil in `handleGeckoRenderHealthCapture()` pruefen. Dieser Eingriff
   wird nie committet. Bei pausiertem Video auf einer Watch-Seite muss beim
   naechsten Resume diese Folge erscheinen: `recover Gecko surface`, dann
   `fresh load blank Gecko tab ... url=...&t=...s`, nach fuenf Sekunden
   `skip Gecko render recovery action=SKIP_THROTTLED`. Bei laufendem Video
   folgt auf das Neu-Einhaengen nur `action=SKIP_VIDEO`, und die Wiedergabe
   laeuft weiter.
10. Neu laden auf einer Watch-Seite antippen: `manual reload ... fresh=true`,
    die Position bleibt erhalten, und Zurueck fuehrt ohne doppelten
    Verlaufseintrag zur vorherigen Seite.
11. Bei einem echten Blackscreen nach langer Hintergrundzeit sofort einen
    Bugreport ziehen und auf die Folge der `TUBENEXT_RENDER`-Zeilen achten.
