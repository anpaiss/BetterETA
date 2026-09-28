"""Soste per ora pedalata dai FIT del Karoo (adb pull /sdcard/FitFiles logs/fit).

  pip install fitdecode
  python tools/stops_from_fit.py logs/fit/FitFiles

Per ogni giro >= 10 km: tempo pedalato (record consecutivi a <= 2 s con
velocità >= 0,5 m/s), tempo fermo (il resto: fermate e auto-pause), minuti di
sosta per ora pedalata. Poi confronta due stime delle soste residue a metà
giro: fissa (mediana storica) e adattiva (rapporto osservato fino a lì,
mescolato con la storica). Risultato del 28/09 su 76 giri: la fissa vince,
perché le soste arrivano a grumi e il rapporto osservato nella prima parte
del giro non predice il resto.
"""
import datetime
import glob
import statistics as st
import sys

import fitdecode


def load(folder):
    rides = []
    for f in sorted(glob.glob(folder + "/*.fit")):
        try:
            recs = []
            with fitdecode.FitReader(f) as fr:
                for fm in fr:
                    if not (isinstance(fm, fitdecode.FitDataMessage) and fm.name == "record" and fm.has_field("timestamp")):
                        continue
                    sp = None
                    if fm.has_field("enhanced_speed") and fm.get_value("enhanced_speed") is not None:
                        sp = fm.get_value("enhanced_speed")
                    elif fm.has_field("speed"):
                        sp = fm.get_value("speed")
                    di = fm.get_value("distance") if fm.has_field("distance") else None
                    recs.append((fm.get_value("timestamp"), sp, di))
        except Exception:
            continue
        if len(recs) < 60:
            continue
        dist = max([d for _, _, d in recs if d is not None] or [0])
        elapsed = (recs[-1][0] - recs[0][0]).total_seconds()
        if dist < 10_000 or elapsed < 1_800:
            continue
        mv, stp = [0.0], [0.0]
        for (ta, sa, _), (tb, sb, _) in zip(recs, recs[1:]):
            dt = (tb - ta).total_seconds()
            if dt <= 0 or dt > 3_600:
                mv.append(mv[-1]); stp.append(stp[-1]); continue
            moving = dt <= 2 and (sb or 0) >= 0.5
            mv.append(mv[-1] + (dt if moving else 0))
            stp.append(stp[-1] + (0 if moving else dt))
        rides.append((recs[0][0] + datetime.timedelta(hours=2), dist / 1000, mv, stp))
    return rides


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else "logs/fit/FitFiles"
    rides = load(folder)
    print(f"giri >= 10 km: {len(rides)}")
    print("data              km   pedal  fermo  fermo/h")
    per_h = []
    for t0, km, mv, stp in rides:
        m, s = mv[-1] / 60, stp[-1] / 60
        per_h.append(s / (m / 60))
        print(f"{t0:%Y-%m-%d %H:%M} {km:5.1f}  {m:5.0f}  {s:5.0f}   {per_h[-1]:5.1f}")
    per_h.sort()
    median = st.median(per_h)
    print(f"\nfermo/h: mediana {median:.1f}, media {st.mean(per_h):.1f}, p25 {per_h[len(per_h)//4]:.1f}, p75 {per_h[3*len(per_h)//4]:.1f}")

    prior = median / 60  # min di sosta per min pedalato
    for name, w in [("fissa (mediana)", None), ("adattiva, peso storico 1 h", 1.0), ("adattiva, peso storico 2 h", 2.0)]:
        errs = {q: [] for q in (0.25, 0.5, 0.75)}
        for _, _, mv, stp in rides:
            M, S = mv[-1], stp[-1]
            for q in errs:
                i = next(k for k, m in enumerate(mv) if m >= q * M)
                rate = prior * 60 if w is None else (median * w + stp[i] / 60) / (w + mv[i] / 3600)
                errs[q].append(abs(rate * (M - mv[i]) / 3600 - (S - stp[i]) / 60))
        print(f"{name:28s} errore sulle soste residue (min): " + "  ".join(
            f"al {int(q*100)}%: mediana {st.median(v):.0f}, p75 {sorted(v)[3*len(v)//4]:.0f}" for q, v in errs.items()))


if __name__ == "__main__":
    main()
