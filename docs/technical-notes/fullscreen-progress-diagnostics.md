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

Bis zur folgenden Auswertung wurde die Player-CSS bewusst nicht auf Verdacht
geaendert.

## Auswertung der realen Ereignisse vom 07.08.2026

Sieben gespeicherte Ereignisse vom 02. bis 07.08.2026 und ein realer
Screenshot vom 06.08.2026 grenzen die Ursache deutlich ein:

- alle Ereignisse entstanden bei `798 x 384` CSS-Pixeln und
  `devicePixelRatio = 1.9`,
- der Tube-NEXT-Videozoom stand immer auf `1` ohne Verschiebung,
- der Player war etwa `797.9` CSS-Pixel und die durch je 12 Pixel Seitenrand
  begrenzte Fortschrittsleiste etwa `773.9` CSS-Pixel breit,
- Geckos ganzzahliges `clientWidth` der Leiste war gleichzeitig `774`,
- YouTube teilte die Kapitelbreiten samt 4-Pixel-Abstaenden in jedem Ereignis
  auf exakt diese ganzzahligen `774` Pixel auf,
- dadurch ueberschritt die Kapitelzeile die reale Subpixelbreite um ungefaehr
  `0.1` Pixel und das jeweils letzte Kapitel sprang sechs CSS-Pixel nach unten.

In sechs Ereignissen war im umgebrochenen letzten Kapitel nur der graue
Ladefortschritt sichtbar. In einem Ereignis waren Lade- und roter
Abspielfortschritt zweizeilig. Das erklaert, warum sichtbarer Fortschritt und
maximaler Balken zeitweise nicht zusammenpassen. Unterschiedliche Videos mit
vier bis siebzehn Kapiteln waren betroffen; eine Aufloesungsaenderung oder der
Tube-NEXT-Zoom sind damit als Ursache widerlegt.

Ein Fix muss eng auf den Landscape-Player begrenzt verhindern, dass YouTubes
Kapitelcontainer an dieser Subpixel-Rundungsgrenze umbrechen. Die
Medienposition, Kapitelbreiten und Videotransformation selbst sollen dabei
nicht neu berechnet werden.

## Diagnosevariante

Der Buildtyp `diagnosticRelease` verwendet dieselbe App-ID und dieselbe
Produktionssignierung wie der normale Release, traegt aber den sichtbaren
Versionszusatz `-diagnostic`. Ein regulaeres Update mit hoeherem VersionCode
kann ihn ohne Datenverlust ersetzen.

Solange diese Diagnosekampagne aktiv ist, wird bei jedem regulaeren Release
aus demselben Stand auch `diagnosticRelease` gebaut. Auf GitHub werden nur die
normalen Release-APKs veroeffentlicht; anschliessend wird die arm64-Diagnose-APK
per `adb install -r` auf das bekannte Testtelefon aktualisiert. Versionierung,
Signaturpruefung, Geraeteauswahl und Schutz der vorhandenen Diagnosedaten sind
verbindlich in
[`release-and-diagnostic-deployment.md`](release-and-diagnostic-deployment.md)
dokumentiert.

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

Zusaetzlich kann die Diagnosefassung eine lokale Kopie direkt ueber Androids
`MediaStore.Downloads` unter `Download/Tube NEXT/` speichern. Dafuer ist ab
Android 10 keine allgemeine Speicherberechtigung notwendig. Der Export erhaelt
einen Zeitstempel im Dateinamen; das interne Ringprotokoll bleibt unveraendert.

## Regression

`ProgressLayoutDiagnosticStoreTest` prueft Ringbegrenzung, erneutes Oeffnen,
ungueltige beziehungsweise uebergrosse Nutzdaten sowie das Loeschen. Vor dem
Installieren der Diagnosefassung muessen ausserdem JavaScript-Syntax,
WebExtension-Manifest, Unit-Tests, Release-Lint, Produktionssignatur, App-ID
und Versionsname geprueft werden.
