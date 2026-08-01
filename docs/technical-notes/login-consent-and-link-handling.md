# Login-, Consent- und Link-Handling

Stand: am 25.07.2026 gegen den aktuellen GeckoView-Code und den fruehen
Projekt-Task `Setze AGENTS-Anweisungen um` geprueft

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
- exakt auf `accounts.google.com` oder `consent.google.com` zeigt.

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

## Kurz-Tap und Long-Press auf Links

GeckoView liefert hier nicht dieselben WebView-Hit-Test-Metadaten wie der
fruehe Prototyp. Das heutige Verhalten wird deshalb eng in der eingebauten
WebExtension umgesetzt. Ein normaler kurzer Tap auf einen geeigneten
HTTP(S)-YouTube-Link wird vor der Navigation angehalten und als
`SHOW_LINK_MENU` an die App-Shell gemeldet. Das native Menue bietet:

- im aktuellen Tab oeffnen,
- in genau einem neuen Tab oeffnen,
- ueber eine andere App oeffnen,
- kopieren,
- teilen,
- oder Schliessen ohne Aktion.

Aktueller und neuer Tab navigieren ueber die normale Engine-API; dadurch wird
`YouTubeNavigationPolicy` vor dem Laden angewendet. Die externe Aktion
schliesst Tube NEXT aus dem Android-Auswahldialog aus und kann daher nicht als
neuer Intent in dieselbe App zuruecklaufen.

Das Kurz-Tap-Handling ignoriert modifizierte und synthetische Klicks,
Download- und JavaScript-Links sowie Ziele ausserhalb der unterstuetzten
YouTube-Hosts. Es greift ebenfalls nicht in Player, Controls, Popup-Menues,
Dialoge, Account-Menues, Buttons, Tube-NEXT-Overlays oder explizite
Login-/Consent-/Redirect-Pfade ein. Damit bleiben YouTubes Kommentar-,
Einstellungs- und Account-Interaktionen in ihrem bestehenden Ereignispfad.

Der Long-Press bleibt der direkte Schnellpfad ohne zusaetzliches Menue:

1. `handleDocumentContextMenu()` reagiert nur auf ein `a[href]`.
2. Nur HTTP(S)-Links zu YouTube werden akzeptiert.
3. Das Content-Script verhindert das Seiten-Kontextmenue und sendet
   `OPEN_NEW_TAB`.
4. Die native Bridge akzeptiert nur Nachrichten der zugehoerigen Session aus
   dem Top-Level-Dokument und validiert die URL erneut als YouTube-URL.
5. `MainActivity` legt danach den neuen Tab an.

Ein kurzer Unterdrueckungsmarker konsumiert den gegebenenfalls nach einem
Long-Press folgenden Click. Dadurch entstehen weder ein zweiter Tab noch das
Kurz-Tap-Menue. Die WebExtension liefert nur Nachrichtentyp und Ziel-URL;
Auswahl, Darstellung und Android-Aktion bleiben in der App-Shell. Jede native
Nachricht wird nach Session, Top-Level-Sender, Typ, Schema und Ziel-URL
validiert.

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
5. Kurzer Tap auf Home-, Kanal-, Such-, Feed- und Watch-Link: Menue erscheint,
   Abbrechen navigiert nicht.
6. Alle fuenf Menueaktionen pruefen; aktueller Tab navigiert einmal, neuer Tab
   entsteht genau einmal und extern oeffnen kehrt nicht in Tube NEXT zurueck.
7. Long-Press auf einen YouTube-Link: ohne Menue entsteht genau ein neuer
   App-Tab mit dem Ziel.
8. Long-Press auf normalen Text, Bild ohne YouTube-Link und Player-Control:
   kein neuer App-Tab.
9. Player-Einstellungen, Account-Menue, Kommentar-Menue sowie Bearbeiten und
   Loeschen eines eigenen Kommentars pruefen.
10. Im Landscape-Player Pinch-to-Zoom und normales Einstellungsmenue pruefen;
   das technische Player-Kontextmenue bleibt verborgen.

## Historische Einordnung

Der fruehe Task belegt einen erfolgreichen ersten MVP mit Tabs,
Cookie-Persistenz, YouTube-Intents und WebView-Fullscreen. Seine konkrete
WebView-Implementierung ist durch GeckoView ersetzt. Dauerhaft uebernommen
werden nur die oben genannten Routing-, Sicherheits- und Testregeln.
