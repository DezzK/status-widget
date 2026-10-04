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

package dezz.status.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import dezz.status.widget.CarTextRenderBrick.ReadingState;

/** Where each reading's icon changes color, bound by bound — including which side owns the bound. */
public class ReadingStateTest {

    @Test
    public void fuelWarnsAtFifteenPercentAndAlarmsAtSeven() {
        assertEquals(ReadingState.OK, FuelLevelRenderBrick.stateOf(100f));
        assertEquals(ReadingState.OK, FuelLevelRenderBrick.stateOf(15.5f));
        assertEquals(ReadingState.WARNING, FuelLevelRenderBrick.stateOf(15f));
        assertEquals(ReadingState.WARNING, FuelLevelRenderBrick.stateOf(7.5f));
        assertEquals(ReadingState.CRITICAL, FuelLevelRenderBrick.stateOf(7f));
        assertEquals(ReadingState.CRITICAL, FuelLevelRenderBrick.stateOf(0f));
    }

    @Test
    public void rangeWarnsBelowAHundredKilometersAndAlarmsBelowFifty() {
        assertEquals(ReadingState.OK, FuelRangeRenderBrick.stateOf(500f));
        assertEquals(ReadingState.OK, FuelRangeRenderBrick.stateOf(100f));
        assertEquals(ReadingState.WARNING, FuelRangeRenderBrick.stateOf(99.5f));
        assertEquals(ReadingState.WARNING, FuelRangeRenderBrick.stateOf(50f));
        assertEquals(ReadingState.CRITICAL, FuelRangeRenderBrick.stateOf(49.5f));
        assertEquals(ReadingState.CRITICAL, FuelRangeRenderBrick.stateOf(0f));
    }

    @Test
    public void voltageIsGoodBetweenTwelvePointFourAndFifteen() {
        assertEquals(ReadingState.OK, BatteryVoltageRenderBrick.stateOf(12.4f));
        assertEquals(ReadingState.OK, BatteryVoltageRenderBrick.stateOf(14.2f));
        assertEquals(ReadingState.OK, BatteryVoltageRenderBrick.stateOf(15f));
        assertEquals(ReadingState.WARNING, BatteryVoltageRenderBrick.stateOf(12.3f));
        assertEquals(ReadingState.WARNING, BatteryVoltageRenderBrick.stateOf(11.8f));
        assertEquals(ReadingState.CRITICAL, BatteryVoltageRenderBrick.stateOf(11.7f));
        assertEquals(ReadingState.CRITICAL, BatteryVoltageRenderBrick.stateOf(15.1f));
    }
}
