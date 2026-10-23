package com.dragsim.macro.client.gui;

/** Strict finite coordinate parsing shared by the route editor and tests. */
public final class RouteCoordinateInput {
	public record Result(boolean valid, double x, double y, double z, String error) {
	}

	private RouteCoordinateInput() {
	}

	public static Result parse(String x, String y, String z) {
		try {
			double px = Double.parseDouble(x.trim());
			double py = Double.parseDouble(y.trim());
			double pz = Double.parseDouble(z.trim());
			if (!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)) {
				return new Result(false, 0, 0, 0, "coordinates must be finite numbers");
			}
			return new Result(true, px, py, pz, "");
		} catch (Exception e) {
			return new Result(false, 0, 0, 0, "enter valid X, Y, and Z coordinates");
		}
	}
}
