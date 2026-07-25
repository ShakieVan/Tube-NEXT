# Watch-Bereitschaft, Ladeanzeige und DOM-Nachlaeufe

Stand: 25.07.2026, gegen den historischen Timer-Task und den aktuellen
GeckoView-Code geprueft

## Problem

Ein Browser-Callback wie `onPageFinished` beziehungsweise Geckos
`onPageStop` bedeutet bei YouTube nicht, dass die sichtbare Watch-Seite fertig
aufgebaut ist. YouTube navigiert als Single-Page-App und kann danach weitere
Skripte, Daten, Player-Zustaende und Layoutaenderungen anwenden.

Umgekehrt bedeutet eine kurze Phase ohne Geometrie- oder Netzwerkaktivitaet
nicht sicher, dass die Seite fertig ist. Eine langsame Verbindung kann nur
voruebergehend stillstehen; YouTube kann zudem dauerhaft Hintergrundanfragen
erzeugen.

Eine perfekte allgemeine `YouTube ist bereit`-Erkennung existiert in diesem
Projekt daher nicht.

## Historischer Versuch im WebView-Stand

Der Task `Ersetze Watch-Timer durch Check` ersetzte zwei feste Wartezeiten
zunaechst durch eine heuristische Fertigerkennung:

- mehrere aufeinanderfolgende stabile Layout-Messungen,
- Ruhe seit der letzten `onLoadResource`-Aktivitaet,
- eine kurze Ruhephase bei WebView-Progress 100,
- ein harter Timeout als Sicherheitsnetz,
- tabbezogene Generationen gegen veraltete Callbacks.

Die Signale waren einzeln plausibel, ihre Kopplung aber nicht verlaesslich.
Die folgenden Grenzen wurden im Geraetetest sichtbar:

- Ressourcen-Callbacks erfassen spaete scriptgetriebene Arbeit nicht
  vollstaendig und koennen zugleich durch unwichtige Hintergrundaktivitaet
  verlaengert werden.
- Geometrie kann waehrend eines Netzstillstands mehrfach gleich aussehen und
  sich danach erneut aendern.
- Mehrere Ruhefenster, Watchdogs, Progress-Callbacks und
  Navigationsereignisse erzeugen konkurrierende Abbruch- und Neustartpfade.
- Aggressive `MutationObserver`-, Resize- und Reflow-Nachsteuerung fuehrte zu
  `SurfaceSyncGroup`-Timeouts und Haengern bei Rotation.

Der damalige Commit `f3180cc` ist deshalb nur historische Evidenz fuer diesen
Versuch, keine wiederzuverwendende Loesung.

## Nachgewiesener Viewport-Fehler

Die Geraetelogs zeigten im fehlerhaften Landscape-Zustand gleichzeitig:

- `window.innerWidth` und `visualViewport.width` von etwa 914 Pixeln,
- `documentElement.clientWidth` und Player-Breite von etwa 411 Pixeln,
- einen breiten aeusseren `full-bleed-container`,
- einen schmalen `#movie_player` mit `ytp-xsmall-width-mode`.

Die Portrait-Stabilisierung hatte fuer die Zielbreite den kleineren Wert aus
Viewport und Dokumentbreite gewaehlt. Ein spaeter oder noch laufender
Portrait-Zyklus konnte dadurch den Landscape-Player wieder auf etwa 411 Pixel
begrenzen; die uebrige Flaeche blieb schwarz.

Die damalige Korrekturrichtung war:

1. Portrait-Stabilisierung im Landscape-Zustand nicht ausfuehren.
2. Alte tabbezogene Zyklen beim Moduswechsel invalidieren.
3. Niemals eine veraltete schmale Dokumentbreite als Obergrenze fuer den
   aktuellen Landscape-Viewport verwenden.

Direkte wiederholte Manipulationen von `#movie_player`, Width-Mode-Klassen und
Video-Inline-Styles erwiesen sich dagegen als zu fragil. Sie konnten
Blackscreens, fehlende Controls und eine defekte Rueckkehr nach Portrait
erzeugen.

## Heutiges GeckoView-Modell

Die aktuelle Implementierung trennt drei Sachverhalte:

### Native Navigation und Ladeanzeige

`GeckoBrowserEngine` meldet Hauptnavigation, Page-Stop und Progress an
`MainActivity`. Pro Tab halten `pageLoadGeneration`, `loadingOverlayVisible`
und `loadingProgress` den sichtbaren Ladezustand zusammen.

`completeTabLoading()` prueft die Generation, bevor ein verzoegerter
Overlay-Abschluss ausgefuehrt wird. Damit kann ein Callback eines alten
Ladevorgangs nicht ohne Weiteres den Zustand einer neueren Navigation
abschliessen. Progress 100 beendet unter GeckoView den Ladezustand; es wird
nicht behauptet, damit sei jedes YouTube-DOM-Detail fertig.

### WebExtension-Darstellung

Die WebExtension wendet Watch- und Landscape-Klassen nach den fuer YouTube-SPA
relevanten Ereignissen erneut an:

- initial beim Laden des Content Scripts,
- bei `yt-navigate-finish`,
- bei `popstate`,
- bei Resize und Orientation-Change.

Ein kleiner Burst aus festen kurzen Nachlaeufen faengt spaet entstehendes DOM
ab. Jeder Lauf ist idempotent: Er prueft URL und Viewport erneut und setzt
dieselbe Root-Klasse beziehungsweise denselben Style-Knoten. Er wartet nicht
darauf, dass die gesamte Seite vermeintlich „ruhig“ wird.

### Legacy-Code

`scheduleWatchViewportStabilization()` und
`WATCH_VIEWPORT_STABILIZE_DELAY_MS` existieren noch in `MainActivity`.
Die eigentliche DOM-Korrektur hinter `stabilizeYouTubeViewport()` ist jedoch
durch `supportsLegacyWatchTweaks()` auf die entfernte WebView-Engine begrenzt
und unter GeckoView ein No-op.

Diese Namen duerfen nicht als Beleg verstanden werden, dass die heutige
Watch-Darstellung von der alten WebView-Geometriemessung abhaengt. Eine
spaetere Bereinigung dieses Altpfads ist sinnvoll, aber eine eigene
Codeaenderung mit Regressionstest.

## Regeln fuer neue Nachlaeufe

1. Ein Browser-Ladeereignis ist ein Lebenszyklussignal, kein Beweis fuer ein
   fertiges YouTube-DOM.
2. Native Ladeanzeige und DOM-Darstellung bleiben getrennte Zustandsmaschinen.
3. Jeder verzoegerte native Callback braucht mindestens Tab-ID und
   Ladegeneration.
4. Jeder DOM-Nachlauf muss URL, Modus und Viewport im Ausfuehrungszeitpunkt
   erneut pruefen.
5. DOM-Aenderungen muessen idempotent sein; Style-Knoten und Root-Klassen
   werden wiederverwendet.
6. Portrait-Werte duerfen niemals spaeter einen Landscape-Viewport begrenzen.
7. Keine permanente Reflow-, Resize- oder Klassen-Gegensteuerung gegen
   YouTubes Player aufbauen.
8. Ein harter visueller Failsafe ist besser als ein Overlay, das auf eine
   unerreichbare globale Ruhebedingung wartet.

## Regressionstest

1. Watch-Seite bei schneller und gedrosselter Verbindung oeffnen.
2. Mehrfach innerhalb der YouTube-SPA zwischen Videos navigieren.
3. Vor Abschluss einer Navigation einen weiteren Link oeffnen; ein alter
   Callback darf das aktuelle Overlay nicht beenden.
4. Zwischen zwei Tabs mit unterschiedlichen Ladezustaenden wechseln.
5. Waehren des Watch-Aufbaus in Landscape und zurueck nach Portrait drehen.
6. Im Landscape-Log pruefen, dass Player und sichtbarer Viewport dieselbe
   Breite verwenden und kein schmaler Portrait-Wert nachtraeglich greift.
7. Auf Blackscreen, halbbreiten Player, fehlende Controls und
   `SurfaceSyncGroup`-Timeouts achten.
8. Sicherstellen, dass Nicht-Watch-Seiten keine Watch-spezifischen
   DOM-Nachlaeufe erhalten.

Die Aufgabenteilung des immersiven Modus ist zusaetzlich in
[`watch-fullscreen-lifecycle.md`](watch-fullscreen-lifecycle.md)
dokumentiert.
