# Tab-Wiederherstellung und Vorschaubilder

Stand: am 01.08.2026 gegen `master` und die Debug-App auf dem Samsung
SM-S928B geprueft

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
erhalten. Trifft das asynchrone Ergebnis bei geoeffnetem Tab-Manager ein,
aktualisiert die Activity den betroffenen Listeneintrag sofort. Ein erneutes
Oeffnen ist dafuer nicht mehr erforderlich.

Die WebExtension sendet ausserdem `PAGE_PREVIEW_READY`, nachdem ihre
zeitlich begrenzte Layout-Stabilisierungsfolge beendet ist. Auf Watch-Seiten
liegt dieser Trigger damit hinter dem letzten Lauf, der
`tubenext-watch-fit` beziehungsweise den Landscape-Umbau anwendet. Die
Activity startet die Aufnahme nur, wenn der zugehoerige Tab zu diesem
Zeitpunkt noch die sichtbare, angehaengte GeckoView besitzt. Das Ergebnis
darf asynchron eintreffen und wird auch dann gespeichert, wenn inzwischen
ein anderer Tab ausgewaehlt wurde.

Kommt der Ready-Trigger erst nach dem Tabwechsel an, wird die Aufnahme als
ausstehend markiert und beim naechsten sichtbaren Aktivieren dieses Tabs
nachgeholt. Die App haengt dafuer keine zweite GeckoView im Hintergrund an
und erzeugt keinen Reload.

### Watch-Artwork fuer sehr schnelle Tabwechsel

Wird ein neuer Watch-Tab innerhalb der ersten Lade-Sekunde wieder verlassen,
existiert beim Ready-Trigger keine renderbare Gecko-Surface mehr. In diesem
Fall kann selbst ein spaeteres `capturePixels()` kein Bild erzeugen. Zwei
eng begrenzte Quellen liefern deshalb bereits im Hintergrund ein
Zwischenbild:

- Geckos offizielle `MediaSession.Metadata.artwork`, sobald YouTube sie setzt,
- ansonsten das kleine offizielle YouTube-Vorschaubild
  `https://i.ytimg.com/vi/<video-id>/mqdefault.jpg`.

Die Video-ID wird ausschliesslich aus einer validierten YouTube-Watch- oder
`youtu.be`-URL uebernommen. Der Loader akzeptiert nur einfache
YouTube-ID-Zeichen, baut einen festen `i.ytimg.com`-Pfad, folgt keinen
Redirects und begrenzt Antwortgroesse sowie Zeitlimits. Er greift weder auf
Video-/Audiostreams noch auf eine inoffizielle Resolver-API zu.

Artwork ist nur ein Fallback. Ein nach dem Layout-Ready-Trigger erfolgreich
erzeugter Seitenscreenshot derselben Ladegeneration hat Vorrang und darf von
spaeter eintreffendem Artwork nicht wieder ueberschrieben werden.

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
- Eine abghaengte GeckoView besitzt keine verlaesslich aufnehmbare Surface.
  Ein noch nicht gestarteter Snapshot kann deshalb nicht unsichtbar in einem
  anderen Tab erzwungen werden; er wird beim naechsten Aktivieren nachgeholt.
- Der Artwork-Fallback gilt nur fuer Watch-URLs mit eindeutig extrahierbarer
  YouTube-Video-ID. Fuer sehr schnell verlassene andere Seitentypen kann ohne
  vorherigen Snapshot weiterhin kein aktuelles Seitenbild existieren.
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
9. Tab-Manager geoeffnet lassen: Das Bild des aktiven Tabs muss nach Abschluss
   von `capturePixels()` ohne Schliessen und erneutes Oeffnen erscheinen.
10. Eine Watch-Seite laden und nach dem Layout-Ready-Trigger den Tab wechseln:
    Die fertige Aufnahme muss erhalten bleiben; ein spaeter Trigger eines
    bereits abgehaengten Tabs muss beim naechsten Aktivieren nachgeholt werden.
11. Aus einer Videoliste per Long-Tap-Rechts-Slide einen neuen Watch-Tab
    oeffnen, innerhalb etwa einer Sekunde ueber den Tab-Manager zur Liste
    zurueckwechseln und nach einigen Sekunden erneut oeffnen: Der neue Tab
    muss mindestens das Video-Artwork statt einer schwarzen Vorschau zeigen.

Der letzte Ablauf wurde am 01.08.2026 auf dem Samsung SM-S928B zunaechst per
Screenrecording als Fehler belegt und anschliessend mit demselben schnellen
Rueckwechsel gegen den Artwork-Fallback erfolgreich wiederholt.
