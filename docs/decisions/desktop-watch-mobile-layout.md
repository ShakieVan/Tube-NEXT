# Desktop-Watch mit mobilem Layout

Status: verbindlich

## Entscheidung

Tube NEXT laedt Video-Watch-Seiten weiterhin als YouTube-Desktop-Seite. Die
WebExtension passt Darstellung und Bedienung an grosse Touch-Smartphones an,
ohne die Seite funktional auf YouTubes Mobile-Watch-Variante umzustellen.

Normale YouTube-Bereiche duerfen weiterhin die mobile Darstellung verwenden.
Die Watch-Seite ist die bewusst gewaehlte Ausnahme.

## Grund

Der Desktop-Watch-Modus stellt Funktionen bereit, die in der mobilen
YouTube-Webseite fehlen oder anders eingeschraenkt sind. Dazu gehoert
insbesondere die Auswahl verfuegbarer Audio-Kanaele. Genau die Verbindung aus
Desktop-Funktionsumfang und mobil brauchbarer Bedienung ist ein wesentlicher
Produktvorteil von Tube NEXT.

Die historische Laufzeitanalyse zeigte fuer den fehlerhaften globalen
Desktop-Ansatz einen Layout-Viewport von etwa 980 CSS-Pixeln bei nur etwa 411
sichtbaren CSS-Pixeln. YouTubes Desktop-Breakpoints reagierten darauf korrekt,
die Inhalte passten aber nicht auf das Display. Messmethode und technische
Folgerungen stehen in
[`../technical-notes/youtube-responsive-layout-diagnostics.md`](../technical-notes/youtube-responsive-layout-diagnostics.md).

## Grenzen fuer Aenderungen

- Layout-, Viewport-, CSS- und Touch-Anpassungen duerfen den Desktop-Modus
  nicht versehentlich in den Mobile-Modus umschalten.
- Eingriffe in YouTube-Ereignisse muessen auf den kleinstmoeglichen Renderer,
  Endpoint oder Betriebszustand begrenzt werden.
- Das normale Video-Einstellungsmenue muss bedienbar bleiben.
- Das technische YouTube-Kontextmenue, das beim Pinch-to-Zoom im immersiven
  Landscape-Player stoert, bleibt unterdrueckt.
- Kommentar-, Player- und Landscape-Kontextmenues sind getrennte
  Interaktionspfade. Ein Fix fuer einen Pfad darf nicht pauschal alle Menues
  oder Dialoge behandeln.

## Mindestpruefung nach Watch-Aenderungen

1. Watch-Seite nutzt weiterhin Desktop-Funktionen und das angepasste mobile
   Layout.
2. Auswahl unterschiedlicher Audio-Kanaele ist weiterhin erreichbar, sofern
   das Video sie anbietet.
3. Normales Video-Einstellungsmenue oeffnet und schliesst korrekt.
4. Pinch-to-Zoom im Landscape-Modus oeffnet nicht das technische
   YouTube-Kontextmenue.
5. Cue-Long-Press und Zoom funktionieren weiterhin.
6. Kommentare lassen sich erstellen; eigene Kommentare lassen sich bearbeiten
   und bis zur Loeschbestaetigung loeschen.
