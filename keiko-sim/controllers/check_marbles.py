from controller import Supervisor

robot = Supervisor()
ts = int(robot.getBasicTimeStep())
r = robot.getSelf()

for i in range(1, 4):
    m = robot.getFromDef(f'LOBITO_{i}')
    if m:
        pos = m.getPosition()
        print(f'LOBITO_{i} pos: [{pos[0]:.3f}, {pos[1]:.3f}, {pos[2]:.3f}]')

robot.simulationQuit(0)
