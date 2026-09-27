# Videoposition offener Tabs

Stand: 27.09.2026, implementiert fuer v1.4.9

## Ursache und Speicherung

Bisher enthielt `TabPersistence` nur Tab-ID, URL und Titel. Hibernation,
App-Prozessverlust und Gecko-Prozess-Recovery laden diese URL erneut und
verlieren dabei den DOM-/Player-Zustand. Eine inaktive, weiterhin vorhandene
Gecko-Session behaelt dagegen ihre Wiedergabeposition.

`TabSession.watchProgress` speichert jetzt die Video-ID und die zuletzt
gemeldete Position in ganzen Sekunden separat von der sichtbaren URL.
`TabPersistence` schreibt beides mit den Tabdaten in private SharedPreferences.
Alte Tabdaten ohne diese optionalen Felder bleiben gueltig. Es gibt kein
zusaetzliches Videoarchiv; beim Schliessen des Tabs oder Wechsel auf ein
anderes Video verschwindet sein gespeicherter Stand.

## Ereignisse aus der WebExtension

`watch-progress.js` liest ausschliesslich das Hauptvideo unter
`#movie_player video.html5-main-video` einer Desktop-`/watch`-Seite.
URL-Video-ID und `ytd-watch-flexy[video-id]` muessen uebereinstimmen.
`youtu.be` wird nach seinem normalen YouTube-Redirect als Watch-Seite erfasst.

- `timeupdate` sichert hoechstens alle fuenf Sekunden; es gibt keinen Timer.
- `pause`, `seeked`, `ended`, `playing`, Verbergen und Verlassen der Seite
  koennen unmittelbar sichern. Identische Sekunden werden zusammengefasst.
- Eine noch nicht initialisierte Position null ueberschreibt keinen alten
  Stand. Rueckwaertsspruenge und ein bewusstes Zuruecksetzen auf null nach
  begonnener Wiedergabe bleiben erlaubt.
- Werbung (`ad-showing`, `ad-interrupting`), Live/DVR (`ytp-live`), unendliche
  oder ungueltige Dauer, laufende Suchbewegungen und Vorschauplayer werden
  ausgelassen. Ein `ended`-Ereignis setzt den gespeicherten Start auf null.
- Die Bindung zwischen Videoelement, Medienquelle und Video-ID entsteht bei
  `loadedmetadata`. Waehrend `yt-navigate-start`/`yt-navigate-finish` wird
  keine Position einem neuen Ziel zugeordnet. Ein spaet aktualisierter
  Watch-Renderer wird beim naechsten Medienereignis beruecksichtigt.

`WATCH_PROGRESS` laeuft ueber die vorhandene native Extension-Bridge.
Gecko akzeptiert nur die zugehoerige Session und Top-Level-Nachrichten einer
noch registrierten Bridge. Die App validiert Host, Video-ID, endliche Dauer
und Position und nimmt den Stand nur fuer dasselbe Video im selben Tab an.
Geckos MediaSession bleibt unveraendert fuer Wiedergabe und Android-Steuerung
zustaendig. Deren gecachte Position wird nicht als Checkpoint verwendet, weil
sie bei Metadaten-/Navigationswechseln zum vorherigen Video gehoeren kann.

## Wiederherstellen

`WatchProgress.restoreUrl()` ersetzt `t`, `start`, `time_continue` und ein
Zeitfragment durch genau ein `t=<Sekunden>s`. Playlist- und andere
Kontextparameter bleiben erhalten. Eine vorhandene Startzeit eines neu
geoeffneten Links gilt bis zum ersten eigenen Checkpoint.

Die URL wird beim Kaltstart, Lazy Restore, Aufwecken nach Hibernation,
Gecko-Crash/-Kill-Recovery und ausdruecklichen Toolbar-Reload verwendet.
Es gibt keine durch Positionsmeldungen ausgeloesten Loads, History-Eintraege,
DOM-Seeks oder `history.replaceState`-Aufrufe. Rotation und Rebind behalten
weiterhin vorhandene Sessions; `createTab()` laedt nur neu erzeugte Sessions.

## Grenzen

Es wird auf die letzte volle Sekunde abgerundet. Bei abruptem Prozessende
bleibt der letzte erfolgreich persistierte Stand; seitdem vergangene Sekunden
koennen fehlen. Seitenende und Android-Kill garantieren kein letztes Event.
Normale YouTube-Autoplayregeln bestimmen weiterhin, ob das Video sofort spielt
oder an der gespeicherten Stelle auf Play wartet. Live-DVR, Shorts und ein
vollstaendiger History-/Scroll-/Formular-Restore sind nicht Bestandteil.
Die Erkennung haengt vom YouTube-Watch-DOM ab; unklare Zuordnungen werden
verworfen, statt eine fremde Position zu speichern.

## Automatisierte Pruefungen

- `node --test tools/watch-progress.test.js`: Pause, Fuenf-Sekunden-Drossel,
  Seek, Verbergen, Seitenende, Startup-null, Abbau des Players, Ende,
  Werbung/Live/Vorschau, SPA-Reihenfolge, spaeter Renderer und mehrere Tabs.
- `WatchProgressTest`: URL-Zeitparameter und Kodierung, alte Tabdaten,
  fremde Video-IDs, Navigation, fehlerhafte Samples und Ende/Rueckwaertssprung.
- `gradlew.bat testDebugUnitTest assembleRelease`: Kotlin-Regressionen und
  signierter Produktionsbuild.

Am 27.09.2026 bestanden: 59 JVM-Tests, 14 JavaScript-Tests (davon zehn fuer
Videopositionen), JavaScript-/Manifest-Syntaxpruefung und alle drei
Release-ABI-Builds. Paket, Version, deaktivierte Diagnose und die Signatur
gegen Produktionskeystore sowie den veroeffentlichten v1.4.8-Release wurden
geprueft.

## Noch auf dem Geraet abzunehmen

1. Zwei Watch-Tabs an unterschiedlichen Stellen pausieren, App vollstaendig
   beenden und neu oeffnen: aktiver Tab und spaeter gewaehlter Tab setzen
   jeweils an der eigenen Stelle fort.
2. Ein Video im Hintergrund abspielen und ueber System-/Bluetooth-Pause
   stoppen. Nach einem App-Neustart dieselbe Position pruefen.
3. Mehrere Watch-Tabs oeffnen und einen durch echten Speicherdruck
   hibernierten Tab wieder auswaehlen; entsprechend nach Gecko-Prozessverlust.
4. In einer Playlist zum naechsten Video wechseln und spaeter zurueckspulen.
   Kein alter Zeitpunkt darf auf das neue Video uebertragen werden.
5. Vor-/Zwischenwerbung, Live mit DVR und ein vollstaendig beendetes Video
   pruefen. Der Werbezeitpunkt darf keinen vorhandenen Stand ersetzen.
6. Einen Link mit eigener Startzeit oeffnen, pausieren, vor-/zurueckspulen und
   die App-Reload-Taste verwenden; kein Konflikt mehrerer Zeitparameter.
7. Rotation, Tabwechsel und Hintergrund/Vordergrund mit gesunder Session:
   keine neuen Loads oder Positionsspruenge; Desktop-Audiokanaele,
   Player-Menues und Android-Mediensteuerung bleiben bedienbar.

Die automatisierten Pruefungen ersetzen diese Live-YouTube-Abnahme nicht.
