# GeckoView-Runtime, Sessions und Navigation

Stand: am 25.07.2026 gegen `master`, die Git-Historie und den Task
`Gecko Engine 2` geprueft

## Zweck

Tube NEXT verwendet GeckoView als einzige Browser-Engine. Zwei Regeln sind
dabei gemeinsam zu schuetzen:

1. Eine laufende Gecko-Session darf bei einem Android-Konfigurationswechsel
   nicht unnoetig verloren gehen.
2. Der Darstellungsmodus einer YouTube-Zielseite muss vor ihrer Navigation
   feststehen. Nachtraegliche UA-Wechsel und Replay-Loads erzeugen sichtbare
   Doppel-Ladevorgaenge, falsche URLs und im schlimmsten Fall
   Navigationsschleifen.

Die Produktentscheidung hinter dem Render-Modus steht separat in
[`../decisions/desktop-watch-mobile-layout.md`](../decisions/desktop-watch-mobile-layout.md).

## Genau eine GeckoRuntime pro App-Prozess

GeckoView erlaubt in diesem Einsatz nur eine laufende `GeckoRuntime`.
Activity-Neustarts, Rotation und Samsung-Multi-Window koennen jedoch eine neue
`MainActivity` erzeugen, waehrend die alte Runtime noch lebt. Eine pro Activity
erzeugte Runtime fuehrte historisch zu:

`Only one GeckoRuntime instance is allowed`

`GeckoBrowserEngine.runtimeFor()` haelt deshalb eine prozessweite Runtime und
erzeugt sie mit dem Application-Kontext. Eine neue Activity verwendet
dieselbe Runtime erneut.

Diese Singleton-Regel betrifft die Runtime, nicht die sichtbare `GeckoView`.
Eine View gehoert weiterhin zur aktuellen Activity.

## Sessions bei Activity-Neustart behalten

Die pro Tab existierende `GeckoSession` wird in `retainedTabs` ueber ihre
stabile Tab-ID gehalten.

Bei einem Konfigurationswechsel:

- loest `detach()` die alte `GeckoView` mit `releaseSession()` von der Session,
- bleibt die `GeckoSession` geoeffnet und in `retainedTabs`,
- erzeugt die neue Activity eine neue `GeckoView`,
- bindet sie an dieselbe Session und registriert die Delegates sowie
  WebExtension-Bridge fuer die neue Activity erneut.

Bei einem echten Tab- oder App-Ende:

- entfernt `destroy()` den Eintrag aus `retainedTabs`,
- loest die View,
- schliesst die Session.

Eine Session wird nur unmittelbar nach ihrer Neuerzeugung mit
`session.open(runtime)` geoeffnet. Eine bereits gehaltene Session erneut zu
oeffnen verursachte historisch `IllegalStateException: Session is open`.

Das Manifest faengt mit
`orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden`
haeufige Konfigurationswechsel ab. Die Session-Retention bleibt trotzdem
notwendig, weil Android eine Activity auch aus anderen Gruenden neu aufbauen
kann.

## Eine zentrale Render-Policy

`YouTubeNavigationPolicy` ist die kanonische Entscheidung fuer den Modus:

- `youtube.com`, `www.youtube.com` und `m.youtube.com` verwenden grundsaetzlich
  den mobilen User-Agent,
- Pfade ab `/watch` sowie `youtu.be` verwenden den Desktop-User-Agent,
- transiente Seiten wie `about:*` oder Account-/Cookie-Flows duerfen den
  User-Agent-Modus nicht umschalten.

Die URL wird nicht zwischen `m.youtube.com` und `www.youtube.com`
umgeschrieben. Der Modus wird ueber den User-Agent gewaehlt; ein Host-Redirect
waere ein zusaetzlicher, sichtbarer Navigationsschritt und wuerde die History
verfaelschen.

Der aktuelle Modus wird pro gehaltener Gecko-Session an genau einer Stelle
gefuehrt. Explizite `EngineTab.loadUrl()`-Aufrufe und Gecko-Callbacks duerfen
nicht getrennte Moduskopien pflegen. Andernfalls kann ein expliziter Wechsel
von Desktop-Watch zu einem mobilen Zwischenziel nur die Session umstellen,
waehrend der folgende serverseitige Redirect zu `/watch` aufgrund eines
veralteten Desktop-Flags den notwendigen Rueckwechsel ueberspringt.

## Navigation vor dem Laden entscheiden

Fuer klassische Top-Level-Navigationen setzt `onLoadRequest()` den
User-Agent-Modus vor dem zugelassenen Request.

YouTube navigiert intern oft als Single-Page-App. Ein Klick kann deshalb den
DOM und die History aendern, bevor ein nativer Callback den Modus rechtzeitig
umstellen koennte. Die eingebaute WebExtension faengt nur einen
Modus-Grenzwechsel frueh in der Capture-Phase ab:

1. Das Content-Script ermittelt Ziel- und aktuellen Render-Modus.
2. Bei Mobile nach Desktop-Watch oder zurueck verhindert es den urspruenglichen
   Klick und sendet `MODE_NAV`.
3. Die native Bridge akzeptiert nur Nachrichten der richtigen Session und des
   Top-Level-Dokuments.
4. Sie validiert die Ziel-URL als internen Flow.
5. Sie setzt zuerst den User-Agent und ruft danach genau einmal
   `session.loadUri(url)` auf.

Normale Klicks innerhalb desselben Modus bleiben bei YouTube. Externe Ziele
werden weiterhin vom nativen Link-Handling behandelt.

Nicht wieder einfuehren:

- Replay-Loads aus `onVisited()` oder `onHistoryStateChange()`,
- UA-Wechsel in mehreren konkurrierenden Delegates,
- `DENY` plus spaeteres Nachladen fuer jeden normalen Request,
- Host-Rewrites als Ersatz fuer den Render-Modus.

Diese Varianten verursachten im historischen Task Doppel-Loads,
Mobile/Desktop-Flattern und sichtbare Wechsel zu Zwischenzielen.

## Sichtbare URL und History sind verschiedene Signale

Die Adresszeile folgt der Top-Level-Location aus `onLocationChange()`.
`onVisited()` und `onHistoryStateChange()` pflegen Gecko- beziehungsweise
Tab-History und Titel, sind aber keine verlaessliche Quelle fuer jede sichtbare
URL-Aktualisierung.

Die nutzersichtbare Zurueck-/Vorwaerts-Navigation wird deshalb zusaetzlich als
tabbezogene kanonische YouTube-History gefuehrt. Geckos rohe Back-/Forward-
Availability bleibt nur Fallback fuer interne Zwischenziele. Details und
Regressionen stehen in
[`canonical-back-forward-navigation.md`](canonical-back-forward-navigation.md).

`MainActivity` filtert die gemeldete Location nochmals mit
`YouTubeNavigationPolicy.isUserVisibleUrl()`. Kurzlebige
`accounts.youtube.com/RotateCookiesPage`-, `about:blank`- oder andere interne
Zwischenziele duerfen die letzte sinnvolle YouTube-URL in der Toolbar nicht
ersetzen.

Ein Account-Hop kann ein echter YouTube-/Google-Cookie-Flow sein. Er darf
intern geladen werden, ist aber weder ein Grund fuer einen UA-Wechsel noch
automatisch die vom Nutzer wahrgenommene Hauptseite.

## Gecko-taugliche Seiteneingriffe

`EngineTab.evaluateJavascript()` ist im Gecko-Pfad absichtlich kein Ersatz
fuer Android-WebViews JavaScript-Auswertung. Der historische Versuch,
JavaScript ueber `session.loadUri("javascript:...")` auszufuehren, verschmutzte
URL und History.

DOM-, CSS- und Touch-Anpassungen gehoeren fuer Gecko in die eingebaute
WebExtension. Native Nachrichten werden eng nach Session, Top-Level-Sender,
Nachrichtentyp und Ziel-URL validiert.

## Verwandte Schutzschichten

- Login-, Consent- und Link-Handling:
  [`login-consent-and-link-handling.md`](login-consent-and-link-handling.md)
- Hintergrundwiedergabe und System-Mediensteuerung:
  [`background-audio-notification.md`](background-audio-notification.md)
- Schwarze Surfaces und Prozessverlust:
  [`gecko-black-surface-recovery.md`](gecko-black-surface-recovery.md)
- Tab-Wiederherstellung und Vorschaubilder:
  [`tab-restoration-and-previews.md`](tab-restoration-and-previews.md)

Diese Mechanismen duerfen die hier beschriebene Session-Retention nicht
unbemerkt durch vorsorgliche Reloads ersetzen.

## Regressionstest

1. Mobile YouTube-Startseite oeffnen und ein Video anklicken: genau eine
   Navigation in Desktop-Watch, keine kurz sichtbare Mobile-Watch-Seite.
2. Von einer Watch-Seite zu Home, Suche oder Kanal wechseln: genau eine
   Navigation in den mobilen Modus.
3. `youtu.be` sowie direkte externe YouTube-Intents pruefen.
4. Login-/Account-Flow durchlaufen: kein UA-Pingpong und keine
   `RotateCookiesPage` als dauerhaft sichtbare Toolbar-URL.
5. Eine Google-`sorry`-Challenge von einer Watch-Seite abschliessen: Der
   serverseitige Redirect auf `m.youtube.com/watch` bleibt intern und wird
   wieder mit Desktop-UA geladen.
6. Mehrere Tabs oeffnen, rotieren und Multi-Window-Groesse aendern:
   keine zweite Runtime, kein `Session is open`, Tabs bleiben erhalten.
7. Einen Tab explizit schliessen: seine Session und Bridge muessen entfernt
   sein; die anderen Tabs bleiben unveraendert.
8. Nach Activity-Neustart Long-Press-Neuer-Tab, Home-Feed-Einstellungen und
   weitere Extension-Nachrichten pruefen, damit die Bridge nach dem Rebind auf
   die neue Activity zeigt.

## Historie

- `7f67f9f`: Delegate-Rewrites und JavaScript-URI-Auswertung entfernt.
- `f3b2e39`: NavigationPolicy und Gecko-Content-Messaging stabilisiert.
- `f91ab43`: kanonische Back-History und Gecko-Landscape-Pfad.
- `3c470b8`: gemeinsame Runtime und Session-Retention bei
  Activity-Neustarts stabilisiert.
