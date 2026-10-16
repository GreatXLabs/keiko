from controller import Supervisor
r = Supervisor()
ts = int(r.getBasicTimeStep())
self_node = r.getSelf()
self_node.enableContactPointsTracking(ts, False)
self_node.getField("rotation").setSFRotation([0,0,1,0.0])  # face +X (east)
self_node.getField("translation").setSFVec3f([0.3, -0.2, 0.001])
motors = {}
for n in ["front_left_motor","rear_left_motor","front_right_motor","rear_right_motor"]:
    m = r.getDevice(n); m.setPosition(float("inf")); m.setVelocity(0.0); motors[n]=m
def drive(l,rr):
    motors["front_left_motor"].setVelocity(l); motors["rear_left_motor"].setVelocity(l)
    motors["front_right_motor"].setVelocity(rr); motors["rear_right_motor"].setVelocity(rr)
hit = None
for i in range(500):
    drive(8.0, 8.0)
    r.step(ts)
    pos = self_node.getPosition()
    for cp in self_node.getContactPoints(False):
        other = r.getFromId(cp.getNodeId())
        d = other.getDef() if other else ""
        if d and d != "ORQUITA":
            print(f"i={i} pos=({pos[0]:.3f},{pos[1]:.3f}) HIT NODE={d} type={other.getTypeName()}", flush=True)
            hit = d
    if hit:
        break
print("RESULT:", hit if hit else "NO WALL HIT", flush=True)
r.simulationQuit(0)
