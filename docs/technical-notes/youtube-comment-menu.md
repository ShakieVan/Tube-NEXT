# Bearbeiten und Loeschen eigener YouTube-Kommentare

Stand: Tube NEXT 1.3.8

## Beobachtung

Auf der Desktop-Watch-Seite liess sich das Drei-Punkte-Menue eines eigenen
Kommentars oeffnen. Beim Antippen von `Bearbeiten` geschah in GeckoView jedoch
nichts Sichtbares. Die naheliegende Vermutung eines zwar erzeugten, aber per
CSS verdeckten Dialogs hat sich nicht bestaetigt.

## Ursache

YouTubes Polymer-Komponente
`ytd-menu-navigation-item-renderer` erzeugt fuer diese Eintraege ein
synthetisches `tap`-Ereignis. In GeckoView wurde der Menueeintrag erreicht,
aber YouTubes zugehoerige Endpoint-Behandlung nicht verlaesslich ausgefuehrt.
Darum wurde weder der Inline-Editor noch der Loeschdialog gestartet.

Die relevanten YouTube-Endpunkte sind:

- `updateCommentDialogEndpoint`
- `updateCommentReplyDialogEndpoint`
- ein `confirmDialogEndpoint`, dessen Bestaetigungsbutton einen
  `performCommentActionEndpoint` traegt

## Loesung

Die WebExtension lauscht in der Capture-Phase auf echte Polymer-`tap`-Events.
Nur wenn der Ereignispfad einen der oben genannten Kommentar-Endpunkte
enthaelt, ruft sie YouTubes bereits vorhandenen Handler `onEndpointTap_` auf.
Anschliessend wird das zugehoerige Dropdown geschlossen.

Die Loesung baut keinen eigenen Editor oder Loeschdialog nach und veraendert
keine Kommentar-API. Sie sorgt lediglich dafuer, dass YouTubes vorhandener
Desktop-UI-Pfad in GeckoView erreicht wird.

## Bewusst unberuehrt

- Desktop-Watch-Modus und dessen mobiles Layout
- Auswahl von Audio-Kanaelen
- normales Video-Einstellungsmenue
- Unterdrueckung des technischen Player-Kontextmenues beim Zoom im
  Landscape-Modus
- Cue-Long-Press und sonstige Player-Gesten

## Validierung

Manuell auf einem Samsung SM-S928B mit der parallel installierten Debug-App:

1. Eigenen Kommentar in der Watch-Ansicht geoeffnet.
2. `Bearbeiten` gewaehlt: YouTubes Inline-Editor erschien mit vorhandenem Text
   und Bildschirmtastatur.
3. `Loeschen` gewaehlt: YouTubes Dialog `Kommentar loeschen` erschien.
4. Den Loeschvorgang abgebrochen; der Test hat keinen echten Kommentar
   geloescht.
5. Geprueft, dass das Drei-Punkte-Dropdown nicht hinter Editor oder Dialog
   offen bleibt.

## Regressionsrisiko

Die verwendeten Renderer-, Endpoint- und Handlernamen gehoeren zu YouTubes
interner Web-Oberflaeche und koennen sich ohne Ankuendigung aendern. Bei einem
erneuten Ausfall zuerst den `tap`-Ereignispfad und die aktuellen
`navigationEndpoint`-Daten untersuchen. Nicht vorsorglich alle
`ytd-menu-navigation-item-renderer` behandeln, da dies andere YouTube-Menues
und die in der Produktentscheidung geschuetzten Player-Interaktionen
beeintraechtigen koennte.
