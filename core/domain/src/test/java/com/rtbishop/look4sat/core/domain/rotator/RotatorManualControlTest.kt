package com.rtbishop.look4sat.core.domain.rotator

import org.junit.Assert.*
import org.junit.Test

class RotatorManualControlTest {
    private val range = RotatorAzimuthRange.ZERO_TO_360
    @Test fun cardinalDirectionsAndZenithMatchSkyCoordinates() {
        assertEquals(RotatorPosition(0.0, 0.0), RotatorManualControl.fromPlot(0.0,-1.0,range,0.0))
        assertEquals(RotatorPosition(90.0, 45.0), RotatorManualControl.fromPlot(0.5,0.0,range,0.0))
        assertEquals(RotatorPosition(180.0, 0.0), RotatorManualControl.fromPlot(0.0,1.0,range,0.0))
        assertEquals(RotatorPosition(270.0, 0.0), RotatorManualControl.fromPlot(-1.0,0.0,range,0.0))
        assertEquals(RotatorPosition(137.0,90.0), RotatorManualControl.fromPlot(0.0,0.0,range,137.0))
    }
    @Test fun signedAndOverlapRangesKeepTheRequestedMechanicalSide() {
        assertEquals(-90.0, RotatorManualControl.fromPlot(-1.0,0.0,RotatorAzimuthRange.MINUS_180_TO_180,0.0)!!.azimuthDegrees,0.0)
        assertEquals(360.0, RotatorManualControl.fromPlot(0.0,-1.0,RotatorAzimuthRange.ZERO_TO_450,400.0)!!.azimuthDegrees,0.0)
        assertFalse(RotatorManualControl.valid(RotatorPosition(400.0,30.0),range))
        assertTrue(RotatorManualControl.valid(RotatorPosition(400.0,30.0),RotatorAzimuthRange.ZERO_TO_450))
    }
    @Test fun invalidAndOutOfPlotInputsAreRejected() {
        assertNull(RotatorManualControl.fromPlot(Double.NaN,0.0,range,0.0))
        assertNull(RotatorManualControl.fromPlot(2.0,0.0,range,0.0))
        assertFalse(RotatorManualControl.valid(RotatorPosition(0.0,Double.NaN),range))
    }
}
