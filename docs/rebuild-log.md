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

## Block B: Punkt 5 minimal (kompakte Kopfzeile), Punkt 6 vorerst ausgesetzt
Status: `umgesetzt`

### Umgesetzt
- Einzeilige Kopfzeile mit:
  - URL-Text (nur Anzeige)
  - Reload-Button
  - Tab-Button mit Tab-Anzahl-Badge
- Sichtbare `TabLayout`-Leiste bleibt ausgeblendet.
- Tab-Button oeffnet aktuell einen einfachen Tab-Dialog (Neuer Tab + Tab-Auswahl), **kein** Bottom-Sheet/Preview-Manager.

### Nicht enthalten (absichtlich)
- Kein Tab-Overview-Bottom-Sheet
- Keine Tab-Previews / Multi-Select / Reorder-UI
- Keine Overlay/Settle-Erweiterungen

### Test-Checkliste (manuell)
1. Kopfzeile ist einzeilig; URL sichtbar; Reload und Tab-Button funktionieren.
2. Tab-Anzahl-Badge zaehlt korrekt bei Neu/Schliessen/Duplizieren.
3. Fullscreen-Video weiterhin funktionsfaehig.
4. Watch-Navigation und Back-Verlauf unveraendert stabil.

## Block C: Tab-Manager (Liste + Vorschau + Header-Piktogramme)
Status: `umgesetzt`

### Umgesetzt
- Bottom-Sheet-Tab-Manager statt einfachem Dialog.
- Tab-Liste mit:
  - Vorschau links (oberer Seitenbereich)
  - Titel rechts daneben
  - `X` zum Schliessen ganz rechts
- Klick auf Tab wechselt den Tab, **Dialog bleibt offen**.
- Aktiver Tab wird dezent gruen markiert.
- Kopfzeile des Tab-Managers mit Piktogramm-Buttons:
  - `+` fuer neuer Tab
  - ueberlappende Rechtecke fuer Duplizieren
  - Close-Others-Icon mit rotem Hinterelement und kleinem `x`

### Nicht enthalten (absichtlich)
- Keine Overlay/Settle-/Watch-Layout-Erweiterungen
- Kein Eingriff in Fullscreen/CustomView-Handling

### Test-Checkliste (manuell)
1. Tab-Manager per Kopfzeilen-Button oeffnen.
2. Tabs in Liste sehen (Preview + Titel + `X`).
3. Auf Tabs klicken und schnell hin-/herschalten: Dialog bleibt offen, aktiver Tab markiert.
4. Kopf-Buttons pruefen:
   - `+` erstellt Tab
   - Duplizieren dupliziert aktiven Tab
   - Andere Tabs schliessen laesst nur aktiven Tab uebrig
5. Danach Watch + Fullscreen erneut pruefen.

## Block D: Punkt 7 (URL kopieren / editieren)
Status: `umgesetzt`

### Umgesetzt
- Kurzklick auf URL in der Kopfzeile:
  - kopiert aktuelle URL in die Zwischenablage
  - bestaetigt per Snackbar
- Langklick auf URL:
  - oeffnet Dialog mit vorausgefuellter URL
  - bestaetigtes Eingabefeld wird im aktuellen Tab geladen

### Test-Checkliste (manuell)
1. Kurzklick auf URL -> Snackbar "Link kopiert".
2. Langklick auf URL -> Dialog erscheint mit aktueller URL.
3. URL aendern und "Oeffnen" -> Seite laedt im aktuellen Tab.

## Block E: URL-Bereinigung ohne `app=desktop`
Status: `umgesetzt`

### Umgesetzt
- Interne YouTube-URL-Normalisierung fuegt `app=desktop` nicht mehr hinzu.
- Vorhandene `app`- und `persist_app`-Parameter werden entfernt.
- Watch/Desktop bleibt ueber WebView-Modus (User-Agent) gesteuert.

## Block F: Punkt 8 (Dark Mode)
Status: `umgesetzt`

### Umgesetzt
- YouTube-Dark-Preference via `PREF`-Cookie fuer:
  - `https://www.youtube.com`
  - `https://m.youtube.com`
- Cookie-Merge behaelt bestehende PREF-Teile und setzt/ersetzt nur `f6=400`.
- WebView-Hintergrund auf schwarz gesetzt, um helle Zwischenframes zu reduzieren.

### Nicht enthalten (absichtlich)
- Keine CSS-/DOM-Injections fuer Dark Mode
- Kein aggressives Forcen per Algorithmic-Darkening

## Block G: Watch-Seite nach `onPageFinished` verzoegert stabilisieren
Status: `umgesetzt`

### Umgesetzt
- Watch-Viewport-Stabilisierung wird nicht mehr sofort bei URL-Update ausgefuehrt.
- Neue Ausloesung:
  - erst nach `onPageFinished`
  - plus `1000ms` Wartezeit
- Pro Tab wird ein kleiner Generation-Counter verwendet, damit aeltere Delays keine neuere Navigation ueberschreiben.

### Ziel
- Nachgeladene YouTube-Skripte bekommen Zeit, bevor die visuelle Korrektur greift.
- Weniger abgeschnittene Pixel links/rechts im Watch-Layout.
