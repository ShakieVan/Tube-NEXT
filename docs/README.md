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
- [`technical-notes/login-consent-and-link-handling.md`](technical-notes/login-consent-and-link-handling.md):
  interne Login-/Consent-Flows, externe Ziele sowie Long-Press-Slider fuer Linkaktionen.
- [`technical-notes/android-youtube-link-association.md`](technical-notes/android-youtube-link-association.md):
  nutzergesteuerte Android-Zuordnung eingehender YouTube-Links und
  Variantenabgrenzung.
- [`technical-notes/canonical-back-forward-navigation.md`](technical-notes/canonical-back-forward-navigation.md):
  tabbezogene kanonische Zurueck-/Vorwaerts-History und Gecko-Fallback.
- [`technical-notes/tab-restoration-and-previews.md`](technical-notes/tab-restoration-and-previews.md):
  Lazy Restore, Gecko-Snapshot-Grenzen und Watch-Artwork fuer schnelle
  Hintergrund-Tabwechsel.
- [`technical-notes/build-variants-and-geckoview-r8.md`](technical-notes/build-variants-and-geckoview-r8.md):
  getrennte App-IDs, verpflichtende Produktionssignierung und der weiterhin
  unminifizierte GeckoView-Release-Build.
- [`technical-notes/release-and-diagnostic-deployment.md`](technical-notes/release-and-diagnostic-deployment.md):
  verbindlicher Ablauf fuer regulaere GitHub-Releases und die nur bei einer
  neuen Diagnosekampagne explizit reaktivierte Diagnosevariante.
- [`technical-notes/watch-fullscreen-lifecycle.md`](technical-notes/watch-fullscreen-lifecycle.md):
  heutige Aufgabenteilung und historische Erkenntnisse zu Watch,
  Landscape, Fullscreen und Lade-Overlay.
- [`technical-notes/fullscreen-progress-diagnostics.md`](technical-notes/fullscreen-progress-diagnostics.md):
  abgeschlossene Diagnose des sporadisch mehrzeilig umgebrochenen
  YouTube-Fortschrittsbalkens samt Opt-in-Reaktivierung.
- [`technical-notes/watch-readiness-and-retries.md`](technical-notes/watch-readiness-and-retries.md):
  Grenzen von Lade-/Fertigerkennung und Regeln fuer sichere DOM-Nachlaeufe.
- [`technical-notes/youtube-responsive-layout-diagnostics.md`](technical-notes/youtube-responsive-layout-diagnostics.md):
  Live-Diagnose von Layout-, Sichtviewport und YouTube-Breakpoints.
- [`technical-notes/privacy-preserving-watch-sharing.md`](technical-notes/privacy-preserving-watch-sharing.md):
  nativer Watch-Share-Link ohne Kontextparameter und eng begrenztes
  Ausblenden von YouTubes Teilen-Aktion.
- [`technical-notes/return-youtube-dislike-integration.md`](technical-notes/return-youtube-dislike-integration.md):
  optionale geschaetzte Dislike-Zahl mit Einwilligung, Stimmenprotokoll,
  Cache und konservativen lokalen Abfragelimits.

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
