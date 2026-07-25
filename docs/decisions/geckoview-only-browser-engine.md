# GeckoView als einzige Browser-Engine

Status: verbindlich

## Entscheidung

Tube NEXT verwendet GeckoView als einzige Browser-Engine fuer alle Tabs. Die
App behaelt ihre native Android-Shell mit Toolbar, Tab-Verwaltung,
Persistenz, Intent-Verarbeitung und System-Mediensteuerung. Innerhalb eines
Produkt-Builds gibt es jedoch keinen parallelen Android-WebView-, Custom-Tab-
oder gemischten Tab-Pfad.

`BrowserEngine` und `EngineTab` bleiben als Trennung zwischen App-Shell und
Browser-Laufzeit bestehen. Diese Abstraktion ist kein Auftrag, eine zweite
Engine auf Vorrat zu pflegen.

## Hintergrund

Die fruehe App basierte auf Android WebView. Mehrere Versuche kombinierten
deren YouTube-Wiedergabe mit Lifecycle-Abfragen, JavaScript-Status,
Foreground-Service, Media-Keys und einer Medienbenachrichtigung. Die App
konnte den sichtbaren Zustand teilweise erkennen, aber die WebView-
Wiedergabe im Hintergrund nicht verlaesslich aktiv halten.

Ein Custom-Tab-Versuch hielt Audio stabiler, brach jedoch die eigene
Tab-Verwaltung, das einheitliche Erscheinungsbild und den vorgesehenen
App-Flow.

Die Idee, bei Fokusverlust nur den YouTube-Audiostream an einen eigenen
nativen Player zu uebergeben, besitzt keinen offiziellen stabilen
Uebergabepunkt. Sie wuerde einen inoffiziellen Stream-Resolver,
signierte kurzlebige URLs und Sonderbehandlung fuer MSE, EME, Login- und
Live-Inhalte benoetigen. Das widerspricht den Nicht-Zielen des Projekts und
waere technisch fragil.

## Gewaehlte Medienarchitektur

Die Wiedergabe bleibt in derselben offiziellen YouTube-Seite und derselben
Gecko-Session. GeckoView meldet den Medienzustand und Transportbefehle ueber
seine `MediaSession.Delegate`-Schnittstelle an die App.

Der Android-Foreground-Service und seine `MediaSession` sind eine native
Steuerungs- und Lebenszyklusschicht fuer diese Gecko-Wiedergabe. Sie sind kein
zweiter Audio-Player und extrahieren keinen Stream. Details stehen in
[`../technical-notes/background-audio-notification.md`](../technical-notes/background-audio-notification.md).

## Folgen

- Alle Tabs sehen gleich aus und teilen GeckoRuntime, Profil, Cookies und
  Engine-Verhalten.
- WebView-spezifische Klassen und Runtime-Pfade bleiben entfernt.
- Gecko-spezifische Unterschiede werden in `GeckoBrowserEngine` oder der
  eingebauten WebExtension gekapselt.
- DOM-, CSS- und Touch-Anpassungen laufen ueber die WebExtension, nicht ueber
  Android-WebViews `evaluateJavascript()`.
- Die App traegt die groessere GeckoView-Abhaengigkeit und deren
  ABI-spezifische Build-Artefakte.
- Engine-, Session- und Surface-Lebenszyklen muessen explizit behandelt
  werden; siehe
  [`../technical-notes/geckoview-runtime-and-navigation.md`](../technical-notes/geckoview-runtime-and-navigation.md)
  und
  [`../technical-notes/gecko-black-surface-recovery.md`](../technical-notes/gecko-black-surface-recovery.md).
- Release-Minifizierung bleibt eine getrennt zu pruefende GeckoView-Grenze;
  siehe
  [`../technical-notes/build-variants-and-geckoview-r8.md`](../technical-notes/build-variants-and-geckoview-r8.md).

## Nicht wieder einfuehren

- gemischte WebView- und Gecko-Tabs,
- Custom Tabs als Watch- oder Hintergrund-Audio-Hauptpfad,
- einen nativen YouTube-Stream-Resolver,
- Audio-Handover zwischen Web- und Zweitplayer,
- einen ungenutzten WebView-Fallback "fuer alle Faelle",
- direkte Abhaengigkeiten der `MainActivity` von Gecko-internen Details, wenn
  der vorhandene Engine-Vertrag sie sinnvoll kapseln kann.

## Kriterien fuer eine spaetere Neubewertung

Eine andere Engine oder Medienarchitektur wird erst geprueft, wenn ein
konkret reproduzierbarer Produktengpass vorliegt. Eine Alternative muss
mindestens belegen:

1. gleiche Login-, Cookie- und Tab-Persistenz,
2. Desktop-Watch mit den benoetigten Desktop-Funktionen,
3. stabile Hintergrundwiedergabe und Android-Mediensteuerung,
4. keine Stream-Extraktion oder DRM-Umgehung,
5. einen vollstaendigen Migrations- und Rueckfallplan ohne dauerhaften
   Hybridbetrieb.

## Mindestpruefung nach Engine-Aenderungen

1. Login und Account-Wechsel ueber App-Neustart.
2. Mindestens zwei Tabs, Wiederherstellung und Tabwechsel.
3. Mobile YouTube-Bereiche sowie Desktop-Watch.
4. Rotation, immersiver Player, Pinch-to-Zoom und YouTube-Controls.
5. Hintergrund-Audio, Notification und Bluetooth-/Headset-Tasten.
6. Externe YouTube-Intents und interne Account-/Consent-Flows.
7. Activity-Neustart ohne zweite GeckoRuntime oder verlorene Session.

## Historie

- `119cd8f`: Engine-Vertraege und Migrationsrahmen.
- `5c441fa`: Gecko-Hard-Cut und Entfernung der WebView-Runtime.
- `3c470b8`: GeckoRuntime-, Session- und Hintergrund-Audio-Stabilisierung.

