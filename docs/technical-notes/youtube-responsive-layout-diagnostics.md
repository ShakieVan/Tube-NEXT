# YouTube-Responsive-Layout diagnostizieren

Stand: 25.07.2026, gegen die historische Desktop-CSS-Analyse und die aktuelle
GeckoView-/WebExtension-Implementierung geprueft

## Historischer Befund

Ausgangspunkt war eine scheinbar widerspruechliche Darstellung: Auf einem
schmalen Smartphone zeigte YouTube links bereits das Desktop-Mini-Menue,
waehrend Titel und Inhaltsbereich zu wenig sichtbaren Platz hatten. In einem
normalen Desktop-Browser erschien dieses Menue erst bei deutlich groesserer
Fensterbreite.

Die Laufzeitmessungen loesten den Widerspruch:

| Zustand | `innerWidth` | `documentClientWidth` | `visualViewportWidth` |
|---|---:|---:|---:|
| mobile YouTube-Seite | ca. 411 | ca. 411 | ca. 411 |
| damalige Desktop-Watch-Seite | ca. 980 | ca. 980 | ca. 411 |

YouTubes responsive CSS reagierte korrekt auf einen logischen
Layout-Viewport von rund 980 CSS-Pixeln. Sichtbar waren aber nur rund 411
CSS-Pixel. Das Mini-Menue und abgeschnittene Inhalte waren daher Symptome
einer auseinanderlaufenden Layout- und Sichtbreite, nicht eines fehlerhaften
YouTube-Breakpoints.

Im damaligen WebView-Code trafen mehrere Faktoren zusammen:

- Desktop-Modus beziehungsweise Desktop-User-Agent,
- `useWideViewPort`-Konfiguration,
- erzwungene Mobile-/Desktop-Hosts und `app`-Parameter,
- JavaScript-Aenderungen am Viewport-Meta-Tag,
- spaet gesetzte Breiten und Zoom-Werte.

Ein einzelner Screenshot konnte nicht unterscheiden, welcher dieser Zustaende
gerade galt. Die gemessenen Laufzeitwerte waren entscheidend.

## Daraus abgeleitete Produktentscheidung

Eine globale Desktop-Darstellung fuer alle YouTube-Seiten loest das
Produktziel nicht. Startseite, Verlauf, Suche, Bibliothek und Account-Flows
funktionieren mit der mobilen Darstellung stabiler und touchfreundlicher.

Nur Watch-Seiten brauchen bewusst den Desktop-Funktionsumfang, insbesondere
fuer Funktionen wie die Auswahl weiterer Audio-Kanaele. Dort passt Tube NEXT
die Desktop-Seite eng begrenzt an den sichtbaren Smartphone-Viewport an.

Diese Entscheidung ist verbindlich in
[`../decisions/desktop-watch-mobile-layout.md`](../decisions/desktop-watch-mobile-layout.md)
festgehalten.

## Heutige Umsetzung

`YouTubeNavigationPolicy` kennt genau zwei Render-Modi:

- `MOBILE` fuer normale YouTube-Bereiche,
- `DESKTOP_WATCH` fuer `/watch` und `youtu.be`.

`GeckoBrowserEngine` schaltet den User-Agent-Modus beim Wechsel dieser
Seitentypen. Die WebExtension verwendet dieselbe URL-Abgrenzung in
`shouldUseDesktop()`.

Auf einer Portrait-Watch-Seite setzt die Extension
`tubenext-watch-fit`. Die CSS-Regeln sind auf diese Root-Klasse begrenzt und:

- begrenzen Dokument und Hauptcontainer auf `100%` beziehungsweise `100vw`,
- entfernen horizontale Ueberbreite,
- stellen die Desktop-Spalten untereinander,
- begrenzen Player, Video und Metadaten auf den sichtbaren Viewport,
- vergroessern ausgewaehlte Player-Bedienelemente fuer Touch,
- lassen das Zahnrad-Menue als bewusst erhaltenen Desktop-Zugang sichtbar.

Landscape verwendet mit `tubenext-landscape-watch` einen getrennten
Regelsatz. Eine Portrait-Regel darf deshalb nicht spaeter die
Landscape-Geometrie ueberschreiben.

## Geeignete Diagnosequelle

YouTube ist eine laufzeitgenerierte Single-Page-App. `Webseite speichern`
liefert keinen belastbaren Diagnosezustand, weil unter anderem fehlen oder
veralten koennen:

- spaeter aufgebautes DOM,
- eingeloggte API-Antworten,
- JavaScript-Zustand und SPA-Historie,
- Service-Worker- und Cache-Zustand,
- nachgeladene Styles und Player-Klassen.

Benoetigt wird der live gerenderte Zustand aus derselben Session, in der der
Fehler auftritt.

Im frueheren Android-WebView-Stand konnte Desktop-Chrome ueber
`chrome://inspect/#devices` direkt auf die WebView zugreifen. Das gilt nicht
automatisch fuer die heutige GeckoView-Engine. `EngineTab.evaluateJavascript`
ist unter Gecko ein No-op. Fuer aktuelle App-Diagnosen sind daher entweder
ein explizit eingerichteter Gecko-Debugging-Pfad oder eine temporaere,
rein lesende WebExtension-Diagnose mit Native-Messaging erforderlich.

Ein normaler Desktop-Browser bleibt als Vergleichsreferenz sinnvoll, ersetzt
aber nicht die Messung in der betroffenen App-Session.

## Minimale Laufzeitmessung

Fuer einen Layoutvergleich sollten mindestens folgende Werte gemeinsam
erfasst werden:

```js
({
  href: location.href,
  userAgent: navigator.userAgent,
  innerWidth: window.innerWidth,
  innerHeight: window.innerHeight,
  documentClientWidth: document.documentElement?.clientWidth ?? null,
  visualViewportWidth: window.visualViewport?.width ?? null,
  visualViewportHeight: window.visualViewport?.height ?? null,
  visualViewportScale: window.visualViewport?.scale ?? null,
  devicePixelRatio: window.devicePixelRatio,
  viewportMeta:
    document.querySelector('meta[name="viewport"]')?.content ?? null,
  rootClasses: document.documentElement?.className ?? "",
  playerRect:
    document.querySelector("#movie_player")?.getBoundingClientRect() ?? null,
  videoRect:
    document.querySelector("video")?.getBoundingClientRect() ?? null
})
```

Zusätzlich gehoeren ein Screenshot und der genaue Betriebszustand dazu:

- Portrait oder Landscape,
- Watch oder Nicht-Watch,
- vor oder nach SPA-Navigation,
- sichtbare App-Toolbar und Systemleisten,
- laufender Zoom beziehungsweise Translation,
- YouTubes relevante Player-Klassen wie `ytp-*-width-mode`.

## Auswertung

Folgende Beziehungen sind wichtiger als ein einzelner Zahlenwert:

1. `innerWidth` gegen `visualViewport.width`: Eine grosse Differenz zeigt,
   dass Layout- und Sichtviewport auseinanderlaufen.
2. Dokumentbreite gegen Player-Rechteck: Ein schmaler Player in einem breiten
   Dokument deutet auf YouTubes interne Width-Mode-Klassifizierung oder einen
   veralteten Inline-Style.
3. Player-Rechteck gegen sichtbaren Viewport: `x`, `right` oder `width`
   ausserhalb des Viewports erklaeren seitlichen Ueberstand direkt.
4. Root-Klassen gegen URL und Orientierung: `tubenext-watch-fit` und
   `tubenext-landscape-watch` duerfen nicht gleichzeitig den falschen
   Regelsatz aktivieren.
5. Werte vor und nach `yt-navigate-finish`: YouTube kann ohne klassischen
   Reload Layout und URL innerhalb derselben Session wechseln.

## Nicht wiederholen

- Keine globale Viewport-Meta-Manipulation fuer alle YouTube-Seiten.
- Keine pauschalen CSS-Regeln fuer mobile Bereiche, nur um einen
  Desktop-Breakpoint zu erzwingen.
- Keine rekursiven Host-, `/m`-, `app=m`- oder `continue`-Umschreibungen.
  Solche Eingriffe erzeugten im historischen Task Login-Schleifen bis hin zu
  HTTP 400.
- Keine Theme- oder Layout-Korrektur durch kuenstliche Reload-URLs; sie
  verdoppelte den sichtbaren Aufbau und beschaedigte die Zurueck-Historie.
- Keine direkte permanente Gegensteuerung von YouTubes
  `ytp-*-width-mode`-Klassen.

Navigation und Engine-Wechsel sind ausfuehrlicher in
[`geckoview-runtime-and-navigation.md`](geckoview-runtime-and-navigation.md)
dokumentiert. Timing- und Nachlaufregeln stehen in
[`watch-readiness-and-retries.md`](watch-readiness-and-retries.md).
