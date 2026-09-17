/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EcarxReadingsTest {

    private static final float EPS = 0.001f;

    private static void assertNoData(float value) {
        assertTrue("expected no data, got " + value, Float.isNaN(value));
    }

    @Test
    public void temperatureRejectsSentinelsAndGlitches() {
        assertEquals(21.5f, EcarxReadings.temperature(21.5f), EPS);
        assertEquals(-40f, EcarxReadings.temperature(-40f), EPS);
        assertNoData(EcarxReadings.temperature(Float.MIN_VALUE));
        assertNoData(EcarxReadings.temperature(Float.NaN));
        assertNoData(EcarxReadings.temperature(-41f));
        assertNoData(EcarxReadings.temperature(86f));
    }

    @Test
    public void fuelPercentPassesNormalReadings() {
        assertEquals(45f, EcarxReadings.fuelPercent(45f), EPS);
        assertEquals(100f, EcarxReadings.fuelPercent(100f), EPS);
        assertEquals(0.5f, EcarxReadings.fuelPercent(0.5f), EPS);
    }

    @Test
    public void fuelPercentShowsAnOverfilledTankAsFull() {
        assertEquals(100f, EcarxReadings.fuelPercent(106f), EPS);
        assertEquals(100f, EcarxReadings.fuelPercent(120f), EPS);
        assertNoData(EcarxReadings.fuelPercent(121f));
        assertNoData(EcarxReadings.fuelPercent(255f));
    }

    @Test
    public void fuelPercentTreatsZeroAndSentinelsAsNoData() {
        assertNoData(EcarxReadings.fuelPercent(0f));
        assertNoData(EcarxReadings.fuelPercent(-1f));
        assertNoData(EcarxReadings.fuelPercent(Float.MIN_VALUE));
        assertNoData(EcarxReadings.fuelPercent(Float.NaN));
    }

    @Test
    public void rangeRejectsParkedZeroAndInvalidEncodings() {
        assertEquals(380f, EcarxReadings.rangeKm(380f), EPS);
        assertEquals(2000f, EcarxReadings.rangeKm(2000f), EPS);
        assertNoData(EcarxReadings.rangeKm(0f));
        assertNoData(EcarxReadings.rangeKm(2001f));
        assertNoData(EcarxReadings.rangeKm(Float.MIN_VALUE));
    }

    @Test
    public void batteryVoltageAcceptsEveryObservedScale() {
        assertEquals(13f, EcarxReadings.batteryVoltage(13, EcarxReadings.QUALITY_ACCURATE), EPS);
        assertEquals(13.8f, EcarxReadings.batteryVoltage(138, EcarxReadings.QUALITY_ACCURATE), EPS);
        assertEquals(13.8f, EcarxReadings.batteryVoltage(1380, EcarxReadings.QUALITY_ACCURATE), EPS);
        assertEquals(12.4f, EcarxReadings.batteryVoltage(124, EcarxReadings.QUALITY_ACCURATE), EPS);
    }

    @Test
    public void batteryVoltageRejectsImplausibleValues() {
        assertNoData(EcarxReadings.batteryVoltage(0, EcarxReadings.QUALITY_ACCURATE));
        assertNoData(EcarxReadings.batteryVoltage(5, EcarxReadings.QUALITY_ACCURATE));
        assertNoData(EcarxReadings.batteryVoltage(18, EcarxReadings.QUALITY_ACCURATE));
        assertNoData(EcarxReadings.batteryVoltage(255, EcarxReadings.QUALITY_ACCURATE));
        assertNoData(EcarxReadings.batteryVoltage(65535, EcarxReadings.QUALITY_ACCURATE));
        assertNoData(EcarxReadings.batteryVoltage(-1, EcarxReadings.QUALITY_ACCURATE));
    }

    @Test
    public void batteryVoltageRejectsAFlagThatSaysInaccurate() {
        assertNoData(EcarxReadings.batteryVoltage(138, 1));
        assertNoData(EcarxReadings.batteryVoltage(138, 2));
        assertNoData(EcarxReadings.batteryVoltage(138, 7));
    }

    @Test
    public void batteryVoltageTrustsTheValueWhenTheFlagReadsAbsent() {
        assertEquals(13.8f, EcarxReadings.batteryVoltage(138, EcarxReadings.SIGNAL_ABSENT), EPS);
    }

    @Test
    public void tankCapacityConvertsMilliliters() {
        assertEquals(62f, EcarxReadings.tankLiters(62000f), EPS);
        assertEquals(70f, EcarxReadings.tankLiters(70000f), EPS);
        assertNoData(EcarxReadings.tankLiters(0f));
        assertNoData(EcarxReadings.tankLiters(Float.MIN_VALUE));
        assertNoData(EcarxReadings.tankLiters(500000f));
    }
}
