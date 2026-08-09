# Watch-Seiten-Optionen und Fullscreen-Taps

Stand: am 09.08.2026 gegen den aktuellen Arbeitsstand geprueft; Aussagen zu
`v1.2.0` bleiben als historische Abgrenzung erhalten

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

## Historischer Dislike-Prototyp und heutige Option

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

Diese Entfernung bleibt fuer `v1.2.0` historisch richtig. Fuer `v1.4.5` wurde
die Funktion mit ausdruecklicher
Einwilligung, stabilerer Renderer-Erkennung, Stimmenrueckmeldung und
konservativen lokalen Abfragelimits neu umgesetzt. Der kanonische heutige
Vertrag steht in
[`return-youtube-dislike-integration.md`](return-youtube-dislike-integration.md).

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

### Bildschirmfeste Zonen bei nativem Zoom

Der bisherige immersive Pinch-to-Zoom skalierte und verschob die native
GeckoView. Ein DOM-`clientX` bezeichnete in diesem Zustand weiterhin eine
Position im transformierten Browser-Viewport. Dadurch verschoben sich die
Tap-Zonen zusammen mit dem vergroesserten Video.

Ein erster Versuch mit DOM-`screenX` reichte unter GeckoView nicht aus: Ein
Geraetetest auf dem Samsung SM-S928B zeigte, dass auch diese Koordinate nach
der nativen View-Transformation nicht verlaesslich den sichtbaren Tap-Punkt
abbildet.

Die stabile Loesung skaliert nicht mehr die komplette GeckoView. Die native
Ebene erkennt weiterhin Pinch und Verschieben, uebermittelt Skalierung und
Translation aber an die WebExtension. Nur das `video`-Element erhaelt dort
die entsprechende CSS-Transformation. Controls, Fortschrittsleiste, Menues,
Captions und Tube-NEXT-Overlays bleiben im unveraenderten Viewport.

Damit entspricht `clientX` wieder direkt der sichtbaren Bildschirmposition
innerhalb des `visualViewport`; dessen `width` und `offsetLeft` bilden die
sichtbaren Grenzen fuer die Drittelwahl. Die DOM-Zielpruefung bleibt getrennt,
sodass echte Player-Bedienelemente weiterhin unveraendert bedient werden.

Bei der Umstellung zeigte der Geraetetest zusaetzlich eine reine
Uebergaberegression: Der Touchstart speicherte die Koordinate voruebergehend
als `x`, waehrend die Zonenermittlung `clientX` las. Der Fallbackwert `0`
ordnete dadurch jeden freien Tap der linken Zone zu. Touchzustand und
Zonenermittlung verwenden nun durchgehend `clientX` und `clientY`.

### Geraetevalidierung vom 02.08.2026

Auf einem Samsung SM-S928B mit GeckoView wurde die Video-only-Transformation
in der Debug-App bestaetigt:

- Pinch und Ein-Finger-Pan skalieren beziehungsweise verschieben nur das
  Videobild.
- YouTube-Overlay, Fortschrittsleiste und Controls bleiben unskaliert am
  Bildschirm.
- Mitteltaps wurden mit `clientX` um 400 bei einer sichtbaren Breite um 798
  korrekt als mittlere Zone erkannt und loesten nachweislich Play/Pause aus.
- Mehrfach-Taps bei etwa 150 beziehungsweise 650 wurden korrekt als linke und
  rechte Zone erkannt und loesten Rueck- beziehungsweise Vorlauf aus.

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
6. Auf mindestens zweifache Vergroesserung zoomen, horizontal bis an beide
   Grenzen verschieben und erneut in der sichtbaren Bildschirmmitte
   Play/Pause sowie an den sichtbaren Bildschirmraendern Rueck-/Vorlauf
   pruefen. Die Zonen duerfen nicht mit dem Video wandern.
7. Im selben Zoomzustand Player-Controls und Fortschrittsleiste einblenden:
   Das Video darf vergroessert und verschoben bleiben, waehrend das gesamte
   Overlay unskaliert am Bildschirm verbleibt.
8. Mehrere Taps muessen den Overlay-Ausblendtimer sinnvoll neu anstossen.
9. Player-Steuerung, Zahnrad-Menue und Audio-Kanal-Auswahl bedienen.
10. Long-Press/Cue und Pinch-to-Zoom im Landscape-Modus pruefen.
11. Das technische YouTube-Kontextmenue darf beim Zoom nicht erscheinen.
12. `Geschätzte Dislikes anzeigen (Return YouTube Dislike)` muss
    standardmaessig aus sein; ohne Aktivierung darf kein RYD-Abruf erfolgen.
13. Beim ersten Aktivieren den Datenschutzhinweis einmal abbrechen und einmal
    bestaetigen. Nur die Bestaetigung darf die Funktion einschalten.
14. Mit aktiver Option auf mindestens zwei Watch-Seiten samt SPA-Wechsel die
    jeweils passende Zahl im vorhandenen Dislike-Segment pruefen.
15. Option ohne Reload deaktivieren; YouTubes urspruenglicher Button muss
    wiederhergestellt werden.
16. Cache-, 429- und Abbruchverhalten nach
    [`return-youtube-dislike-integration.md`](return-youtube-dislike-integration.md)
    automatisiert pruefen. Stimmen nur auf eigenen oder ausdruecklichen
    Testvideos abnehmen.
