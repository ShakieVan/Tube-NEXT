# Index der Projekt-Tasks

Inventur: 25.07.2026

| Datum | Task | Task-ID | Status | Vorgesehenes Ziel |
|---|---|---|---|---|
| 25.07.2026 | Fix Kommentar-Bearbeitung | `019f975e-aaa8-7f53-8847-c42020b765a1` | bestaetigt | [`youtube-comment-menu.md`](../technical-notes/youtube-comment-menu.md), [`desktop-watch-mobile-layout.md`](../decisions/desktop-watch-mobile-layout.md) |
| 18.07.2026 | Unterdruecke Kontextmenue im Fullscreen | `019f766b-dbb0-70a0-93a8-0c99d9b2612d` | bestaetigt (25.07.2026) | [`fullscreen-context-menu.md`](../technical-notes/fullscreen-context-menu.md) |
| 27.05.2026 | Schwarze Seite beheben | `019e69b3-d08f-7a41-b2c0-f47c7601dd67` | bestaetigt (25.07.2026) | [`gecko-black-surface-recovery.md`](../technical-notes/gecko-black-surface-recovery.md) |
| 25.05.2026 | Pruefe Hintergrundnutzung | `019e5d95-e3a5-7d81-b46a-b246b1011d22` | bestaetigt (25.07.2026) | [`background-resource-management.md`](../technical-notes/background-resource-management.md) |
| 25.05.2026 | Kommentar-Button ergaenzen | `019e5d66-97ca-78e3-9013-d3b9a1dbd59a` | bestaetigt (25.07.2026) | [`youtube-comment-navigation.md`](../technical-notes/youtube-comment-navigation.md) |
| 25.05.2026 | Update-Benachrichtigung hinzufuegen | `019e5d01-c4b7-7bf0-924a-3ccae6d63a2f` | bestaetigt (25.07.2026) | [`self-update-flow.md`](../technical-notes/self-update-flow.md) |
| 22.05.2026 | Player-Benachrichtigung fixen | `019e4df2-9c4d-7272-8100-77f1eeedc839` | bestaetigt (25.07.2026) | [`background-audio-notification.md`](../technical-notes/background-audio-notification.md) |
| 21.05.2026 | Watch-Seite-Optionen erweitern | `019e4ab5-8649-7961-a742-ba4bd04450f0` | bestaetigt (25.07.2026) | [`watch-page-options-and-taps.md`](../technical-notes/watch-page-options-and-taps.md), [`desktop-watch-mobile-layout.md`](../decisions/desktop-watch-mobile-layout.md) |
| 19.05.2026 | Optimierungen | `019e3ea4-3861-7422-b3bf-51de38a8b562` | bestaetigt (25.07.2026) | [`tab-restoration-and-previews.md`](../technical-notes/tab-restoration-and-previews.md), [`build-variants-and-geckoview-r8.md`](../technical-notes/build-variants-and-geckoview-r8.md), [`youtube-home-feed-filters.md`](../technical-notes/youtube-home-feed-filters.md) |
| 19.05.2026 | Neue Chat-Infos speichern | `019e3e9e-b95c-7662-bff3-2ff4ab735fec` | ersetzt (25.07.2026) | [`README.md`](README.md), [`AGENTS.md`](../../AGENTS.md) |
| 15.05.2026 | Android Studio-Fehler beheben | `019e2b0b-b6b9-77e1-8a63-bc79c782f642` | ohne-dauerwissen (25.07.2026) | kein Projektfehler; damaliger IDE-Directory-Lock |
| 17.03.2026 | Gecko Engine 2 | `019cfcd2-b9c3-7803-92a7-dd6e5480869c` | bestaetigt (25.07.2026) | [`geckoview-runtime-and-navigation.md`](../technical-notes/geckoview-runtime-and-navigation.md), bestehende Audio-, Surface- und Watch-Notizen |
| 17.03.2026 | Gecko Engine | `019cfc40-8477-7743-a05e-164bfa25ca39` | bestaetigt (25.07.2026) | [`geckoview-only-browser-engine.md`](../decisions/geckoview-only-browser-engine.md), [`hotrod-engine-migration.md`](../hotrod-engine-migration.md), [`AGENTS.md`](../../AGENTS.md) |
| 15.03.2026 | Trace fullscreen regression from RC1 | `019cf1f5-ec4f-7df3-b22b-fe409f83f30c` | ungeprueft | Fullscreen-/Watch-Notiz |
| 15.03.2026 | Ersetze Watch-Timer durch Check | `019cf138-481a-7601-9076-31bd7f0fb5c5` | ungeprueft | Watch-Lebenszyklus-Notiz |
| 15.03.2026 | Analysiere YouTube Desktop CSS | `019cf03e-705c-7441-bdab-6a42f6e84f47` | ungeprueft | Watch-Layout-Entscheidung |
| 13.03.2026 | Setze AGENTS-Anweisungen um | `019ce74d-92d1-78b0-ba1b-94bdb6035bf7` | ungeprueft | Arbeits- und Dokumentationsregeln |
| 13.03.2026 | Ueberpruefe AGENTS.md | `019ce743-346e-7960-a98c-9b89c5ba5858` | ungeprueft | Arbeits- und Dokumentationsregeln |

## Naechste Pruefgruppe

1. `019cf1f5-ec4f-7df3-b22b-fe409f83f30c`: Fullscreen-Regression
   seit RC1 gegen die heutige Watch-Implementierung abgleichen.
2. `019cf138-481a-7601-9076-31bd7f0fb5c5`: Watch-Timer und
   Lebenszyklus gegen die heutige WebExtension pruefen.
3. `019cf03e-705c-7441-bdab-6a42f6e84f47`: Desktop-CSS-Analyse
   gegen die verbindliche Watch-Layout-Entscheidung pruefen.
