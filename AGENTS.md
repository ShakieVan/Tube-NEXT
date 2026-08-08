# AGENTS.md - Tube-NEXT

## Zweck

Tube NEXT ist eine touchfreundliche Android-App fuer die offizielle
YouTube-Webseite. Sie verbindet eine native, tabfaehige App-Shell mit
GeckoView als eingebetteter Browser-Engine.

Die App ist keine eigene Video-Plattform. Ihr wesentlicher Produktvorteil ist:
Normale YouTube-Bereiche koennen mobil dargestellt werden, Watch-Seiten
bleiben jedoch Desktop-Seiten und werden fuer grosse Smartphones
touchfreundlich angepasst. Dadurch bleiben Desktop-Funktionen wie die Auswahl
anderer Audio-Kanaele erhalten.

## Verbindliche Entscheidungen

- Alle Tabs verwenden GeckoView. Kein Android-WebView-, Custom-Tab- oder
  gemischter Produktpfad.
- Watch-Seiten und `youtu.be` verwenden Desktop-Modus; andere unterstuetzte
  YouTube-Seiten mobilen Modus.
- Die Wiedergabe bleibt in der offiziellen YouTube-Seite. Die
  Android-Medienbenachrichtigung steuert die Gecko-MediaSession; sie ist kein
  zweiter Player.
- CSS-, DOM- und Touch-Anpassungen laufen gekapselt ueber die eingebaute
  WebExtension.

Vor Engine- oder Watch-Aenderungen sind diese Dokumente verbindlich:

- [`docs/decisions/geckoview-only-browser-engine.md`](docs/decisions/geckoview-only-browser-engine.md)
- [`docs/decisions/desktop-watch-mobile-layout.md`](docs/decisions/desktop-watch-mobile-layout.md)
- [`docs/technical-notes/geckoview-runtime-and-navigation.md`](docs/technical-notes/geckoview-runtime-and-navigation.md)

## Nicht-Ziele

Nicht implementieren oder vorbereiten:

- Umgehung von DRM,
- Downloads von YouTube-Inhalten,
- Ad-Blocking oder vergleichbare Richtlinienumgehungen,
- Extraktion oder Reverse Engineering direkter YouTube-Audio-/Videostreams,
- inoffizielle Stream-Resolver fuer einen eigenen Player,
- ungenutzte zweite Browser-Engines "fuer alle Faelle".

## Technische Leitplanken

- Sprache: Kotlin
- Plattform: Android
- Min SDK: 29
- Compile-/Target-SDK und GeckoView-Version: aus dem aktuellen Gradle-Stand
- Java-/Kotlin-Bytecode: JVM 17
- Browser-Laufzeit: GeckoView
- Architektur: einfache App-Shell mit gekapselter Engine, Tab- und
  Audio-Verantwortung

Bei Konflikten gilt:

1. Stabilitaet
2. Login- und Sitzungs-Persistenz
3. Erhalt des Desktop-Watch-Funktionsumfangs
4. gute Touch-Bedienbarkeit
5. Performance und Akkuverbrauch
6. Zusatzeffekte und Komfortfunktionen

## Aktuelle Architektur

### `MainActivity`

Verantwortlich fuer:

- App-Start, Toolbar und globale Navigation,
- eingehende Intents,
- Verbindung zwischen App-Shell, `TabManager` und aktivem `EngineTab`,
- Android-Lebenszyklus, Rotation und immersiven Watch-Modus,
- Loading-, Tab-Uebersichts- und Einstellungsoberflaechen.

Engine-Details sollen soweit sinnvoll hinter den vorhandenen Vertraegen
bleiben.

### `BrowserEngine` und `EngineTab`

Trennen App-Shell und Gecko-Laufzeit. Die Abstraktion dient Testbarkeit und
klarer Verantwortung; sie bedeutet nicht, dass mehrere Engines gepflegt
werden sollen.

### `GeckoBrowserEngine`

Verantwortlich fuer:

- gemeinsame prozessweite `GeckoRuntime`,
- Erzeugen, Halten und Schliessen von `GeckoSession`s,
- Gecko-Delegates fuer URL, History, Fortschritt, Inhalt und MediaSession,
- GeckoView-Erzeugung und Session-Rebind nach Activity-Neustart,
- Installation und native Bridge der eingebauten WebExtension.

Eine vorhandene Session niemals erneut `open(runtime)`-en. Bei einem
Konfigurationswechsel wird nur die alte View geloest; ein echtes Tab-Ende
schliesst die Session.

### `AppTab`, `TabManager` und Persistenz

`AppTab` verbindet Tab-Metadaten und `EngineTab`. `TabManager` verwaltet
Reihenfolge und Auswahl. Persistierte IDs und URLs sowie Vorschaubilder
ermoeglichen Wiederherstellung und Tab-Uebersicht.

GeckoViews und Sessions sind teuer. Hintergrund-Tabs nur nach den Regeln aus
[`docs/technical-notes/background-resource-management.md`](docs/technical-notes/background-resource-management.md)
schlafen legen.

### Navigation

`YouTubeNavigationPolicy` entscheidet zentral zwischen Mobile und
Desktop-Watch. `LinkInterceptor` trennt interne YouTube-/Google-Flows von
externen Zielen.

Nicht wieder einfuehren:

- `m.youtube.com`/`www.youtube.com` als Modusersatz umschreiben,
- UA-Replay-Loads aus History- oder Visit-Callbacks,
- transiente Account-/`about:*`-URLs als dauerhafte Toolbar-URL,
- JavaScript ueber `session.loadUri("javascript:...")`.

YouTube-SPA-Grenzwechsel werden vor der Navigation in der WebExtension
abgefangen und als validierte native `MODE_NAV`-Nachricht verarbeitet.

### WebExtension

Assets unter
`app/src/main/assets/web_extensions/tubenext_nav_switch/` enthalten
YouTube-spezifische Navigation, Layout-, Touch- und Filteranpassungen.

Jeder Eingriff muss:

- auf den kleinsten noetigen Host, Seitentyp und Renderer begrenzt sein,
- bei SPA-Navigation und spaet nachgeladenem DOM funktionieren,
- vorhandene YouTube-Menues und Player-Controls moeglichst unberuehrt lassen,
- mit einem passenden Regressionstest in einer technischen Notiz abgesichert
  sein.

`EngineTab.evaluateJavascript()` ist im Gecko-Pfad kein allgemeiner
Seiteneingriff. Neue DOM-Logik gehoert in die WebExtension.

### Hintergrund-Audio

`GeckoBrowserEngine` meldet Medienzustand und Steuerung.
`AndroidBackgroundAudioCoordinator` ordnet Zustand und Controls einem Tab zu.
`BackgroundAudioService` stellt bei aktiver Hintergrundwiedergabe
Foreground-Service, Android-MediaSession, Notification, Audio-Fokus und
Wake-Lock bereit.

Vor Aenderungen lesen:
[`docs/technical-notes/background-audio-notification.md`](docs/technical-notes/background-audio-notification.md).

## Kernfunktionen und Abnahme

Ein belastbarer Stand muss mindestens bieten:

- Login, Cookies und Account-Wechsel ueber App-Neustarts,
- mindestens zwei stabile, wiederherstellbare Tabs,
- YouTube-Links aus anderen Apps,
- mobile Darstellung normaler YouTube-Seiten,
- Desktop-Watch mit mobilem Layout und Desktop-Funktionen,
- Video-Wiedergabe, Rotation, immersiven Landscape-Modus und
  YouTube-Controls,
- Hintergrund-Audio mit korrekter System-Mediensteuerung,
- kein offensichtlicher Crash oder schwarzer Dauerzustand bei Rotation,
  Hintergrund/Vordergrund und Tabwechsel.

Picture-in-Picture ist nur dann ein Ziel, wenn es mit GeckoView, Watch-Modus
und Hintergrund-Audio stabil zusammenspielt. Automatische PiP-Aktivierung
nicht ohne neue, belegte Produktentscheidung einfuehren.

## Performance und Lebenszyklus

- Keine unnoetigen Reloads; URL, Session und Wiedergabeposition sind
  Nutzerzustand.
- Nicht mehrere GeckoViews gleichzeitig im sichtbaren Surface-Container
  lassen.
- Sessions bei reinen Konfigurationswechseln behalten.
- Tabs nicht nur aufgrund einer schwarzen Heuristik vorsorglich neu laden.
- Media-Notification nicht bei jedem Positionsupdate neu bauen.
- Speicher-, Surface- und Hintergrundregeln aus den technischen Notizen
  beachten.

## Bekannte Risiken

- YouTube kann DOM, Renderer und Ereignisverhalten jederzeit aendern.
- GeckoView-Versionen koennen Delegate-, Medien-, Surface- und
  R8-Eigenschaften veraendern.
- Mehrere Desktop-Watch-Tabs benoetigen erheblichen nativen, GPU- und
  Surface-Speicher.
- Zu breite CSS- oder Event-Eingriffe koennen Kommentar-, Player- oder
  Kontextmenues gemeinsam beschaedigen.
- Google-Account- und Consent-Flows sind interne Navigation, aber nicht
  automatisch nutzersichtbare Hauptseiten.

## Manifest und Link-Handling

Relevante Hosts:

- `www.youtube.com`
- `youtube.com`
- `m.youtube.com`
- `youtu.be`

Google-Account- und Consent-Hosts werden nur fuer den notwendigen internen
Login-Flow zugelassen. Andere externe Ziele gehen an eine externe App.

## Rechtliches

- Tube NEXT nutzt die offizielle YouTube-Webseite in GeckoView.
- Vor Funktionen mit Compliance-Risiko Nutzungsbedingungen und
  Plattformrichtlinien pruefen.
- Im Zweifel die stabilere, regelkonforme Variante waehlen.

## Arbeitsweise

- Kleine, nachvollziehbare und separat pruefbare Schritte bevorzugen.
- Vor Beginn Arbeitsordner, Branch und Worktree mit Git pruefen; alte
  Chat-Handoffs sind keine Autoritaet fuer den aktuellen Stand.
- Beim Zugriff auf das bekannte Testtelefon kann der erste ADB-Aufruf nur den
  Daemon starten. Eine leere erste Ausgabe von `adb devices -l` deshalb
  mindestens einmal unmittelbar wiederholen, bevor das Telefon als nicht
  erreichbar gilt oder auf MTP ausgewichen wird.
- Bestehende Nutzerveraenderungen im Worktree erhalten.
- Technische Grenzen klar benennen, statt fragile Workarounds zu verstecken.
- Architektur nur bei einem konkreten Engpass erweitern.

## Projektdokumentation

- Einstiegspunkt ist [`docs/README.md`](docs/README.md).
- Vor Aenderungen die einschlaegigen Entscheidungen und technischen Notizen
  lesen.
- Lokale Implementierungsdetails kurz im Code kommentieren.
- Ueber mehrere Dateien oder spaetere Arbeiten relevantes Wissen unter
  `docs/technical-notes/` festhalten.
- Dauerhafte Produkt- und Architekturentscheidungen unter `docs/decisions/`
  dokumentieren.
- Bei Releases Versionswerte und `docs/releases/vX.Y.Z.md` aktualisieren.
- Vor einem GitHub-Release und solange die Fortschrittsdiagnose aktiv ist den
  verbindlichen Ablauf aus
  [`docs/technical-notes/release-and-diagnostic-deployment.md`](docs/technical-notes/release-and-diagnostic-deployment.md)
  befolgen. Der oeffentliche Release bleibt diagnosefrei; auf das bekannte
  Testtelefon kommt anschliessend der passend versionierte
  `diagnosticRelease`.
- Aussagen aus alten Chats gegen Code, Git-Historie und Tests pruefen.
- Ersetzte Workarounds und widerlegte Erkenntnisse in der kanonischen Notiz
  deutlich aktualisieren.
