# AGENTS.md - Tube-NEXT

## Zweck
Dieses Repository dient als Startpunkt fuer eine Android-App, die YouTube moeglichst nah an der Desktop-Erfahrung in einer touchfreundlichen App abbildet.

Die App soll keine eigene Video-Plattform sein, sondern primaer eine robuste, tabfaehige WebView-basierte YouTube-App mit Fokus auf Bedienbarkeit, Persistenz und alltagstauglicher Performance.

## Produktziel
Baue eine Android-App, die:
- die YouTube-Desktop-Seite in einer WebView nutzt
- fuer Touch-Bedienung und grosse Smartphone-Displays optimiert ist
- mehrere Tabs wie ein mobiler Browser unterstuetzt
- YouTube-Links direkt in der App oeffnen kann
- Logins, Cookies und Sitzungen verlaesslich beibehaelt
- im Videomodus moeglichst immersiv wirkt

## Nicht-Ziele
Folgendes soll nicht implementiert oder vorbereitet werden:
- Umgehung von DRM
- Download-Funktionen fuer YouTube-Inhalte
- Ad-Blocking oder andere Eingriffe, die klar gegen YouTube-Richtlinien laufen
- Reverse Engineering nativer YouTube-Player-Funktionen, wenn dieselben Daten nur ueber inoffizielle APIs verfuegbar waeren

## Technische Leitplanken
- Sprache: Kotlin
- Plattform: Android
- Min SDK: 29
- Target SDK: aktuelle stabile Android-SDK-Version des Projekts
- UI-Grundlage: WebView-basierte App mit eigener Tab-Verwaltung
- Architektur: lieber einfach, testbar und modular als zu frueh komplex

Wenn spaetere Entscheidungen zu Konflikten fuehren, gelten diese Prioritaeten:
1. Stabilitaet
2. Login- und Sitzungs-Persistenz
3. Gute Bedienbarkeit auf Touch-Geraeten
4. Performance und Akkuverbrauch
5. Zusatzeffekte und Komfortfunktionen

## Kernfunktionen

### 1. YouTube in Desktop-Darstellung
- Verwende einen Desktop User Agent.
- Aktiviere JavaScript, DOM Storage und Cookies.
- Unterstuetze YouTube-Features, soweit sie in der Desktop-Webseite innerhalb einer Android-WebView stabil funktionieren.

### 2. Login und Account-Nutzung
- Login soll App-Neustarts ueberleben.
- Cookies und WebView-Daten duerfen nicht unnoetig geloescht werden.
- Mehrere Accounts muessen mindestens ueber die normale YouTube-Account-Umschaltung benutzbar bleiben.
- Ein expliziter "eingeloggt / nicht eingeloggt"-Modus ist optional und nur sinnvoll, wenn er technisch sauber ohne fragile Workarounds umsetzbar ist.

### 3. Tabs
- Mehrere gleichzeitig offene YouTube-Seiten.
- Neuen Tab oeffnen, schliessen, wechseln, optional duplizieren.
- Offene Tabs und deren letzte URLs sollen bei App-Neustart wiederherstellbar sein.

### 4. Link-Handling
- `youtube.com`, `m.youtube.com` und `youtu.be` Links sollen von der App verarbeitet werden koennen.
- Externe YouTube-Links sollen wahlweise im aktuellen oder in einem neuen Tab landen.

### 5. Video- und Vollbild-Erlebnis
- Normales HTML5-Video innerhalb der WebView muss sauber funktionieren.
- Vollbild soll fuer den Nutzer immersiv wirken.
- Pinch-to-Zoom und optionale Doppeltipp-Zoom-Interaktionen sind wuenschenswert, aber nur dann, wenn sie das eigentliche Playback nicht destabilisieren.
- Geraeterotation und Landscape-Nutzung sollen sinnvoll unterstuetzt werden.

### 6. Picture-in-Picture
- PiP ist ein Soll-Ziel, falls die eingesetzte Android/WebView-Kombination dies fuer YouTube im Projektkontext stabil zulaesst.
- Wenn PiP unzuverlaessig ist, hat Stabilitaet Vorrang vor einer halbfertigen Aktivierung.

## Architekturvorschlag

### `MainActivity`
Verantwortlich fuer:
- App-Start
- Toolbar und globale Navigation
- Weiterleitung eingehender Intents
- Verbindung zwischen UI, Tabs und aktivem WebView

### `BrowserTab` oder `TabSession`
Empfohlene Abstraktion pro Tab:
- eindeutige ID
- Titel
- letzte URL
- WebView-Zustand
- optional Snapshot oder Vorschaudaten

### `WebViewFactory`
Zentrale Erstellung und Konfiguration neuer WebViews:
- Desktop User Agent
- JavaScript
- DOM Storage
- Cookie-Verhalten
- sichere Defaults fuer Dateizugriffe und Medienwiedergabe

### `TabManager`
Verantwortlich fuer:
- Anlegen und Schliessen von Tabs
- Tab-Wechsel
- Persistenz der Tab-Liste
- Wiederherstellung beim Start

### `YouTubeWebViewClient`
Verantwortlich fuer:
- URL-Steuerung
- Erkennen interner und externer YouTube-Links
- Navigation innerhalb der App
- Fehlerbehandlung beim Laden

### `YouTubeWebChromeClient`
Verantwortlich fuer:
- Vollbild-Callbacks
- Video-bezogene Browser-Events
- Titel-Updates
- Fortschritt und optionale Datei-/Permission-Integrationen

### `LinkInterceptor`
Kann als eigene Klasse oder als Teil des `WebViewClient` umgesetzt werden:
- Erkennung unterstuetzter Hosts
- Entscheidung aktueller Tab vs. neuer Tab
- Weitergabe externer, nicht zu YouTube gehoerender Links an den Standardbrowser

## Wichtige Implementierungsdetails

### WebView-Konfiguration
Mindestens pruefen und sinnvoll setzen:
- `javaScriptEnabled`
- `domStorageEnabled`
- `mediaPlaybackRequiresUserGesture`
- `setAcceptCookie(true)`
- `setAcceptThirdPartyCookies(...)`
- geeignete Cache-Strategie

Keine unsicheren WebView-Settings ohne klaren Grund aktivieren.

### Persistenz
- Session- und Cookie-Persistenz haben hohen Stellenwert.
- Offene Tabs sollen in einer klaren, einfachen Struktur gespeichert werden.
- Bevorzuge nachvollziehbare Persistenz mit `DataStore` oder sauber gekapselten `SharedPreferences`, statt frueh komplexe Datenhaltung einzufuehren.

### Performance
- Vermeide unnoetige JavaScript-Injections.
- Vermeide dauernde Neuinitialisierung von WebViews, wenn Tabs nur gewechselt werden.
- Halte Speicherverbrauch im Blick, da mehrere WebViews teuer sein koennen.

### Touch-Optimierung
- CSS- oder JS-Injections nur sparsam und gekapselt einsetzen.
- Jede Injection muss optional abschaltbar und robust gegen Aenderungen der YouTube-DOM bleiben.
- Keine Loesung bauen, die bei kleinen UI-Aenderungen von YouTube sofort komplett bricht.

## Empfohlene Reihenfolge der Umsetzung
1. Grundprojekt in Android Studio anlegen
2. Einzelne WebView mit Desktop-YouTube stabil zum Laufen bringen
3. Login- und Cookie-Persistenz verifizieren
4. Intent-Filter fuer YouTube-Links integrieren
5. Tab-System aufbauen
6. Vollbild- und Rotationsverhalten verbessern
7. Optionale Touch-Optimierungen hinzufuegen
8. PiP evaluieren
9. Akku- und Speicherverhalten optimieren

## Akzeptanzkriterien
Eine erste brauchbare Version ist erreicht, wenn:
- YouTube in Desktop-Darstellung laedt
- Login nach App-Neustart erhalten bleibt
- mindestens zwei Tabs stabil nutzbar sind
- YouTube-Links aus anderen Apps in dieser App landen koennen
- Video-Wiedergabe inklusive Vollbild-Nutzung im Alltag funktioniert
- keine offensichtlichen Abstuerze bei Rotation, Hintergrund/Vordergrund und Tab-Wechsel auftreten

## Bekannte Risiken
- YouTube kann DOM, Layout und Verhalten jederzeit aendern.
- WebView-Verhalten kann sich je nach Android-System-WebView-Version unterscheiden.
- Manche Desktop-Funktionen von YouTube koennen in einer mobilen WebView eingeschraenkt oder instabil sein.
- Zu aggressive Touch-Optimierungen koennen Bedienung oder Playback verschlechtern.

## Manifest-Hinweis
Fuer Link-Oeffnung sind Intent-Filter fuer mindestens folgende Hosts relevant:
- `www.youtube.com`
- `youtube.com`
- `m.youtube.com`
- `youtu.be`

## Rechtliches
- Die App nutzt die offizielle YouTube-Webseite in einer WebView.
- Vor jeder Funktion mit moeglichem Compliance-Risiko ist zu pruefen, ob sie mit den Nutzungsbedingungen und Plattformrichtlinien vereinbar ist.
- Im Zweifel konservativ entscheiden und die stabilere, regelkonforme Variante bevorzugen.

## Arbeitsweise fuer Codex oder andere Agenten
- Bevorzuge kleine, nachvollziehbare Schritte statt grosser ungetesteter Umbauten.
- Dokumentiere Annahmen direkt im Code oder in kurzen Projekt-Notizen.
- Wenn technische Grenzen von WebView oder YouTube die Wunschfunktion blockieren, benenne die Einschraenkung klar statt fragilen Workaround-Code zu bauen.
- Schlage nur dann komplexere Architektur vor, wenn ein konkreter Engpass sichtbar ist.

## Projektdokumentation
- Beginne bei [`docs/README.md`](docs/README.md). Dort ist festgelegt, welche
  Information in welche Datei gehoert.
- Lies vor einer Aenderung die einschlaegigen Entscheidungen und technischen
  Notizen. Fuer die Watch-Seite ist
  [`docs/decisions/desktop-watch-mobile-layout.md`](docs/decisions/desktop-watch-mobile-layout.md)
  verbindlich.
- Halte nicht offensichtliche Erkenntnisse zu YouTube, GeckoView oder
  Android-Laufzeitverhalten unter `docs/technical-notes/` fest.
- Dokumentiere dauerhafte Architektur- und Produktentscheidungen unter
  `docs/decisions/`.
- Aktualisiere bei einem Release sowohl die Versionswerte als auch eine Datei
  `docs/releases/vX.Y.Z.md`.
- Code-Kommentare erklaeren nur lokale Implementierungsdetails. Wissen, das
  fuer kuenftige Aenderungen oder Regressionstests relevant ist, gehoert
  zusaetzlich in die Projektdokumentation.
- Wenn ein Workaround ersetzt oder eine Erkenntnis widerlegt wird, aktualisiere
  die kanonische Notiz und markiere ueberholte historische Aussagen deutlich.
