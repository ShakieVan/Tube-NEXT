# Watch-Seiten-Optionen und Fullscreen-Taps

Stand: am 25.07.2026 gegen `master`, `v1.2.0` und den zugehoerigen
Projekt-Task geprueft

Diese Notiz ergaenzt die verbindliche Entscheidung
[`desktop-watch-mobile-layout.md`](../decisions/desktop-watch-mobile-layout.md).
Die Watch-Seite bleibt eine Desktop-Seite; die hier beschriebenen Funktionen
sind eng begrenzte Darstellungs- und Touch-Anpassungen.

## Kanal-Branding ausblenden

In den Einstellungen gibt es unter `Watch-Seite` die standardmaessig
deaktivierte Option `Kanal-Branding ausblenden`.

Ihr urspruenglicher Zweck ist nicht allgemeines Entfernen von Links oder
Werbung. YouTubes schwebendes Kanal-Wasserzeichen konnte beim Einblenden der
Player-Steuerung verschoben werden und dadurch genau unter dem zweiten Tap
einer Doppeltipp-Geste landen. Statt zu spulen oeffnete die App dann
versehentlich den Kanal-Link.

Die Option wird in den App-Praeferenzen als `watch_page_hide_branding`
gespeichert und ueber `EngineHomeFeedSettings` sowie den nativen
WebExtension-Port an alle lebenden Tabs verteilt. Der Name der Settings-Klasse
ist historisch gewachsen; sie transportiert heute Startseiten- und
Watch-Einstellungen.

Auf einer Watch-Seite setzt die WebExtension die Root-Klasse
`tubenext-hide-watch-branding`. Das zugehoerige CSS blendet nur bekannte
Branding-Elemente aus:

- `.annotation.annotation-type-custom.iv-branding`
- `.ytp-ce-channel`
- `.ytp-watermark`
- `.branding-img-container`

Ausserhalb einer Watch-Seite oder bei deaktivierter Option ist die Root-Klasse
nicht aktiv. Die Anpassung betrifft weder den normalen Einstellungsdialog des
Players noch Audio-Kanal-Auswahl, Kommentare oder allgemeine Links.

## Nicht veroeffentlicht: Dislike-Anzeige

Im historischen Task wurde zunaechst eine optionale Dislike-Anzeige
prototypisch umgesetzt. Da YouTube keine oeffentliche aktuelle Dislike-Zahl
bereitstellt, verwendete der Versuch die Drittanbieter-API von Return YouTube
Dislike.

Der Abruf funktionierte, aber die Zahl liess sich in YouTubes damaliger
Button-DOM unter GeckoView nicht stabil sichtbar einbauen. Vor `v1.2.0`
wurden deshalb wieder entfernt:

- die Einstellungsoption,
- die zusaetzliche Host-Berechtigung der WebExtension,
- der Drittanbieter-Netzwerkabruf,
- Cache-, DOM- und CSS-Code fuer die Anzeige.

Der heutige Stand sendet keine Video-ID an diesen Dienst und verspricht keine
Dislike-Anzeige. Eine spaetere Wiederaufnahme waere eine neue
Produktentscheidung mit eigener Datenschutz-, Zuverlaessigkeits- und
DOM-Pruefung; sie ist kein unvollstaendiger Teil der aktuellen Funktion.

## Overlay-gekoppelte Fullscreen-Taps

Die eigene Tap-Steuerung ist auf `isLandscapeWatch()` begrenzt. Taps auf
echten Player-Bedienelementen werden nicht umgedeutet.

`isPlayerOverlayVisible()` erkennt das sichtbare YouTube-Overlay anhand:

- der Player-Klasse `ytp-autohide`,
- Sichtbarkeit und Deckkraft von `.ytp-chrome-bottom`.

Ist das Overlay verborgen, dient der erste Tap ausschliesslich dazu, es ueber
YouTubes `showControls()` und synthetische Mausbewegungsereignisse
einzublenden. Dabei wird eine begonnene Mehrfach-Tap-Folge verworfen. Erst
nachdem das Overlay sichtbar ist, gelten die eigenen Zonen:

| Zone | Ein Tap | Mehrfach-Tap |
|---|---|---|
| linkes Drittel | keine Wiedergabeaktion | 10 Sekunden zurueck |
| mittleres Drittel | Play/Pause | Play/Pause |
| rechtes Drittel | keine Wiedergabeaktion | 10 Sekunden vor |

Ein einzelner Tap wird kurz verzoegert ausgewertet, damit er von einem
folgenden Tap derselben Zone unterschieden werden kann. Jede verarbeitete
Aktion ruft erneut `showPlayerOverlay()` auf und stoesst damit auch YouTubes
Ausblendverhalten wieder an.

Touch- und Click-Pfade existieren parallel, weil Gecko/YouTube je nach
Geraetepfad unterschiedliche Ereignisse liefern. Zeitliche
Unterdrueckungsmarker verhindern, dass dieselbe Touch-Geste danach nochmals
als Click ausgefuehrt wird.

## Abgrenzung zu Long-Press und Kontextmenues

Ein Long-Press ausserhalb der Player-Bedienelemente setzt im immersiven
Landscape-Modus den Cue-Punkt. Bewegung ueber dem Schwellwert bricht die
Long-Press-Auswertung ab.

Die spaeter hinzugekommene Unterdrueckung von YouTubes technischem
Fullscreen-Kontextmenue ist ein eigener Pfad und in
[`fullscreen-context-menu.md`](fullscreen-context-menu.md) dokumentiert.
Normale Player-Menues und Dialoge duerfen durch die Tap-Steuerung nicht
blockiert werden.

## Lebenszyklus

Die Branding-Einstellung wird:

- beim Verbinden des WebExtension-Ports an einen Tab gesendet,
- nach einer Aenderung sofort an alle lebenden Tabs verteilt,
- nach YouTubes SPA-Navigation und Browser-Historiennavigation erneut
  angewendet,
- mit einem kurzen verzoegerten zweiten Lauf gegen spaet aufgebautes DOM
  abgesichert.

Da nur eine CSS-Klasse am Dokument umgeschaltet wird, ist fuer diese Funktion
kein dauerhafter eigener `MutationObserver` erforderlich.

## Grenzen und Wartungshinweise

- Die vier Branding-Selektoren gehoeren zu YouTubes nicht stabiler DOM. Wenn
  YouTube sie aendert, faellt nur diese optionale Bereinigung aus.
- `EngineHomeFeedSettings`, `setHomeFeedPreference()` und
  `applyHomeFeedSettingsToTabs()` tragen inzwischen auch die Watch-Option.
  Das ist funktional korrekt, aber begriffliche technische Schuld.
- Die Tap-Zonen veraendern direkt `HTMLVideoElement.currentTime` und
  `play()/pause()`. Zukuenftige Player-Aenderungen muessen auf echte
  Geraeteereignisse und YouTubes eigene Controls getestet werden.
- Die Watch-Anpassungen duerfen niemals durch Umschalten auf YouTubes mobile
  Watch-Seite vereinfacht werden, weil dadurch Desktop-Funktionen wie die
  Audio-Kanal-Auswahl verloren gehen koennen.

## Historie

`v1.2.0` beziehungsweise Commit `ddeb47e` veroeffentlichte:

- die Option zum Ausblenden des Kanal-Brandings,
- die Overlay-Vorbedingung fuer eigene Fullscreen-Taps,
- die bewusste Nichtaufnahme der instabilen Dislike-Anzeige.

## Regressionstest

1. Watch-Seite muss weiterhin im Desktop-Modus mit mobil angepasstem Layout
   laufen.
2. `Kanal-Branding ausblenden` ein- und ausschalten; bestehende Tabs muessen
   ohne Reload reagieren.
3. Bei deaktivierter Option muss YouTubes Branding unveraendert bleiben.
4. Bei verborgenem Player-Overlay darf der erste Tap nur die Steuerung
   einblenden.
5. Bei sichtbarem Overlay: Mitte einfach fuer Play/Pause, links und rechts
   mehrfach fuer Rueck-/Vorlauf testen.
6. Mehrere Taps muessen den Overlay-Ausblendtimer sinnvoll neu anstossen.
7. Player-Steuerung, Zahnrad-Menue und Audio-Kanal-Auswahl bedienen.
8. Long-Press/Cue und Pinch-to-Zoom im Landscape-Modus pruefen.
9. Das technische YouTube-Kontextmenue darf beim Zoom nicht erscheinen.
10. Es darf weder eine Dislike-Option noch eine Anfrage an eine
    Dislike-Drittanbieter-API geben.
