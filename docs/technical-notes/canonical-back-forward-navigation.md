# Kanonische Zurueck-/Vorwaerts-Navigation

Stand: 01.08.2026

## Zweck

Jeder Tube-NEXT-Tab besitzt eine eigene sichtbare YouTube-History. Sie ist
von Geckos roher Session-History getrennt, weil Login-, Consent-, Cookie- und
`about:*`-Zwischenziele zwar in derselben Session bleiben muessen, aber keine
eigenen nutzersichtbaren Toolbar-Schritte sind.

`CanonicalTabHistory` kapselt Eintraege, aktuellen Index und eine laufende
History-Navigation. Die Toolbar-Schaltflaechen und Android-System-Zurueck
verwenden denselben nativen Back-Pfad; dadurch kann eine Geste nicht doppelt
ausgefuehrt werden.

## Kanonisierung

- Nur die unterstuetzten HTTP(S)-YouTube-Hosts werden aufgenommen.
- Fragmente normaler Seiten werden entfernt.
- Watch- und `youtu.be`-Ziele werden auf
  `https://www.youtube.com/watch?v=<video-id>` reduziert.
- Login-/Consent- und andere Fremdziele werden nicht aufgenommen.

Die Kanonisierung dient der Identitaet eines History-Schritts. Sie darf nicht
als Anlass fuer Replay-Loads aus Gecko-History-Callbacks verwendet werden.

## Navigation und Forward-Verwerfen

Zurueck und Vorwaerts verschieben den kanonischen Index und loesen genau eine
Navigation ueber `EngineTab.loadUrl()` aus. Bis der Location-Callback das Ziel
bestaetigt, ist die History als pending markiert und beide Buttons sind
deaktiviert. Ein Fehler hebt diesen Zustand wieder auf.

Beginnt stattdessen eine normale Navigation auf ein neues sichtbares Ziel,
werden Eintraege vor dem bisherigen Forward-Ende sofort entfernt. SPA-
Location-Aenderungen laufen durch denselben Record-Pfad. Moduswechsel werden
weiterhin vor dem Laden von `YouTubeNavigationPolicy` behandelt.

Geckos `canGoBack`/`canGoForward` bleibt als begrenzter Fallback erhalten,
wenn die kanonische History kein Ziel hat. Das ist insbesondere fuer interne
Login-/Consent-Zwischenschritte notwendig. Die Schaltflaechen lesen beide
Zustaende immer vom aktuell ausgewaehlten Tab und werden beim Tabwechsel sowie
bei Gecko-Availability-Callbacks sofort aktualisiert.

## Oberflaeche

Zurueck, Vorwaerts und Reload verwenden je 40 dp breite Touch-Ziele. Die
seitlichen Toolbar-Paddings und URL-Abstaende wurden reduziert, damit die
vorhandene URL-Anzeige sowie Tab- und Einstellungsbuttons auch auf schmalen
Displays erhalten bleiben. Deaktivierte History-Aktionen sind nicht klickbar
und optisch abgeblendet.

## Automatisierte Tests

`CanonicalTabHistoryTest` prueft offline:

- Back und Forward samt Aktivierungszustaenden,
- genau einen pending History-Schritt,
- Verwerfen der Forward-History bei einem neuen Ziel,
- Watch-/`youtu.be`-Kanonisierung,
- Ausschluss von Login- und Fremdzielen,
- Wiederherstellung ohne haengenden pending-Zustand.

## Geraete-Regressionstest

1. Home, Suche/Feed, Kanal und Watch nacheinander oeffnen.
2. Zurueck und Vorwaerts bedienen; jeder Tap navigiert genau einmal.
3. Nach Zurueck ein neues Ziel oeffnen: Vorwaerts ist deaktiviert.
4. Zwischen mindestens zwei Tabs wechseln: beide Buttonzustaende wechseln
   sofort mit.
5. Mobile/ Desktop-Watch-Grenzen in beide Richtungen durchlaufen.
6. Android-System-Zurueck mit dem Toolbar-Back vergleichen; kein Doppelschritt.
7. Login/Consent oeffnen und zurueckkehren; interne Zwischenziele erscheinen
   nicht als kanonische Toolbar-History.
8. Watch-Wiedergabe, Position und YouTube-Controls nach History-Navigation
   pruefen; keine zusaetzlichen Replay-Loads duerfen auftreten.
