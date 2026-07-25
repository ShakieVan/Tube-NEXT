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

`LinkInterceptor.isInternalFlowUri()` laesst HTTP(S)-Navigation innerhalb der
App zu, wenn sie:

- auf einen unterstuetzten YouTube-Host zeigt oder
- Teil eines Google-Account-, Consent- oder Cookie-Flows ist.

Andere HTTP(S)-Ziele werden von `GeckoBrowserEngine.onLoadRequest()` abgelehnt
und ueber den App-Callback an eine externe Anwendung gegeben.

`YouTubeNavigationPolicy` entscheidet unabhaengig davon ueber den
Darstellungsmodus. Nur Watch-Seiten und `youtu.be` erhalten Desktop-UA.
Account- und Consent-Zwischenziele sind kein Desktop-Watch-Signal.

Die Toolbar uebernimmt nur nutzersichtbare YouTube-URLs. Insbesondere
`accounts.youtube.com/RotateCookiesPage`, `about:*` und andere transiente
Zwischenziele ersetzen nicht die letzte sinnvolle YouTube-Adresse.

## Long-Press auf Links

GeckoView liefert hier nicht dieselben WebView-Hit-Test-Metadaten wie der
fruehe Prototyp. Das heutige Verhalten wird deshalb eng in der eingebauten
WebExtension umgesetzt:

1. `handleDocumentContextMenu()` reagiert nur auf ein `a[href]`.
2. Nur HTTP(S)-Links zu YouTube werden akzeptiert.
3. Das Content-Script verhindert das Seiten-Kontextmenue und sendet
   `OPEN_NEW_TAB`.
4. Die native Bridge akzeptiert nur Nachrichten der zugehoerigen Session aus
   dem Top-Level-Dokument und validiert die URL erneut als YouTube-URL.
5. `MainActivity` legt danach den neuen Tab an.

Das ist bewusst **kein vollstaendiges Browser-Kontextmenue**. Der aktuelle
Stand bietet auf diesem Pfad direkt "YouTube-Link in neuem Tab", aber nicht:

- im aktuellen Tab oeffnen,
- Link kopieren oder teilen,
- extern oeffnen,
- Bild speichern oder andere Bildaktionen.

Falls diese Funktionen spaeter ergaenzt werden, muss die WebExtension nur
minimal Link-/Bildmetadaten liefern. Auswahl, Darstellung und Android-Aktion
gehoeren in die App-Shell. Jede native Nachricht bleibt nach Session,
Top-Level-Sender, Typ, Schema und Ziel-URL zu validieren.

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
5. Long-Press auf einen YouTube-Link: genau ein neuer App-Tab mit dem Ziel
   entsteht.
6. Long-Press auf normalen Text, Bild ohne YouTube-Link und Player-Control:
   kein neuer App-Tab.
7. Im Landscape-Player Pinch-to-Zoom und normales Einstellungsmenue pruefen;
   das technische Player-Kontextmenue bleibt verborgen.

## Historische Einordnung

Der fruehe Task belegt einen erfolgreichen ersten MVP mit Tabs,
Cookie-Persistenz, YouTube-Intents und WebView-Fullscreen. Seine konkrete
WebView-Implementierung ist durch GeckoView ersetzt. Dauerhaft uebernommen
werden nur die oben genannten Routing-, Sicherheits- und Testregeln.
