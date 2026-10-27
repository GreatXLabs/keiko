import numpy as np

from scan_match import icp_2d


def test_icp_recovers_known_transform():
    rng = np.random.default_rng(0)
    src = rng.uniform(-500, 500, size=(60, 2))

    true_angle = np.radians(15)
    true_R = np.array([[np.cos(true_angle), -np.sin(true_angle)],
                        [np.sin(true_angle), np.cos(true_angle)]])
    true_t = np.array([80.0, -30.0])
    dst = (true_R @ src.T).T + true_t

    R, t, angle_deg, error = icp_2d(src, dst)

    assert abs(angle_deg - 15) < 0.5, f"angulo esperado ~15, dio {angle_deg}"
    assert np.allclose(t, true_t, atol=1.0), f"traslacion esperada ~{true_t}, dio {t}"
    assert error < 0.1, f"error residual muy alto: {error}"
    print(f"OK: angulo={angle_deg:.2f} t={t} error={error:.4f}")


if __name__ == "__main__":
    test_icp_recovers_known_transform()
