# RC1 Rebuild Log

## Baseline
- Branch: `codex/rc1-rebuild`
- Base commit: `e7766ad` (RC1)

## Block A: Punkte 1-4 (Mobile/Standard, Login stabil, Tap->Watch, Back-Historie)
Status: `in RC1 bereits vorhanden` (kein Code-Delta zu RC2 in diesen Kernpunkten)

### Code-Stellen
- Mobile/Watch Modus:
  - `MainActivity.shouldUseDesktopMode(...)`
  - `MainActivity.normalizeInternalYouTubeUrl(...)`
  - `MainActivity.applyBrowsingMode(...)`
- Login/Auth-Schutz:
  - `MainActivity.isYouTubeAuthenticationPath(...)`
  - `YouTubeWebViewClient.shouldOverrideUrlLoading(...)`
  - `LinkInterceptor.isInternalFlowUri(...)`
- Tap -> Watch / SPA:
  - `YouTubeWebViewClient.doUpdateVisitedHistory(...)`
- Tab-Historie / Zurueck:
  - `BrowserTab.navigationHistory`, `historyIndex`, `pendingHistoryNavigation`
  - `MainActivity.recordTabHistory(...)`
  - `MainActivity.navigateBackInTabHistory(...)`

### Test-Checkliste (manuell)
1. App starten, Startseite und normale Bereiche bleiben mobil (`m.youtube.com`).
2. Video antippen -> Watch-Seite auf `www.youtube.com/watch?...` mit `app=desktop`.
3. Login durchfuehren und App neu starten -> weiterhin eingeloggt.
4. In Watch mehrmals intern navigieren, dann Back druecken -> Verlaufsschritte statt Pendeln zwischen zwei URLs.

### Cache/Storage Hinweis
- Fuer Block A ist **kein** `Clear cache`/`Clear storage` vorgesehen.
- Nur wenn Login-Session aus alten Builds sichtbar „haengt“, einmalig:
  1. Android App-Info -> Speicher -> Cache leeren.
  2. **Storage nur als letzte Option**, da Login/Cookies verloren gehen.
