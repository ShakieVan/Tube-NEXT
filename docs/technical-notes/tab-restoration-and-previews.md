# Tab-Wiederherstellung und Vorschaubilder

Stand: am 25.07.2026 gegen `master`, `v1.0.1` und `v1.0.2` geprueft

## Persistierter Zustand

`TabPersistence` speichert fuer jeden Tab:

- ID,
- letzte URL,
- letzten Titel,
- Reihenfolge,
- ID des ausgewaehlten Tabs.

Der interne Gecko-Sitzungszustand wird nicht serialisiert. Cookies und Login
werden separat von Gecko verwaltet; beim Wiederherstellen eines Tabs wird
seine letzte URL neu geladen.

## Lazy Restore

Beim App-Start wird nur fuer den zuvor ausgewaehlten Tab sofort die URL
geladen. Fuer die anderen wiederhergestellten Tabs werden zwar Tab- und
Gecko-Session-Objekte angelegt, aber:

- die URL wird noch nicht geladen,
- die Session wird inaktiv und unfokussiert gesetzt,
- ihre View bleibt verborgen und nicht im sichtbaren Container.

Beim ersten Auswaehlen setzt `selectTab()` den Tab aktiv und laedt die
gespeicherte URL genau dann. Das reduziert Startzeit, Netzwerkverkehr und
JavaScript-Arbeit gegenueber dem frueheren gleichzeitigen Laden aller Tabs.
Es ist keine vollstaendige Speicher-Suspendierung, weil das Session-Objekt
bereits existiert.

Die spaeter hinzugekommene Hibernation unter Android-Speicherdruck kann eine
Gecko-Session vollstaendig zerstoeren. Sie ist in
[`background-resource-management.md`](background-resource-management.md)
dokumentiert und vom Start-Lazy-Load zu unterscheiden.

## Verhalten beim Tab-Wechsel

Vor dem Wechsel wird der bisherige Tab mit `setFocused(false)` und
`setActive(false)` heruntergeregelt. Der neue Tab wird aktiviert und als
einzige View an den sichtbaren Container gehaengt.

`suspendMediaWhenInactive(false)` erlaubt bewusst, dass laufende
Hintergrundmedien eines inaktiven Tabs weiterlaufen. Der Tab-Wechsel ist daher
kein pauschales Stoppen von Audio oder Video.

## Tab-Manager

Ein normaler Tap auf einen Eintrag:

1. waehlt den Tab aus,
2. erzeugt in diesem instabilen Umschaltmoment kein neues Bild des alten Tabs,
3. schliesst das Bottom Sheet.

Drag-and-drop und Scrollen bleiben RecyclerView-/ItemTouchHelper-Pfade und
werden durch den normalen Tap nicht ersetzt.

## Vorschau-Erzeugung

Vorschaubilder werden als `192 x 120` Pixel grosse JPEG-Dateien mit Qualitaet
72 im privaten Verzeichnis `files/tab_previews/` gespeichert. Beim Schliessen
eines Tabs wird sein Bild geloescht; beim Start werden Dateien unbekannter
Tab-IDs entfernt.

Fuer eine sichtbare GeckoView wird `capturePixels()` verwendet. Fuer andere
View-Typen existiert ein Canvas-Fallback. Beim Oeffnen des Tab-Managers wird
der sichtbare Tab aufgenommen; gespeicherte Bilder der anderen Tabs bleiben
erhalten.

Geckos Pixel-Capture ist asynchron. Das historische weisse Vorschaubild
entstand, weil das Ergebnis erst eintraf, nachdem der Tab bereits inaktiv
beziehungsweise kurz leer war. Deshalb wird beim Auswaehlen aus dem
Tab-Manager kein Capture des vorherigen Tabs gestartet.

## Blank-Filter

Ein neues Bild darf ein vorhandenes nur ersetzen, wenn eine Stichprobe nicht
nahezu leer ist. `isEffectivelyBlankPreview()` verwirft:

- mindestens 98 Prozent schwarze oder transparente Samples,
- mindestens 98 Prozent weisse oder transparente Samples,
- vollstaendig opake Bilder mit einer Luminanzspanne von hoechstens vier.

Damit werden schwarze, weisse und fast einfarbige Gecko-Zwischenbilder
abgefangen. Ein legitimes fast einfarbiges Seitenbild kann dadurch ebenfalls
verworfen werden; in diesem Fall ist das Beibehalten der letzten brauchbaren
Vorschau die konservativere Wahl.

## Grenzen

- Eine Vorschau ist ein zuletzt brauchbarer Snapshot, keine garantiert
  aktuelle Live-Ansicht.
- Der Tab-Manager aktualisiert ein gerade sichtbares Listenelement derzeit
  nicht mit dem asynchronen Ergebnis; das Bild steht spaetestens beim
  naechsten Oeffnen bereit.
- Bei noch nie geladenen Hintergrund-Tabs kann kein Seitenbild existieren.
- Die Wiederherstellung behaelt URL und Titel, nicht Scrollposition,
  Formulardaten oder einen exakten Gecko-History-Stack ueber einen
  vollstaendigen App-Neustart.

## Historie

`v1.0.1` beziehungsweise Commit `c539285` fuehrte Lazy Restore, stabilere
Previews und das Schliessen des Tab-Managers nach normalem Tap ein.
`v1.0.2` behielt diese Funktionen unveraendert bei.

## Regressionstest

1. Mehrere Tabs speichern, App beenden und neu starten.
2. Nur der zuletzt aktive Tab darf sofort laden; andere erst bei Auswahl.
3. Tab-Manager oeffnen, Tab normal antippen: Auswahl und Schliessen muessen
   funktionieren.
4. Eintraege per Long-Press verschieben und Liste scrollen.
5. Zwischen spielendem Video und anderem Tab wechseln; gute Vorschau darf
   nicht schwarz oder weiss ueberschrieben werden.
6. App neu starten und persistierte JPEG-Vorschauen pruefen.
7. Tab schliessen; zugehoerige Vorschau muss entfernt werden.
8. Hintergrund-Audio darf beim Wechsel zu einem anderen Tab weiterlaufen.
