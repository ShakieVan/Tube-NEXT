# Chat-Audit

Der Chat-Audit ueberfuehrt belastbares Projektwissen aus alten Codex-Tasks in
die kanonische Dokumentation. Er wird nach dem jeweils letzten bekannten guten
Release in kleinen, separat committeten Schritten bearbeitet.

## Ablauf pro Task

1. Genau einen Task vollstaendig lesen.
2. Nur Aussagen mit kuenftigem technischem oder produktbezogenem Wert als
   Kandidaten notieren.
3. Jeden Kandidaten gegen aktuellen Code, Git-Historie und vorhandene Tests
   pruefen.
4. Bestaetigte Erkenntnisse in `docs/decisions/`,
   `docs/technical-notes/`, `docs/releases/` oder ein anderes kanonisches
   Dokument uebernehmen.
5. Den Eintrag in [`chat-index.md`](chat-index.md) mit Status, Ziel und
   Pruefdatum aktualisieren.
6. Dokumentation und Index gemeinsam committen, bevor der naechste Task
   ausgewertet wird.

Vollstaendige Chat-Transkripte werden nicht ins Repository kopiert. Der Index
enthaelt nur Herkunft, Bearbeitungsstand und Ziel der verdichteten Erkenntnis.

## Status

- `ungeprueft`: Task wurde nur inventarisiert.
- `in-pruefung`: Inhalt wird gerade mit Projektstand und Historie abgeglichen.
- `bestaetigt`: relevante Erkenntnisse wurden in kanonische Dokumente
  uebernommen.
- `historisch`: Inhalt beschreibt einen alten Stand, bleibt aber als Kontext
  nuetzlich.
- `ersetzt`: spaetere Implementierung oder Entscheidung hat die Aussage
  abgeloest.
- `ohne-dauerwissen`: geprueft, aber keine zusaetzliche Projektnotiz noetig.
- `nicht-verfuegbar`: Quelle ist nicht mehr lesbar.

## Reihenfolge

Zuerst werden Tasks mit aktuellem Regressionsrisiko ausgewertet, danach
Architektur- und Produktentscheidungen, zuletzt reine Aufbau- und
Release-Historie. Ein Task darf mehrere kanonische Ziele haben; der Index
verlinkt dann alle.

## Abdeckung und Grenzen

Die Erstinventur vom 25.07.2026 umfasst lokale Codex-Sitzungsdateien, in denen
der exakte Projektname `Tube-NEXT` vorkommt. Sie erfasst damit auch Tasks, die
in der aktuellen Seitenleiste nicht mehr unter den 50 juengsten Eintraegen
liegen.

Nicht automatisch enthalten sind:

- geloeschte oder nicht mehr lokal vorhandene Tasks,
- Chats auf nicht verbundenen Rechnern oder Hosts,
- ChatGPT-Chats ohne eindeutigen Bezug zum lokalen Projektpfad,
- Inhalte von Anhaengen, die in der Sitzung nicht mehr verfuegbar sind.

Solche Quellen koennen spaeter manuell mit Task-ID und Herkunft in den Index
aufgenommen werden.
