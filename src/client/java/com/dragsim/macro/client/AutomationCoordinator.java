package com.dragsim.macro.client;

/** Runtime-only ownership for mutually exclusive automation modes. */
public final class AutomationCoordinator {
	public enum Owner {
		NONE("none"),
		COMBAT("Combat Macro"),
		ROUTE_WALKER("Route Walker"),
		MINING_BOT("Mining Bot"),
		BONEMERANG("Bonemerang Throw");

		private final String displayName;

		Owner(String displayName) {
			this.displayName = displayName;
		}

		public String displayName() {
			return displayName;
		}
	}

	private static Owner owner = Owner.NONE;

	private AutomationCoordinator() {
	}

	public static synchronized boolean tryAcquire(Owner requested) {
		if (requested == null || requested == Owner.NONE) {
			return false;
		}
		if (owner == Owner.NONE || owner == requested) {
			owner = requested;
			return true;
		}
		return false;
	}

	public static synchronized void release(Owner releasing) {
		if (owner == releasing) {
			owner = Owner.NONE;
		}
	}

	public static synchronized Owner owner() {
		return owner;
	}

	static synchronized void resetForTest() {
		owner = Owner.NONE;
	}
}
