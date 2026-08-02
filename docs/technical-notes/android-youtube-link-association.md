# Android-Zuordnung fuer eingehende YouTube-Links

## Systemgrenze

Tube NEXT verarbeitet `http`- und `https`-Links fuer `youtube.com`,
`www.youtube.com`, `m.youtube.com` und `youtu.be`. Die Domains gehoeren jedoch
nicht Tube NEXT. Eine automatische verifizierte Zuordnung ist deshalb ohne
eine passende `assetlinks.json` auf jedem YouTube-Host nicht moeglich.

Der Intent-Filter verwendet dennoch `android:autoVerify="true"`, damit die
Hosts am Android-App-Link-System teilnehmen und ihr Nutzerzustand ab Android
12 ueber `DomainVerificationManager` lesbar ist. Die erwartbar fehlschlagende
Eigentumsverifikation erteilt Tube NEXT keine Rechte. Erst die ausdrueckliche
Auswahl des Nutzers unter `Standardmaessig oeffnen` ordnet die Hosts zu;
Androids Nutzerentscheidung hat Vorrang vor der fehlenden Verifikation.

Referenz:
[`Verify App Links`](https://developer.android.com/training/app-links/verify-applinks#request-the-user-to-associate-your-app-with-a-domain).

## Nutzerfuehrung

Wenn die Zuordnung noch nicht vollstaendig ist, erscheint ein erklaerender
Startdialog mit:

- `Jetzt einrichten`: oeffnet direkt
  `Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` fuer das eigene Paket,
- `Spaeter`: erinnert auf Android 12 und neuer fruehestens nach sieben Tagen,
- `Nicht mehr fragen`: deaktiviert nur den automatischen Hinweis.

Auf Android 10 und 11 ist der differenzierte Domainzustand nicht ueber
`DomainVerificationManager` verfuegbar. Dort erscheint der automatische
Hinweis nur einmal; die Einstellungen zeigen danach bewusst keinen
behaupteten Aktivzustand.

Die App-Einstellungen enthalten unabhaengig vom Hinweis einen Bereich
`YouTube-Links` mit aktuellem Status und dauerhaftem Einstieg in die
Android-Systemseite. Nach der Rueckkehr aus der Systemseite wird der Zustand
erneut gelesen und als Snackbar sowie in der noch offenen Einstellungsansicht
aktualisiert.

Startdialoge werden nicht gestapelt. Der Post-Install-Hinweis fuer die
APK-Installationsfreigabe hat Vorrang, danach folgt das Link-Onboarding und
erst in einem spaeteren Start gegebenenfalls der Akku-Hinweis.

## Buildvarianten

Der eingehende VIEW-Filter liegt auf dem Aktivitaetsalias
`YouTubeLinkHandler`. Seine Aktivierung wird pro Buildtyp gesetzt:

- `release`: aktiv,
- `diagnosticRelease`: aktiv; gleiche App-ID und Produktionssignatur wie der
  Release, daher bleibt die nutzerspezifische Linkzuordnung bei Updates
  erhalten,
- `debug`: deaktiviert,
- `localRelease`: deaktiviert.

Damit konkurrieren parallel installierte Entwicklungsvarianten nicht mit der
produktiven beziehungsweise diagnostischen App um YouTube-Links. Die
deaktivierten Varianten zeigen auch kein Link-Onboarding und keinen
irrefuehrenden Linkstatus in ihren Einstellungen.

## Regression

1. Merged Manifest pruefen: Alias in `release` und `diagnosticRelease` aktiv,
   in `debug` und `localRelease` deaktiviert.
2. Frische Installation ohne Zuordnung: Hinweis erscheint genau einmal und
   oeffnet die app-spezifische Systemseite.
3. `Spaeter`: kein erneuter Hinweis innerhalb von sieben Tagen.
4. `Nicht mehr fragen`: kein weiterer automatischer Hinweis; manueller
   Einstellungen-Einstieg bleibt vorhanden.
5. Alle vier Hosts aktivieren und zurueckkehren: Status meldet vollstaendige
   Zuordnung.
6. Nur einzelne Hosts aktivieren: Teilstatus mit Anzahl anzeigen.
7. Einen `youtu.be`- und einen `www.youtube.com/watch`-Link von ausserhalb der
   App oeffnen: genau ein neuer Tube-NEXT-Tab entsteht.
8. Debug- und Local-Release parallel installieren: Beide duerfen fuer einen
   externen YouTube-Link nicht als Kandidat erscheinen.
9. Diagnose-Release ueber einen regulaeren Release und umgekehrt installieren:
   bestehende Linkauswahl, Tabs, Login und Einstellungen bleiben erhalten.
