# Hot Rod Engine Migration (Background-Audio Fokus)

## Ziel
Die bisherige WebView-zentrierte Laufzeit wird durch eine einzelne, vollwertige Engine ersetzt.
Die App-Shell (Toolbar, Tabs, Persistenz, Link-Einstieg) bleibt erhalten.

## Leitprinzipien
- Keine Hybrid-Tabs im Produktbetrieb.
- Kleine, testbare Schritte.
- Stabilitaet und Sitzungs-Persistenz vor Komfortfunktionen.
- Background-Audio und Notification-Player sind Kernziele.

## Phase 0 (abgeschlossen in diesem Branch-Stand)
- Engine-Vertrag eingefuehrt:
  - `BrowserEngine`
  - `EngineTab`
  - `EngineCallbacks`
  - `EnginePlaybackState`
  - `EngineType`
- WebView als erste Engine-Implementierung hinter dem Vertrag vorbereitet:
  - `WebViewBrowserEngine`
- Background-Audio-Orchestrierung als eigenes Interface vorbereitet:
  - `BackgroundAudioCoordinator`

## Phase 1 (naechster Schritt)
- WebView-Anbindung hinter `BrowserEngine` kapseln.
- `MainActivity` nur noch gegen `EngineTab` programmieren.
- Keine direkten `WebView`-Typannahmen mehr in `MainActivity`.

Status:
- Erledigt als Hard-Cut-Variante.
- Laufzeit ist auf Gecko umgestellt.
- Der alte WebView-Engine-Pfad wurde entfernt.
- Einzelne UX-Funktionen, die vorher von WebView-HitTest abhaengig waren, sind vorerst reduziert.

## Phase 2
- GeckoView als zweite Implementierung von `BrowserEngine` integrieren.
- Session-, URL-, Titel- und Fortschritts-Callbacks auf App-Shell durchreichen.
- Vollbild-Events und Rotation engine-spezifisch adaptieren.

## Phase 3
- Engine-nativer Background-Media-Pfad:
  - MediaSession
  - Notification-Player
  - Bluetooth/Headset-Steuerung
- Fokusverlust-Handover:
  - Playback-State aufnehmen (URL/Zeit/Status)
  - Background-Playback fortsetzen
  - Beim Resume in die aktive Engine-Session zuruecksyncen

## Phase 4
- Gecko-only Runtime aktivieren (kein Mischbetrieb).
- WebView-Engine nur noch als Fallback-Branch oder komplett entfernen.

## Abnahme-Checkliste
- Desktop-YouTube startet stabil.
- Login bleibt nach App-Neustart erhalten.
- Mindestens zwei Tabs stabil nutzbar.
- Externe YouTube-Links landen in der App.
- Vollbild/Rotation ohne sichtbare Regression.
- Screen-Off + App-Hintergrund: Audio + Notification-Steuerung stabil.
