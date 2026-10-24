package com.dragsim.macro.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GuiPaintMaskTest {
	@Test
	void discIsSymmetricAndAntiAliased() {
		int r = 12, size = r * 2;
		float[] a = GuiPaint.discAlpha(r);
		assertEquals(size * size, a.length);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				float v = a[y * size + x];
				assertEquals(v, a[y * size + (size - 1 - x)], 0f);
				assertEquals(v, a[(size - 1 - y) * size + x], 0f);
			}
		}
		assertEquals(1f, a[r * size + r], 0f);      // centre
		assertEquals(0f, a[0], 0f);                 // corner of the square
		float edge = a[r * size];                   // left edge, middle row: partly covered
		assertTrue(edge > 0f && edge <= 1f);
	}

	@Test
	void softMaskIsSolidInsideAndFadesOut() {
		int r = 6, sp = 10, c = r + sp, size = c * 2;
		float[] a = GuiPaint.softAlpha(r, sp);
		assertEquals(1f, a[c * size + c], 0f);
		float prev = 1f;
		for (int x = c + r; x < size; x++) {       // walking right from the core edge
			float v = a[c * size + x];
			assertTrue(v <= prev);
			prev = v;
		}
		assertEquals(0f, a[c * size + size - 1], 1e-3f);
	}

	@Test
	void ringIsHollow() {
		int outer = 10, inner = 7, size = outer * 2;
		float[] a = GuiPaint.ringAlpha(outer, inner);
		assertEquals(0f, a[outer * size + outer], 0f);           // centre
		assertTrue(a[outer * size + 1] > 0.5f);                  // on the band (x ≈ outer - 9)
		assertEquals(0f, a[0], 0f);
	}
}
