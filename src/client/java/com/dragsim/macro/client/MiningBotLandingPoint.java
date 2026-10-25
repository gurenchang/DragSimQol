package com.dragsim.macro.client;

import java.util.Random;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where on the visible face the Mining Bot lands, like a hand does: on the near side, where the
 * crosshair comes in, a random share of the way toward the middle and a little off the line,
 * instead of the same exact point every time. Pure geometry; the caller ray-checks the result.
 */
public final class MiningBotLandingPoint {
	/** Share of the way from the entry edge toward the face middle. */
	static final double MIN_DEPTH = 0.15;
	static final double MAX_DEPTH = 0.55;
	/** Sideways spread as a share of the smaller face size. */
	static final double SIDE_SPREAD = 0.15;
	/** Kept clear of the face edges (blocks), at most a quarter of the face size. */
	static final double EDGE_MARGIN = 0.08;
	/** The point sits just inside the block so a ray toward it hits this face. */
	private static final double INSET = 1.0e-3;

	private MiningBotLandingPoint() {
	}

	/** Random landing point; see {@link #landingPoint} for the parameters. */
	public static Vec3 pick(Random random, Vec3 eye, Vec3 view, Vec3 faceHit, Direction face, AABB bounds) {
		double depth = MIN_DEPTH + random.nextDouble() * (MAX_DEPTH - MIN_DEPTH);
		// Triangular -1..1: mostly near the line, sometimes further off.
		double side = random.nextDouble() + random.nextDouble() - 1.0;
		return landingPoint(eye, view, faceHit, face, bounds, depth, side);
	}

	/**
	 * @param eye     eye position
	 * @param view    current view direction (unit)
	 * @param faceHit where a ray to the block's middle point hits the visible face
	 * @param face    that face
	 * @param bounds  the shape box containing faceHit (world coordinates)
	 * @param depth   0 = at the entry edge, 1 = at faceHit
	 * @param side    -1..1 sideways offset
	 */
	static Vec3 landingPoint(Vec3 eye, Vec3 view, Vec3 faceHit, Direction face, AABB bounds,
			double depth, double side) {
		Direction.Axis axis = face.getAxis();
		double minU = min(bounds, axis, 0), maxU = max(bounds, axis, 0);
		double minV = min(bounds, axis, 1), maxV = max(bounds, axis, 1);
		double marginU = Math.min(EDGE_MARGIN, (maxU - minU) * 0.25);
		double marginV = Math.min(EDGE_MARGIN, (maxV - minV) * 0.25);
		minU += marginU;
		maxU -= marginU;
		minV += marginV;
		maxV -= marginV;
		double hu = clamp(u(faceHit, axis), minU, maxU);
		double hv = clamp(v(faceHit, axis), minV, maxV);

		// Where the current view ray meets the face plane.
		double plane = faceHit.get(axis);
		double denom = view.get(axis);
		double baseU = hu;
		double baseV = hv;
		double dirU = 0.0;
		double dirV = 0.0;
		if (Math.abs(denom) > 1.0e-6) {
			double t = (plane - eye.get(axis)) / denom;
			if (t > 0.0) {
				double pu = u(eye, axis) + u(view, axis) * t;
				double pv = v(eye, axis) + v(view, axis) * t;
				dirU = pu - hu;
				dirV = pv - hv;
				if (pu >= minU && pu <= maxU && pv >= minV && pv <= maxV) {
					// Already on the face: stay close to where the crosshair is.
					double k = 1.0 - depth * 0.5;
					baseU = hu + dirU * k;
					baseV = hv + dirV * k;
				} else {
					// Entry edge: furthest point from the middle toward the crosshair still on the face.
					double s = Math.min(1.0, Math.min(reach(hu, dirU, minU, maxU), reach(hv, dirV, minV, maxV)));
					double k = s * (1.0 - depth);
					baseU = hu + dirU * k;
					baseV = hv + dirV * k;
				}
			}
		}
		double length = Math.hypot(dirU, dirV);
		double perpU = length > 1.0e-9 ? -dirV / length : 1.0;
		double perpV = length > 1.0e-9 ? dirU / length : 0.0;
		double spread = SIDE_SPREAD * Math.min(maxU - minU + 2 * marginU, maxV - minV + 2 * marginV);
		baseU = clamp(baseU + perpU * side * spread, minU, maxU);
		baseV = clamp(baseV + perpV * side * spread, minV, maxV);
		int normal = switch (axis) {
			case X -> face.getStepX();
			case Y -> face.getStepY();
			case Z -> face.getStepZ();
		};
		return compose(axis, plane - normal * INSET, baseU, baseV);
	}

	/** Largest step multiple along dir from h that stays within [min, max]. */
	private static double reach(double h, double dir, double min, double max) {
		if (dir > 1.0e-12) return (max - h) / dir;
		if (dir < -1.0e-12) return (min - h) / dir;
		return Double.POSITIVE_INFINITY;
	}

	// Face coordinates (u, v), the same pairing as MiningBotController.driftPoint.
	private static double u(Vec3 p, Direction.Axis axis) {
		return axis == Direction.Axis.X ? p.y : p.x;
	}

	private static double v(Vec3 p, Direction.Axis axis) {
		return axis == Direction.Axis.Z ? p.y : p.z;
	}

	private static double min(AABB b, Direction.Axis axis, int which) {
		if (which == 0) return axis == Direction.Axis.X ? b.minY : b.minX;
		return axis == Direction.Axis.Z ? b.minY : b.minZ;
	}

	private static double max(AABB b, Direction.Axis axis, int which) {
		if (which == 0) return axis == Direction.Axis.X ? b.maxY : b.maxX;
		return axis == Direction.Axis.Z ? b.maxY : b.maxZ;
	}

	private static Vec3 compose(Direction.Axis axis, double w, double u, double v) {
		return switch (axis) {
			case X -> new Vec3(w, u, v);
			case Y -> new Vec3(u, w, v);
			case Z -> new Vec3(u, v, w);
		};
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
