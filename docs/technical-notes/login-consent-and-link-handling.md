# Login-, Consent- und Link-Handling

Stand: am 01.08.2026 gegen den aktuellen GeckoView-Code und den Geraeteablauf
auf dem Samsung SM-S928B geprueft

## Zweck

YouTube-Login, Google-Consent und normale externe Links sehen aus Sicht der
App alle wie Navigationen aus. Sie brauchen dennoch unterschiedliche Regeln:

- YouTube und die notwendigen Google-Zwischenziele muessen innerhalb
  derselben Gecko-Sitzung bleiben, damit Login und Cookies erhalten bleiben.
- Beliebige externe Ziele sollen nicht still innerhalb der App geladen werden.
- Kurzlebige Account- und Cookie-Seiten duerfen weder die sichtbare
  YouTube-URL noch den Mobile-/Desktop-Watch-Modus dauerhaft veraendern.

Die zentrale Navigation und Session-Retention sind in
[`geckoview-runtime-and-navigation.md`](geckoview-runtime-and-navigation.md)
dokumentiert.

## Historische Erkenntnis aus dem WebView-Stand

Im ersten lauffaehigen Android-WebView-Prototyp war die interne Hostliste zu
eng. Ein Klick auf `Login` oder ein Schritt der Zwei-Faktor-Anmeldung konnte
deshalb in den externen Browser beziehungsweise in den Android-Dialog zum
Hinzufuegen eines Geraetekontos wechseln. Nach Rueckkehr war die YouTube-
Sitzung teilweise trotzdem angemeldet, der Ablauf war aber nicht
deterministisch.

Der Prototyp zeigte ausserdem:

- Consent-Seiten brauchen einen echten kleinen Viewport und muessen scrollbar
  bleiben.
- Erzwungenes WebView-Darkening kann Seitenfarben in unlesbares Grau auf
  Schwarz verwandeln.
- Globale Viewport-, Zoom- oder `MutationObserver`-Schleifen koennen statt
  einer Korrektur eine weisse beziehungsweise schwarze Seite erzeugen.
- Login- und Consent-Probleme duerfen nicht durch Cookie-Loeschen "behoben"
  werden; Sitzungs-Persistenz hat Vorrang.

Darkening-, Wide-Viewport- und WebView-Hit-Test-Details sind historisch. Die
heutige App verwendet ausschliesslich GeckoView. Die Navigations- und
Sicherheitsregeln bleiben jedoch gueltig.

## Heutiger GeckoView-Vertrag

`NavigationHostPolicy` ist die zentrale Host-Grenze.
`LinkInterceptor.isInternalFlowUri()` laesst HTTP(S)-Navigation innerhalb der
App nur zu, wenn sie:

- auf einen unterstuetzten YouTube-Host zeigt oder
- exakt auf `accounts.google.com`, `consent.google.com` oder
  `gds.google.com` zeigt.

`gds.google.com` ist kein pauschal angenommener Google-Host. Er wurde auf
einem echten Geraeteablauf nach Passwort und 2FA als Vorschlagsseite fuer
Google-Kontodaten beobachtet, bevor der Flow zu YouTube zurueckkehrt. Fuer
weitere Google-Hosts bleibt die Grenze geschlossen; neue Hosts werden erst
nach einem protokollierten Top-Level-Ablauf einzeln aufgenommen.

YouTube-Unterhosts bleiben ueber die label-begrenzte Domain
`*.youtube.com` intern; dadurch funktionieren insbesondere
`accounts.youtube.com/RotateCookiesPage` und `consent.youtube.com`. Aehnlich
aussehende Namen ausserhalb dieser Domain sowie andere Google-Angebote sind
keine Login-Freigabe. Zwei-Faktor- und Account-Auswahlseiten laufen unter dem
zugelassenen Account-Host. Wenn ein Geraeteablauf einen weiteren Top-Level-
Host benoetigt, wird er einzeln belegt und ergaenzt statt eine Google-Domain
pauschal freizugeben.

Andere HTTP(S)-Ziele werden von `GeckoBrowserEngine.onLoadRequest()` abgelehnt
und ueber den App-Callback an eine externe Anwendung gegeben.
Debug-Builds protokollieren dabei nur Schema und Host des abgelehnten
Top-Level-Ziels. Pfad, Query, Fragment, Tokens und Nutzerdaten werden nicht
protokolliert.

`YouTubeNavigationPolicy` entscheidet unabhaengig davon ueber den
Darstellungsmodus. Nur Watch-Seiten und `youtu.be` erhalten Desktop-UA.
Account- und Consent-Zwischenziele sind kein Desktop-Watch-Signal.

Die Toolbar uebernimmt nur nutzersichtbare YouTube-URLs. Insbesondere
`accounts.youtube.com/RotateCookiesPage`, `about:*` und andere transiente
Zwischenziele ersetzen nicht die letzte sinnvolle YouTube-Adresse.

## Bestaetigter Geraeteablauf

Am 01.08.2026 wurde nach dem Loeschen der Debug-App-Daten folgender realer
Top-Level-Ablauf beobachtet:

1. YouTube-Login innerhalb der Gecko-Sitzung,
2. Passwort und Zwei-Faktor-Bestaetigung,
3. Weiterleitung auf `gds.google.com` zur Festlegung einer privaten Adresse,
4. Rueckkehr zu YouTube,
5. Neuladen der Seite in Tube NEXT mit erfolgreicher Anmeldung.

Vor Aufnahme von `gds.google.com` sprang dieser Zwischenschritt in einen
anderen Browser und liess den Nutzer anschliessend dort auf YouTube landen.
Die explizite Hostfreigabe haelt den beobachteten Pfad nun in derselben
Gecko-Sitzung. Sie ist kein Beleg fuer eine allgemeine Freigabe weiterer
Google-Subdomains.

## Long-Press-Slider fuer Links

GeckoView liefert hier nicht dieselben WebView-Hit-Test-Metadaten wie der
fruehe Prototyp. Das heutige Verhalten wird deshalb eng in der eingebauten
WebExtension umgesetzt. Ein kurzer Tap wird dabei nicht konsumiert und bleibt
vollstaendig in YouTubes normalem Ereignispfad.

Bei einem einzelnen echten HTTP(S)-YouTube-Link gilt:

1. `touchstart` beziehungsweise ein primaerer Pointer startet nur einen
   450-ms-Hold-Timer; noch wird kein Seitenereignis verhindert.
2. Eine Bewegung ueber der Toleranz vor Ablauf des Timers bricht die Erkennung
   ab, damit vertikales Scrollen normal bleibt.
3. Nach Ablauf erscheint ein isoliertes, nicht klickbares WebExtension-
   Overlay mit zwei runden Zielen und pulsierenden Richtungspfeilen.
4. Nach links ziehen und loslassen sendet `SHOW_LINK_MENU`; nach rechts ziehen
   und loslassen sendet `OPEN_NEW_TAB`.
5. Loslassen in der Mitte, `touchcancel` oder Pointer-Abbruch bleibt ohne
   Aktion. Folge-`tap`-/`click`-Ereignisse derselben Hold-Geste werden
   unterdrueckt.

Das linke Ziel oeffnet ein natives Auswahlmenue fuer aktuellen Tab, neuen Tab,
externes Oeffnen, Kopieren und Teilen. Das rechte Ziel bleibt der schnelle
Neuer-Tab-Pfad. Beide Ziele laufen vor der Navigation durch
`YouTubeNavigationPolicy`.

`contextmenu` bleibt als Gecko-Rueckfall erhalten. Wenn die laufende
Touch-Geste bekannt ist, aktiviert es nur den Slider. Meldet ein Geraet dagegen
ausschliesslich `contextmenu`, bleibt der bewaehrte direkte Neuer-Tab-Pfad
verfuegbar.

Die WebExtension liefert nur Nachrichtentyp und Ziel-URL. Die native Bridge
akzeptiert Nachrichten ausschliesslich von der zugehoerigen Session und aus
dem Top-Level-Dokument. `LinkInteractionPolicy` validiert Typ, HTTP(S)-Schema,
Standardport, fehlende Userinfo und den exakten YouTube-Host erneut, bevor
`MainActivity` eine Aktion ausfuehrt.

## Abgrenzung zu Player-Kontextmenues

Der Link-Long-Press ist nicht dasselbe wie YouTubes technisches
Player-Kontextmenue. Im immersiven Landscape-Modus wird `.ytp-contextmenu`
gezielt unterdrueckt, damit Pinch-to-Zoom nicht `Statistiken fuer
Interessierte` oeffnet. Details und Regressionstest stehen in
[`fullscreen-context-menu.md`](fullscreen-context-menu.md).

Bei Aenderungen an `contextmenu`-Listenern ist die Reihenfolge wichtig:

- Player-Controls und Tube-NEXT-Overlays duerfen keine Linkaktion ausloesen.
- Kommentar- und Einstellungsmenues duerfen nicht global unterdrueckt werden.
- Die Landscape-Sperre darf ausserhalb des Players kein allgemeines
  Browserverhalten verschlucken.

## Regressionstest

1. Abgemeldet YouTube oeffnen, Consent-Auswahl bedienen und bis zur
   YouTube-Seite zurueckkehren.
2. Login inklusive Account-Auswahl und Zwei-Faktor-Schritt durchlaufen:
   notwendige Google-Seiten bleiben intern; die Sitzung bleibt nach
   App-Neustart erhalten.
3. Waehrend des Flows pruefen, dass die Toolbar keine
   `RotateCookiesPage`- oder `about:*`-URL dauerhaft anzeigt.
4. Einen beliebigen externen Nicht-YouTube-Link oeffnen: Er geht an eine
   externe Anwendung.
5. Kurzer Tap auf Home-, Shorts-, Abo-, Feed- und Watch-Link: normales
   YouTube-Verhalten, kein Tube-NEXT-Aktionsmenue.
6. Long-Press und links ziehen: Aktionsmenue erscheint; Abbrechen navigiert
   nicht. Alle fuenf Aktionen jeweils einmal pruefen.
7. Long-Press und rechts ziehen: genau ein neuer App-Tab mit dem Ziel entsteht.
8. Long-Press und mittig loslassen sowie vertikal scrollen: keine Linkaktion.
9. Long-Press auf normalen Text, Bild ohne YouTube-Link und Player-Control:
   keine Linkaktion.
10. Account-, Burger-, Kommentar- und Player-Menues normal bedienen.
11. Im Landscape-Player Pinch-to-Zoom und normales Einstellungsmenue pruefen;
   das technische Player-Kontextmenue bleibt verborgen.

## Historische Einordnung

Der fruehe Task belegt einen erfolgreichen ersten MVP mit Tabs,
Cookie-Persistenz, YouTube-Intents und WebView-Fullscreen. Seine konkrete
WebView-Implementierung ist durch GeckoView ersetzt. Dauerhaft uebernommen
werden nur die oben genannten Routing-, Sicherheits- und Testregeln.
