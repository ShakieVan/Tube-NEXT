# Sprung zur YouTube-Kommentarsektion

Stand: seit Tube NEXT 1.3.1, am 25.07.2026 gegen den aktuellen Code geprueft

## Zweck und Einordnung

Auf Desktop-Watch-Seiten kann die Kommentarsektion weit unterhalb des Players
liegen und von YouTube erst beim Scrollen nachgeladen werden. Tube NEXT
ergaenzt deshalb einen Floating-Button mit Sprechblasen-Piktogramm, der den
Nutzer kontrolliert zu den Kommentaren fuehrt.

Der Button ist Bestandteil der GeckoView-WebExtension. Er ist kein natives
Android-Overlay und bleibt damit im selben DOM-/Viewport-Kontext wie die
Watch-Seite.

Diese Funktion ist von der Verwaltung eigener Kommentare getrennt. Bearbeiten
und Loeschen sind in
[`youtube-comment-menu.md`](youtube-comment-menu.md) dokumentiert.

## Sichtbarkeit

Der Kommentar-Button und der vorhandene Nach-oben-Button teilen Stil und
Overlay-Eventschutz, haben aber getrennte Sichtbarkeitsregeln:

- Nach oben erscheint erst ab `scrollY > 480`.
- Kommentare erscheint auf einer Watch-Seite bereits am Seitenanfang.
- Kommentare blendet aus, sobald der gefundene Kommentarbereich im Viewport
  liegt.
- Im Landscape-Watch-Modus bleibt der Kommentar-Button ausgeblendet, damit er
  Player, Zoom und Cue-Modus nicht stoert.

Bei Verlassen der Watch-Seite oder Wechsel in Landscape wird eine laufende
Kommentar-Suche beendet.

## Zielsuche

`findCommentsTarget()` prueft nacheinander:

- `#comments`
- `ytd-comments`
- `ytd-comments-header-renderer`
- das Engagement-Panel mit
  `target-id='engagement-panel-comments-section'`

Ein Element gilt nur als brauchbar, wenn es noch im Dokument liegt und eine
sichtbare Groesse oder erwartete Kommentar-Unterelemente besitzt. Damit wird
ein noch leerer YouTube-Platzhalter nicht vorschnell als fertiges Ziel
behandelt.

## Kontrolliertes Nachladen

Wenn der Kommentarbereich bereits existiert, scrollt Tube NEXT direkt dorthin
und korrigiert die Position nach 260 ms noch einmal, falls YouTubes dynamisches
Layout das Ziel verschoben hat.

Ist der Bereich noch nicht geladen:

1. Die Seite scrollt alle 480 ms um hoechstens etwa 78 Prozent der
   Viewport-Hoehe beziehungsweise mindestens 360 Pixel nach unten.
2. Nach jedem Schritt wird erneut nach einem brauchbaren Kommentarziel
   gesucht.
3. Nach zwei Schritten ohne weiteren Scrollfortschritt wird abgebrochen.
4. Spaetestens nach 8,5 Sekunden greift ein Fallback auf `#below` oder den
   Primaercontainer der Watch-Seite.

Der Timer ist nur waehrend dieser Benutzeraktion aktiv und wird danach
geloescht. Es gibt keinen dauerhaften Kommentar-Poller.

## Touch-Abgrenzung

Eigene `click`-, `pointerup`- und `touchend`-Ereignisse werden konsumiert und
starten den Sprung. `pointerdown`, `touchstart`, `mousedown` und
`contextmenu` werden ebenfalls am Button gestoppt. Dadurch gelangen diese
Gesten nicht in Player-Tap-, Landscape- oder Long-Press-Pfade.

## Historie und Validierung

- Der erste Stand koppelte die Sichtbarkeit zu stark an den
  Nach-oben-Button.
- Nach Nutzerrueckmeldung wurde die aktuelle Regel eingefuehrt: Kommentare
  bereits oben sichtbar, Ausblenden erst im Kommentar-Viewport.
- Debug- und Release-Build wurden vor `v1.3.1` erfolgreich erstellt.
- Der Nutzer bestaetigte die Funktion vor der Veroeffentlichung von `v1.3.1`.

## Regressionstest

1. Portrait-Watch-Seite ganz oben oeffnen: Kommentar-Button sichtbar,
   Nach-oben-Button noch unsichtbar.
2. Kommentar-Button antippen, bevor YouTube `#comments` erzeugt hat.
3. Kontrolliertes Nachladen und anschliessenden Sprung pruefen.
4. Wenn Kommentare sichtbar sind, muss der Button ausgeblendet sein.
5. Wieder nach oben scrollen: Kommentar-Button erscheint erneut.
6. Landscape-/Fullscreen-Modus pruefen: Kommentar-Button bleibt verborgen.
7. Nach-oben-Button, Cue-Modus, Zoom und normale Player-Taps pruefen.
8. Auf eine Nicht-Watch-Seite navigieren und sicherstellen, dass kein
   Kommentar-Timer weiterlaeuft.
