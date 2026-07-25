# Hot-Rod-Migration von WebView zu GeckoView

Status: historische Migrationsnotiz; die aktuelle verbindliche Entscheidung
steht in
[`decisions/geckoview-only-browser-engine.md`](decisions/geckoview-only-browser-engine.md).

## Ausgangslage

Die fruehe Tube-NEXT-App verwendete Android WebView. Hintergrundwiedergabe
blieb trotz Foreground-Service, JavaScript-Zustandsabfrage und Media-Key-
Experimenten unzuverlaessig. Ein Custom-Tab-Test konnte Audio zwar besser im
Hintergrund halten, passte aber nicht zur eigenen Tab-Verwaltung und zum
einheitlichen App-Erlebnis.

Der Arbeitsbranch `codex/background-audio` wurde deshalb im Maerz 2026 als
"Hot Rod" fuer einen vollstaendigen Motorwechsel verwendet.

## Migrationsverlauf

### Engine-Vertrag

Commit `119cd8f` fuehrte die Trennung zwischen App-Shell und Browser-Laufzeit
ein:

- `BrowserEngine`
- `EngineTab`
- `EngineCallbacks`
- `EnginePlaybackState`
- `EngineType`
- `BackgroundAudioCoordinator`

Ein kurzfristiger WebView-Adapter machte die Entkopplung der `MainActivity`
kompilierbar. Er war eine Migrationsstuetze, kein geplanter Hybridbetrieb.
Der Tag `hotrod-baseline-1` markierte diesen Zwischenstand.

### Hard-Cut

Der auf der Festplatte entstandene Gecko-Hard-Cut wurde mit `5c441fa`
gesichert:

- GeckoView-Abhaengigkeit und Mozilla-Maven-Repository aufgenommen,
- `GeckoBrowserEngine` als einzige Runtime eingebunden,
- Tabs auf `AppTab` und `EngineTab` umgestellt,
- alte WebView-Factory, Clients, Tabklasse und Engine-Implementierung
  entfernt,
- WebView-spezifische JavaScript- und Fullscreen-Annahmen zunaechst
  deaktiviert.

Damit gab es bewusst keinen Produktstand mit gemischten WebView- und
Gecko-Tabs.

### Stabilisierung

Die folgende Runde reparierte URL-Quelle, User-Agent-Modus,
Single-Page-Navigation, Watch-Layout, Landscape, Tab-Vorschauen,
Hintergrund-Audio und den Gecko-Lebenszyklus. Die heute gueltigen Regeln
stehen nicht in dieser Chronik, sondern in:

- [`technical-notes/geckoview-runtime-and-navigation.md`](technical-notes/geckoview-runtime-and-navigation.md)
- [`technical-notes/background-audio-notification.md`](technical-notes/background-audio-notification.md)
- [`decisions/desktop-watch-mobile-layout.md`](decisions/desktop-watch-mobile-layout.md)
- [`technical-notes/gecko-black-surface-recovery.md`](technical-notes/gecko-black-surface-recovery.md)

## Verworfen

- WebView und Gecko dauerhaft parallel betreiben,
- Watch-Seiten als Custom Tabs auslagern,
- einen YouTube-Stream fuer einen eigenen Audio-Player extrahieren,
- alte WebView-JavaScript-Aufrufe ueber `javascript:`-Navigation nachbilden.

## Ergebnis

Die App-Shell mit Toolbar, Tabs, Persistenz und Intent-Einstieg blieb erhalten.
GeckoView uebernahm vollstaendig Rendering, Web-Media und Sitzungen. Die
Android-Medienbenachrichtigung steuert die Gecko-Wiedergabe, statt einen
zweiten Player zu betreiben.
