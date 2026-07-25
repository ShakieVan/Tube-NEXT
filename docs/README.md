# Projektdokumentation

Diese Datei ist der Einstiegspunkt fuer technische und historische
Projektinformationen. Ziel ist eine kleine Zahl kanonischer Dokumente statt
Wissen, das nur in Chats, Commits oder lokalen Code-Kommentaren auffindbar ist.

## Ablage

- `decisions/`: dauerhafte Produkt- und Architekturentscheidungen samt
  Begruendung, Grenzen und Folgen.
- `technical-notes/`: konkrete Erkenntnisse zu YouTube-DOM, GeckoView,
  Android-Verhalten, Workarounds und Regressionstests.
- `releases/`: nutzerorientierte Aenderungen je veroeffentlichter Version.
- `audit/`: Arbeitsindex fuer die schrittweise Auswertung alter Tasks und
  Chats. Er ist keine zweite technische Dokumentation.
- `archive/`: ueberholte oder rein historische Dokumente, die noch als
  Herkunftsnachweis gebraucht werden.

Bestehende Querschnittsdokumente:

- [`rebuild-log.md`](rebuild-log.md): historische Umsetzung und manuelle
  Abnahme des fruehen Neuaufbaus.
- [`decisions/geckoview-only-browser-engine.md`](decisions/geckoview-only-browser-engine.md):
  verbindliche Engine- und Medienarchitektur.
- [`hotrod-engine-migration.md`](hotrod-engine-migration.md): Verlauf der
  Umstellung von Android WebView auf GeckoView.
- [`technical-notes/geckoview-runtime-and-navigation.md`](technical-notes/geckoview-runtime-and-navigation.md):
  aktueller GeckoView-Laufzeit-, Session- und Navigationsvertrag.
- [`technical-notes/watch-fullscreen-lifecycle.md`](technical-notes/watch-fullscreen-lifecycle.md):
  heutige Aufgabenteilung und historische Erkenntnisse zu Watch,
  Landscape, Fullscreen und Lade-Overlay.
- [`technical-notes/watch-readiness-and-retries.md`](technical-notes/watch-readiness-and-retries.md):
  Grenzen von Lade-/Fertigerkennung und Regeln fuer sichere DOM-Nachlaeufe.
- [`technical-notes/youtube-responsive-layout-diagnostics.md`](technical-notes/youtube-responsive-layout-diagnostics.md):
  Live-Diagnose von Layout-, Sichtviewport und YouTube-Breakpoints.

## Was wird wo dokumentiert?

- Eine lokale Code-Entscheidung, die direkt aus dem Code hervorgeht:
  kurzer Code-Kommentar.
- Eine ueber mehrere Dateien oder spaetere Arbeiten relevante Erkenntnis:
  `technical-notes/`.
- Eine bewusst gewaehlte, dauerhaft zu schuetzende Richtung:
  `decisions/`.
- Sichtbare Aenderungen einer ausgelieferten Version:
  `releases/`.
- Noch nicht ausgewertete Aussagen aus einem alten Task:
  nur als Eintrag in `audit/chat-index.md`, bis sie geprueft und uebernommen
  wurden.

## Pflege

Technische Notizen nennen nach Moeglichkeit:

1. beobachtetes Verhalten,
2. ermittelte Ursache,
3. umgesetzte Loesung und ihren engen Geltungsbereich,
4. bewusst unberuehrte Funktionen,
5. manuelle oder automatisierte Regressionstests.

Aussagen aus alten Chats gelten nicht automatisch als aktueller Projektstand.
Sie werden gegen Code, Git-Historie und aktuelle Tests geprueft und als
`bestaetigt`, `historisch`, `ersetzt` oder `ungeprueft` eingeordnet.
