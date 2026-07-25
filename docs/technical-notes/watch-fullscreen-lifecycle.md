# Watch- und Fullscreen-Lebenszyklus

Stand: 25.07.2026, gegen den historischen RC1-Neuaufbau und den aktuellen
GeckoView-Code geprueft

## Historisches Ergebnis

Im Maerz 2026 wurde der damalige WebView-Stand ausgehend von RC1 (`e7766ad`)
in kleinen Bloecken neu aufgebaut. Nach jedem Block wurden Watch-Seite,
Navigation und Fullscreen auf dem Geraet geprueft.

Der Fullscreen blieb funktionsfaehig, auch nachdem nacheinander folgende
Funktionen wieder hinzugekommen waren:

- kompakte Kopfzeile,
- Tab-Manager und persistierte Tab-Vorschauen,
- URL kopieren und bearbeiten,
- URL-Bereinigung ohne `app=desktop`,
- Dark Mode,
- Touch- und Zoom-Anpassungen,
- um eine Sekunde verzoegerte Watch-Stabilisierung,
- einfaches, tabbezogenes Lade-Overlay.

Damit wurden Toolbar, Tab-Manager, Dark Mode und ein einzelner
Stabilisierungs-Timer als alleinige Ursache des frueher beobachteten
Fullscreen-Schadens widerlegt. Der Task isolierte keinen einzelnen
urspruenglichen RC2-Commit als Fullscreen-Ursache.

Separat wurde jedoch eine konkrete Overlay-Regression nachgewiesen:
mehrstufige Quiet-Window-, Watchdog- und Unterbrechungslogik konnte sich
gegenseitig verlaengern oder einen neuen Seitenzustand ueberholen. Der
Rueckbau auf den einfachen Block-H-Ablauf (`aa90dfd`) beseitigte diese
Regression. Ein anschliessender einfacher Ausblendaufschub von zwei Sekunden
(`809c069`) wurde auf dem Geraet bestaetigt.

Die dauerhafte Erkenntnis lautet daher nicht „keine Timer verwenden“, sondern:
wenige, lokale und idempotente Wiederholungen mit einem eindeutigen
Generationsbezug sind robuster als eine verkettete Zustandsmaschine aus
Ruhefenstern, Watchdogs und gegenseitigen Abbruchregeln.

Das damalige Arbeitsprotokoll liegt in
[`../rebuild-log.md`](../rebuild-log.md). Es beschreibt einen historischen
WebView-Stand und ist keine aktuelle Architekturvorgabe.

## Heutige GeckoView-Architektur

Tube NEXT verwendet ausschliesslich GeckoView. Der immersive
Landscape-Watch-Modus verteilt die Verantwortung bewusst auf zwei Ebenen.

### Native Android-Ebene

`MainActivity`:

- aktiviert den Modus nur bei einer Watch-URL im Landscape-Format,
- versteckt App-Toolbar und Systemleisten,
- haengt Pinch-to-Zoom und Verschieben an die aktuelle Engine-View,
- setzt Skalierung und Translation beim Verlassen vollstaendig zurueck,
- beendet den Modus beim Wechsel in Portrait, auf eine Nicht-Watch-Seite oder
  in einen anderen passenden Tab-Zustand.

Die Gecko-Implementierung liefert Fullscreen-Aenderungen ueber
`GeckoSession.ContentDelegate.onFullScreen()` an die Activity. Sie stellt aber
keinen WebView-artigen Custom-View-Container bereit:
`GeckoEngineTab.isInCustomView()` ist immer `false` und
`exitFullscreenIfNeeded()` ist ein No-op.

Das noch vorhandene `fullscreenContainer`-Layout und die durch
`supportsLegacyWatchTweaks()` geschuetzten JavaScript-Injections sind
WebView-Altbestand. Da die verbindliche Engine GeckoView ist, laufen diese
Injections nicht. Neue Fullscreen-Arbeit darf diesen Altpfad nicht
reaktivieren.

### WebExtension-Ebene

Die WebExtension erkennt eine Watch-Seite im Landscape-Viewport und setzt
`tubenext-landscape-watch` auf dem Wurzeldokument. Das zugehoerige CSS:

- fixiert Player und Video auf den sichtbaren Viewport,
- blendet Masthead, Kommentare, Empfehlungen und sonstige Seitenspalten nur
  in diesem Modus aus,
- legt YouTubes Controls, Einstellungen, Captions und Tube-NEXT-Overlays ueber
  die Videoebene,
- laesst das normale Player-Einstellungsmenue bedienbar,
- unterdrueckt nur das gesondert dokumentierte technische
  `.ytp-contextmenu`.

Die Anwendung wird nach Navigation, Resize und Rotation mehrfach kurz
wiederholt. Diese Wiederholungen sind absichtlich zustandslos und idempotent:
Sie setzen dieselbe Root-Klasse und denselben Style-Knoten erneut, statt
mehrere native Lade- und Overlay-Zustaende zu orchestrieren.

Weitere Interaktionsdetails stehen in
[`watch-page-options-and-taps.md`](watch-page-options-and-taps.md) und
[`fullscreen-context-menu.md`](fullscreen-context-menu.md).

## Lade-Overlay und Watch-Stabilisierung

Das native Lade-Overlay ist pro Tab gespeichert und an
`pageLoadGeneration` gebunden. Ein spaeter Callback darf dadurch keinen
neueren Ladevorgang beenden. Unter GeckoView beendet Fortschritt 100 den
Ladezustand; die alte WebView-Viewport-Stabilisierung ist deaktiviert.

Das Overlay darf einen immersiven Player nicht ueberdecken. Ein gemeldeter
Gecko-Fullscreen-Eintritt blendet es deshalb unmittelbar aus. Neue
Stabilisierungslogik soll nicht wieder Navigation, DOM-Ruhe, Progress,
Fullscreen und Overlay in eine gemeinsame lange Timer-Kette koppeln.

## Verbindliche Invarianten

1. Watch-Seiten bleiben Desktop-Seiten; das mobile Erscheinungsbild entsteht
   durch eng begrenzte Layout-Anpassungen.
2. Landscape allein reicht nicht: Der immersive Modus gilt nur fuer eine
   aktuelle Watch-URL.
3. Android-System-UI und App-Chrome werden nativ gesteuert; YouTube-DOM und
   Player-Layer werden von der WebExtension gesteuert.
4. Portrait, Tab-Wechsel und Verlassen der Watch-Seite muessen Zoom,
   Translation, Touch-Listener und immersive System-UI zuruecksetzen.
5. Player-Controls, Zahnrad-Menue und Audio-Kanal-Auswahl muessen oberhalb des
   Videos erreichbar bleiben.
6. Das Lade-Overlay darf weder einen alten Tab-Zustand auf einen neuen
   uebertragen noch den immersiven Player verdecken.
7. Neue DOM-Wiederholungen muessen lokal, idempotent und durch einen klaren
   Abbruchzustand begrenzt sein.

## Regressionstest

1. Watch-Seite in Portrait oeffnen und Desktop-Funktionen, insbesondere eine
   vorhandene Audio-Kanal-Auswahl, pruefen.
2. Ins Landscape drehen: Toolbar und Systemleisten verschwinden, Player und
   Video fuellen den sichtbaren Bereich.
3. Player-Controls, Zahnrad-Menue, Captions und Audio-Kanal-Menue bedienen.
4. Pinch-to-Zoom und Verschieben pruefen; das technische Kontextmenue darf
   nicht erscheinen.
5. Zwischen Tabs wechseln und die Watch-Seite verlassen: App-Chrome erscheint
   wieder und die Engine-View hat Skalierung `1`, Translation `0`.
6. Zurueck ins Portrait drehen und denselben Ruecksetz-Zustand pruefen.
7. Mehrere schnelle Watch-Navigationen ausloesen: Ein alter Lade-Callback darf
   das Overlay eines neueren Ladevorgangs nicht ausblenden.
8. Bei sichtbarem Lade-Overlay in den Fullscreen wechseln: Das Overlay muss
   sofort verschwinden.
