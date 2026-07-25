# Hintergrundnutzung, Akku und Tab-Hibernation

Stand: am 25.07.2026 gegen `master` und die Git-Historie geprueft

## Leitentscheidung

Tube NEXT verwendet keinen periodischen Hintergrund-Checker und keinen
5-Minuten-Timer zum Einfrieren von Tabs. Zustandswechsel und Speicherfreigabe
werden durch vorhandene App- und Android-Ereignisse ausgeloest.

Das schuetzt drei Ziele:

- Hintergrund-Audio soll verlaesslich bleiben.
- Login, Cookies und wertvoller Seitenzustand sollen moeglichst lange erhalten
  bleiben.
- Die App soll nicht selbst durch Timer, Polling oder unnoetige Reloads zum
  Dauerverbraucher werden.

## Inaktiv ist nicht hiberniert

### Inaktiver Gecko-Tab

Beim Tabwechsel und beim Wechsel der Activity in den Hintergrund ruft Tube
NEXT `EngineTab.onPause()` auf. Gecko setzt dann:

- `session.setFocused(false)`
- `session.setActive(false)`

Session, DOM, URL, Cookies und Login bleiben dabei erhalten. Das ist eine
leichte Inaktivierung, kein Schliessen oder Reload.

Gecko wird mit `suspendMediaWhenInactive(false)` konfiguriert. Dadurch kann der
separate MediaSession-/Foreground-Service-Pfad Hintergrundwiedergabe
fortsetzen, obwohl die Seite nicht fokussiert ist.

### Hibernierter Tab

Bei Hibernation bleibt der `AppTab` als leichte Shell mit ID, Titel, URL,
Historie und Preview bestehen. Die schwere Gecko-Session und View werden
dagegen kontrolliert zerstoert und durch `HibernatedEngineTab` ersetzt.

Beim spaeteren Auswaehlen erzeugt `ensureTabAwake()` eine neue Gecko-Session
und laedt die letzte URL. Der laufende DOM-/Feed-Zustand dieses Tabs ist dann
nicht mehr vorhanden.

## Warum kein 5-Minuten-Standby existiert

Der urspruengliche Vorschlag wollte Tabs nach fuenf Minuten ohne Wiedergabe
zusaetzlich in Standby versetzen. Die Codepruefung zeigte:

- Nicht sichtbare Tabs werden bereits beim Tabwechsel inaktiv.
- Der ausgewaehlte Tab wird bereits beim App-`onPause()` inaktiv.
- Ein weiterer Timer wuerde dieselbe Lifecycle-Logik doppeln.
- Verzoegerte Aktionen koennten inzwischen geschlossene oder gewechselte Tabs
  treffen.
- Der erwartete Zusatznutzen waere kleiner als das Risiko fuer MediaSession,
  Tabwechsel und Wiederaufnahme.

Der Timer wurde daher nicht implementiert. Diese Entscheidung gilt weiterhin.

## Ereignisgesteuerte Hibernation bei Speicherdruck

Seit `v1.3.6` nutzt `MainActivity.onTrimMemory()` zwei Stufen:

### `TRIM_MEMORY_UI_HIDDEN`

- Preview des aktuellen Tabs sichern,
- nicht aktive Tabs pausieren,
- keine Gecko-Session zerstoeren.

### `TRIM_MEMORY_BACKGROUND` oder staerker

Nur wenn mehr als zwei Gecko-Tabs noch leben, werden geeignete
Hintergrund-Tabs nach `lastActivatedAtMs` sortiert und die aeltesten
kontrolliert hiberniert, bis hoechstens zwei lebende Tabs uebrig sind.

Von Hibernation geschuetzt sind:

- der aktuell ausgewaehlte Tab,
- bereits hibernierte Tabs,
- Tabs im Custom-/Fullscreen-View,
- ein Tab mit aktuell laufender Wiedergabe,
- alle unterstuetzten YouTube-Seiten, die keine Watch-URL sind.

Damit sind insbesondere Start-, Feed-, Such-, Kanal- und andere
Kontextseiten geschuetzt. Nur entbehrliche Watch-Tabs sind Kandidaten.

Android gibt der App mit `onTrimMemory()` eine Gelegenheit zum Aufraeumen,
aber kein Veto gegen einen spaeteren Prozess-Kill. Der Task konnte
`TRIM_MEMORY_UI_HIDDEN` auf dem Testgeraet nachweisen. Ein kuenstliches
`TRIM_MEMORY_BACKGROUND`-Signal wurde von Android verweigert, solange der
Prozess noch als foreground-nah eingestuft war; der echte starke Pfad blieb
damals ein Praxistest.

## Schutz der Hintergrundwiedergabe

`AndroidBackgroundAudioCoordinator.isPlaybackActiveForTab()` verhindert, dass
ein spielender Tab hiberniert wird.

Wenn Wiedergabe pausiert oder beendet wird:

- wird AudioFocus freigegeben,
- entfernt der Service die Foreground-Benachrichtigung,
- beendet sich `BackgroundAudioService`,
- bleibt die Gecko-Seite als inaktiver Tab im Speicher, solange kein
  Speicherdruck sie spaeter hiberniert.

Es laeuft danach kein absichtlich dauerhafter App-Timer fuer den
Hintergrundplayer weiter.

## Weitere dauerhafte Aktivitaetsreduktionen

### Updatepruefung

Die automatische Updatepruefung verwendet keinen 24-Stunden-Handler. Sie wird
in `onStart()` angestossen und vergleicht den gespeicherten
`lastCheckAtMillis` mit einem Intervall von 24 Stunden.

### Home-Feed-Observer

Der WebExtension-`MutationObserver` fuer Home-Feed-Filter wird nur verbunden,
wenn mindestens ein solcher Filter aktiv ist. Bei Standardeinstellungen laeuft
er nicht dauerhaft mit.

Zeitlich begrenzte `setTimeout`-Bursts fuer Watch-Layout und der kurze
Kommentar-Scroll-Timer sind davon getrennt und enden selbststaendig.

## Akkuoptimierungs-Hinweis

Tube NEXT prueft mit
`PowerManager.isIgnoringBatteryOptimizations(packageName)`, ob Android die App
weiter optimieren darf.

Der Hinweis erscheint, solange:

- die App noch optimiert wird und
- `Nicht mehr anzeigen` nicht gewaehlt wurde.

`Spaeter` setzt keine Versions- oder Dauersperre. In den Einstellungen kann der
Hinweis deaktiviert oder wieder aktiviert werden.

Es gibt keinen stabilen oeffentlichen Android-Deep-Link direkt auf
`App-Info > Akku`. Deshalb erklaert ein Zwischendialog den Weg und oeffnet dann
per `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` die App-Info von Tube NEXT.
Eine aggressive direkte Ausnahme-Anfrage wird nicht verwendet.

Die alte Konstante `KEY_BATTERY_OPTIMIZATION_HINT_VERSION_CODE` ist im
aktuellen Code noch vorhanden, wird aber fuer das Verhalten nicht mehr
verwendet. Sie ist ein kleiner spaeterer Bereinigungskandidat.

## Historie

- Task `019e5d95-e3a5-7d81-b46a-b246b1011d22`: 5-Minuten-Standby verworfen;
  Akku-Hinweis, zeitstempelbasierte Updatepruefung und bedingter
  Home-Feed-Observer umgesetzt.
- Release `v1.3.3`: diese risikoarmen Hintergrundverbesserungen
  veroeffentlicht.
- Task `019e69b3-d08f-7a41-b2c0-f47c7601dd67`: spaeterer Nachweis hohen
  Gecko-Speicherverbrauchs und Android-Prozessdrucks.
- Release `v1.3.6`: ereignisgesteuerte Hibernation von Hintergrund-Watch-Tabs
  hinzugefuegt.

## Regressionstest

1. Zwischen mehreren Tabs wechseln und pruefen, dass nur der ausgewaehlte Tab
   aktiv/fokussiert ist.
2. Hintergrund-Audio starten, App verlassen und Wiedergabe ueber Notification
   steuern.
3. Pausieren: Benachrichtigung und Foreground-Service verschwinden.
4. App wieder oeffnen: ausgewaehlter Tab wird reaktiviert; gesunde Seiten
   laden nicht grundlos neu.
5. Akku-Hinweis mit `Spaeter`, `Nicht mehr anzeigen` und dem
   Einstellungs-Schalter pruefen.
6. Updatepruefung mehrfach foregrounden und sicherstellen, dass das
   24-Stunden-Intervall respektiert wird.
7. Home-Feed-Filter deaktivieren und pruefen, dass kein Observer verbunden
   bleibt.
8. Bei echtem Speicherdruck pruefen, dass nur alte Hintergrund-Watch-Tabs
   hibernieren und Feed-/Playback-Tabs erhalten bleiben.
9. Hibernierten Tab auswaehlen und kontrollierten Reload der letzten URL
   pruefen.
