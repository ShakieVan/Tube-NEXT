# Technisches YouTube-Kontextmenue beim Landscape-Zoom

Stand: Tube NEXT 1.3.7, am 25.07.2026 gegen den aktuellen Code geprueft

## Beobachtung

Beim Pinch-to-Zoom im immersiven Landscape-Player erschien gelegentlich
YouTubes technisches Player-Kontextmenue mit Eintraegen wie `Statistiken fuer
Interessierte`. Zwei Finger konnten von GeckoView beziehungsweise YouTubes
Player-Ereignispfad zeitweise wie ein Long-Press behandelt werden.

Ein erster Fix, der nur das vorhandene `contextmenu`-Handling auf
Player-Bedienelemente ausdehnte, reichte nicht aus. Das Menue erschien beim
anschliessenden Geraetetest erneut.

## Ermittelte Ursache

Ein Screenshot vom verbundenen Testgeraet identifizierte das sichtbare Element
als YouTubes eigenes `.ytp-contextmenu`. Es war weder ein natives
Android-Kontextmenue noch GeckoViews Auswahlmenue.

Die erste Sperre hing ausserdem zu eng an `isLandscapeWatch()`. Dieser Zustand
war waehrend einzelner Zoom-Ereignisse nicht verlaesslich gesetzt, obwohl der
Player sichtbar im Vollbild war. Eine Ausnahme fuer Player-Bedienelemente liess
zusaetzlich einen Ereignispfad offen.

## Loesung

Die WebExtension sichert den Pfad zweistufig ab:

1. `handleLandscapeCueContextMenu()` behandelt `contextmenu` bereits auf
   `window` und danach auf `document` in der Capture-Phase.
2. `.ytp-contextmenu` wird unter
   `html.tubenext-landscape-watch` per CSS ausgeblendet und nimmt keine
   Pointer-Ereignisse an.

`isFullscreenPlayerMode()` erkennt mehrere gleichwertige Vollbildzustaende:

- den von Tube NEXT berechneten Landscape-Watch-Zustand,
- `document.fullscreenElement`,
- die Klasse `tubenext-landscape-watch`,
- YouTubes Player-Klasse `ytp-fullscreen`.

Im Vollbild wird jedes technische Kontextmenue konsumiert. Ein Cue-Punkt wird
aber weiterhin nur gesetzt, wenn der Long-Press nicht auf einem
Player-Bedienelement begann. Damit bleiben Controls vor versehentlichen
Cue-Aktionen geschuetzt.

## Abgrenzung zu anderen Menues

Die CSS-Regel gilt nur fuer `.ytp-contextmenu` im immersiven
Landscape-Watch-Modus. Sie unterdrueckt nicht:

- das normale Zahnrad-/Video-Einstellungsmenue,
- Kommentar-Dropdowns und Kommentar-Dialoge,
- allgemeine YouTube-Menues ausserhalb des Landscape-Players.

Diese Abgrenzung ist fuer die Produktentscheidung
[`desktop-watch-mobile-layout.md`](../decisions/desktop-watch-mobile-layout.md)
verbindlich.

## Validierung

- JavaScript-Syntax, Manifest und Android-Build wurden vor `v1.3.7` geprueft.
- Der erste, zu enge Fix fiel im Test auf einem Samsung SM-S928B durch.
- Die zweistufige Variante wurde anschliessend auf demselben Geraet getestet
  und vom Nutzer als funktionierend bestaetigt.
- `v1.3.7` wurde mit genau diesem Stand veroeffentlicht.

## Regressionstest

1. Watch-Seite in Landscape und immersiven Vollbildmodus bringen.
2. Mehrfach Pinch-to-Zoom beginnen, auch ueber sichtbaren Player-Controls.
3. Sicherstellen, dass `.ytp-contextmenu` nicht erscheint.
4. Normales Video-Einstellungsmenue oeffnen und bedienen.
5. Long-Press auf freier Videoflaeche pruefen: Cue-Punkt wird gesetzt.
6. Long-Press auf Controls pruefen: kein Kontextmenue und kein Cue-Punkt.
7. Kommentar- und sonstige YouTube-Menues ausserhalb des Players pruefen.

## Wartungshinweis

Wenn das Problem erneut auftritt, zuerst per Screenshot oder DOM-Inspektion
bestimmen, ob YouTube den Klassennamen oder den Ereignispfad geaendert hat.
Die Sperre nicht pauschal auf alle Menues ausweiten.
