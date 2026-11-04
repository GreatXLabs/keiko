from multiprocessing import Pool
import numpy as np
import sim_altura_us as S
if __name__ == "__main__":
    DIST = np.round(np.arange(0.04, 0.185, 0.02), 3); YS = np.round(np.arange(-0.10, 0.101, 0.025), 3)
    for pitch in (0.0, -2.0, 2.0):
        args = [(H, pitch, 0.06, yaw, d, y) for H in (0.05, 0.055, 0.06) for yaw in (0.0, 30.0) for d in DIST for y in YS]
        with Pool() as p: obs = p.map(S.caso_obstaculo, args)
        cargs = [(H, pitch, d, y) for H in (0.05, 0.055, 0.06) for d in np.round(np.arange(0.03, 0.185, 0.01), 3) for y in YS]
        with Pool() as p: can = p.map(S.caso_canica, cargs)
        print(f"\n== inclinación {pitch:+.0f}° (conservadora) ==")
        for H in (0.05, 0.055, 0.06):
            for yaw in (0.0, 30.0):
                ok = [r['conservadora'][0] for a, r in obs if a[0]==H and a[3]==yaw]
                malos = sorted({(int(round(a[4]*100)), int(round(a[5]*100))) for a, r in obs if a[0]==H and a[3]==yaw and not r['conservadora'][0]})
                print(f"  H={H*100:.1f} obst6cm yaw{yaw:.0f}: confirma {100*np.mean(ok):.0f}%  fallas (d,y): {malos}")
            ct = [r['conservadora'][0] or r['conservadora'][1] for a, r in can if a[0]==H]
            ct2 = [r['optimista'][0] or r['optimista'][1] for a, r in can if a[0]==H]
            print(f"  H={H*100:.1f} canica con problema (<=18cm): conserv {100*np.mean(ct):.0f}%  optim {100*np.mean(ct2):.0f}%")
