# Fullscreen-Fortschrittsdiagnose

## Beobachtung

Auf einem realen Landscape-Screenshot vom 27. Juli 2026 sind die roten
YouTube-Fortschrittssegmente ueber zwei Zeilen verteilt. Der Abspielpunkt
scheint weiterhin zur Summe der roten Segmente zu passen; fehlerhaft ist
wahrscheinlich die Geometrie der segmentierten Fortschrittsanzeige und nicht
die Medienzeit selbst.

Eine manuelle Aenderung der Videoaufloesung reproduzierte den Zustand nicht.
Eine dynamische Neuerstellung der Kapitel-, Werbe- oder Playersegmente bleibt
eine Hypothese. Insbesondere muss noch geklaert werden, ob YouTubes intern
berechnete Segmentbreiten zeitweise mit der von Tube NEXT begrenzten
`.ytp-chrome-bottom`-Breite kollidieren.

Bis ein Ereignis mit Messwerten vorliegt, wird die Player-CSS nicht auf
Verdacht geaendert.

## Diagnosevariante

Der Buildtyp `diagnosticRelease` verwendet dieselbe App-ID und dieselbe
Produktionssignierung wie der normale Release, traegt aber den sichtbaren
Versionszusatz `-diagnostic`. Ein regulaeres Update mit hoeherem VersionCode
kann ihn ohne Datenverlust ersetzen.

Nur dieser Buildtyp aktiviert die Fortschrittsdiagnose. Die WebExtension
prueft im Landscape-Watch-Modus in einem Intervall von 2,5 Sekunden:

- ob sichtbare `.ytp-play-progress`-Segmente auf mehreren vertikalen Zeilen
  liegen,
- ob sichtbare `.ytp-load-progress`-Segmente auf mehreren Zeilen liegen,
- ob `.ytp-progress-list` unerwartet vertikal ueberlaeuft.

Ein Befund muss in zwei aufeinanderfolgenden Messungen bestehen. Pro
zusammenhaengender Fehlerphase wird nur ein Ereignis gemeldet; zusaetzlich
gilt eine Mindestpause von fuenf Minuten.

## Inhalt und Aufbewahrung

Ein Ereignis enthaelt ausschliesslich:

- Zeitpunkt, App-Version und interne Tab-ID,
- YouTube-Video-ID und URL-Pfad, aber keine vollstaendige URL,
- Medienzeit, Dauer und Pausezustand,
- Viewport-, Zoom- und Player-Geometrie,
- begrenzte Klassennamen und berechnete Layoutwerte der relevanten
  Fortschrittselemente.

Cookies, Accountdaten, Seitentexte, Titel, Kommentare und Medieninhalte werden
nicht gespeichert.

Die App haelt maximal 20 Ereignisse als JSON Lines unter
`files/diagnostics/fullscreen-progress-layout.jsonl`. Neue Ereignisse
verdraengen die aeltesten atomar. Der private App-Speicher uebersteht
Neustarts und signierte App-Updates; nur die Schaltflaeche in den Einstellungen
oder eine Deinstallation loescht das Protokoll. In den Einstellungen werden
Zaehler, Teilen und explizites Loeschen angeboten.

## Regression

`ProgressLayoutDiagnosticStoreTest` prueft Ringbegrenzung, erneutes Oeffnen,
ungueltige beziehungsweise uebergrosse Nutzdaten sowie das Loeschen. Vor dem
Installieren der Diagnosefassung muessen ausserdem JavaScript-Syntax,
WebExtension-Manifest, Unit-Tests, Release-Lint, Produktionssignatur, App-ID
und Versionsname geprueft werden.
