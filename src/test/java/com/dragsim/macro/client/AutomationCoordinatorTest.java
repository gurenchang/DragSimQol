package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutomationCoordinatorTest {
	@BeforeEach void reset() { AutomationCoordinator.resetForTest(); }

	@Test
	void rejectsOtherOwnersWithoutDisturbingCurrentOwner() {
		assertTrue(AutomationCoordinator.tryAcquire(AutomationCoordinator.Owner.COMBAT));
		assertFalse(AutomationCoordinator.tryAcquire(AutomationCoordinator.Owner.ROUTE_WALKER));
		AutomationCoordinator.release(AutomationCoordinator.Owner.MINING_BOT);
		assertEquals(AutomationCoordinator.Owner.COMBAT, AutomationCoordinator.owner());
	}

	@Test
	void releaseAllowsNextOwner() {
		assertTrue(AutomationCoordinator.tryAcquire(AutomationCoordinator.Owner.MINING_BOT));
		AutomationCoordinator.release(AutomationCoordinator.Owner.MINING_BOT);
		assertTrue(AutomationCoordinator.tryAcquire(AutomationCoordinator.Owner.ROUTE_WALKER));
	}
}
