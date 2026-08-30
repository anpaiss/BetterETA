# Handover — BetterEta

Aggiornato: 2026-08-24. Vedi anche `..\HANDOVER.md` (ambiente, JAVA_HOME, convenzioni) e la memoria persistente.

## Stato

- **Beta personale, repo PRIVATA**, branch `v2-dashboard` (= default su GitHub; `master` è vecchio e divergente, ha un fix del 04/07 mai riconciliato). Firma debug, distribuzione solo `adb install -r`. NON sottoposta a Hammerhead.
- Sul Karoo dell'utente: ultima build installata e funzionante (estensione ripartita regolarmente dopo il reboot del 24/08 sera, modello a 54k campioni).

## Cosa fa (in breve)

ETA per la route in navigazione basata su un modello adattivo velocità/pendenza (5 bucket, EMA con finestra ~20 min = 1200 campioni a 1 Hz, persistito in SharedPreferences). Pipeline waypoint→segmenti con smoothing quote 50 m; posizione = dead reckoning + snap GPS + rescue globale. 3 data field: Dashboard (pagina intera), ETA assoluto, tempo rimanente.

## Lezioni pagate care (non regredire!)

1. **Mai resettare modello/posizione sulle transizioni RideState** (auto-pausa continua) né sugli `Idle` transitori di navigazione (`navActive=false`, route resta in memoria). Reset solo quando arriva una polyline DIVERSA.
2. **Polyline vuote in NavigatingToDestination vanno ignorate** (prima cancellavano la route a metà giro).
3. **Rescue globale**: sceglie il candidato più coerente con la posizione stimata (non il più vicino al fix — su anelli/andata-ritorno teleportava a inizio/fine route) e rifiuta salti implausibili: `|salto| ≤ secondi_da_ultimo_aggancio_buono (orologio reale) × 20 m/s + 500 m`. Orologio iniettabile nel costruttore di `SegmentPipeline` per i test.
4. Elevation polyline = coppie (distanza, quota) precisione 1 (scala 10); `NavigatingToDestination` non ha elevazioni → dislivello "sconosciuto" (-1), NON zero.
5. Il banner "next cue" del Karoo copre la fascia bassa della pagina senza notifica SDK: la dashboard la lascia vuota di proposito.

## Feature recenti (agosto)

- **PACE VS PLAN**: delta vs prima stima valida del giro, SOLO tempo in movimento (soglia 0,5 m/s) — le soste non lo muovono. Baseline catturata al primo ETA con `pipeline.isLocated()`, reset solo al cambio route.
- **Confronto ETA vs Karoo** (opzione B): 1 campione/min (nostra previsione + `TIME_TO_DESTINATION` nativo + moving time); all'arrivo ogni campione è giudicato contro il tempo in movimento reale rimanente (scartati quelli con <300 s davanti; minimo 5 campioni). Risultato in punti % (positivo = BetterEta meglio) salvato in `better_eta_results` (ring 20); media + lista nella MainActivity.
- **DA VERIFICARE al prossimo giro con route**: se il Karoo pubblica davvero `TIME_TO_DESTINATION` in streaming. Se no, nel log appare `STATS: Confronto ETA non registrato: N campioni validi` → serve un piano B per leggere l'ETA di sistema.

## Diagnostica

- Log su file: `adb pull /sdcard/Android/data/com.anpaiss.bettereta/files/better_eta.log` (rotazione 512 KB → `.old`). Tag: SYS/ROUTE/GPS/RIDE/MODEL/ETA/STATS. Copie locali in `logs/` (gitignored).
- **Routine consolidata con l'utente**: dopo ogni giro, analizzare il log a caccia di incongruenze (snap anomali, reset, baseline). I timestamp NON sono monotoni attorno ai boot (risync NTP).
- Test: `.\gradlew.bat :app:testDebugUnitTest` (39 test; pipeline/rescue/modello coperti).

## Aperture

- Modello unico per tutte le bici: se l'utente alternasse ebike/muscolare servirebbe un profilo-modello per bici (discusso, non richiesto).
- Se un giorno si pubblica: rendere pubblica la repo o hostare l'APK, versionName reale (ora "1.0"), firma release.
