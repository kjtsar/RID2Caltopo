package org.opendroneid.android.bluetooth;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the wire-protocol decoder helpers inside
 * {@link OpenDroneIdParser.Location}.
 *
 * Spec reference (ASTM F3411 / OpenDroneID):
 *   altitude  = raw / 2 − 1000  (metres, range −1000 to +31767.5)
 *   speed     = raw × 0.25 (mult=0) or raw × 0.75 + 255 × 0.25 (mult=1)  (m/s)
 *   direction = raw (EW=0) or raw + 180 (EW=1)  (degrees)
 *   speedVert = 0.5 × raw  (m/s, signed via two's-complement interpretation)
 */
public class OpenDroneIdParserDecoderTest {

    private static final double DELTA = 1e-9;

    // -----------------------------------------------------------------------
    // calcAltitude
    // -----------------------------------------------------------------------

    @Test
    public void calcAltitude_zero_returnsNegative1000() {
        assertEquals(-1000.0, OpenDroneIdParser.Location.calcAltitude(0), DELTA);
    }

    @Test
    public void calcAltitude_2000_returnsSeaLevel() {
        // 2000 / 2 − 1000 = 0
        assertEquals(0.0, OpenDroneIdParser.Location.calcAltitude(2000), DELTA);
    }

    @Test
    public void calcAltitude_2100_returns50m() {
        // 2100 / 2 − 1000 = 50
        assertEquals(50.0, OpenDroneIdParser.Location.calcAltitude(2100), DELTA);
    }

    @Test
    public void calcAltitude_oddValue_returnsHalfStepPrecision() {
        // value=1 → 0.5 − 1000 = −999.5  (0.5 m resolution)
        assertEquals(-999.5, OpenDroneIdParser.Location.calcAltitude(1), DELTA);
    }

    @Test
    public void calcAltitude_maxEncodedValue_isWithinSpec() {
        // max raw = 65534 (0xFFFE) → 65534/2 − 1000 = 31767
        assertEquals(31767.0, OpenDroneIdParser.Location.calcAltitude(65534), DELTA);
    }

    // -----------------------------------------------------------------------
    // calcSpeed
    // -----------------------------------------------------------------------

    @Test
    public void calcSpeed_mult0_zero_returnsZero() {
        assertEquals(0.0, OpenDroneIdParser.Location.calcSpeed(0, 0), DELTA);
    }

    @Test
    public void calcSpeed_mult0_100_returns25() {
        assertEquals(25.0, OpenDroneIdParser.Location.calcSpeed(100, 0), DELTA);
    }

    @Test
    public void calcSpeed_mult0_255_returns63_75() {
        // 255 × 0.25 = 63.75
        assertEquals(63.75, OpenDroneIdParser.Location.calcSpeed(255, 0), DELTA);
    }

    @Test
    public void calcSpeed_mult1_zero_returns63_75() {
        // 0 × 0.75 + 255 × 0.25 = 63.75
        assertEquals(63.75, OpenDroneIdParser.Location.calcSpeed(0, 1), DELTA);
    }

    @Test
    public void calcSpeed_mult1_100_returns138_75() {
        // 100 × 0.75 + 255 × 0.25 = 75 + 63.75 = 138.75
        assertEquals(138.75, OpenDroneIdParser.Location.calcSpeed(100, 1), DELTA);
    }

    @Test
    public void calcSpeed_mult1_255_returns255() {
        // 255 × 0.75 + 255 × 0.25 = 255
        assertEquals(255.0, OpenDroneIdParser.Location.calcSpeed(255, 1), DELTA);
    }

    // -----------------------------------------------------------------------
    // calcDirection
    // -----------------------------------------------------------------------

    @Test
    public void calcDirection_EW0_returnsValueUnchanged() {
        assertEquals(0.0,   OpenDroneIdParser.Location.calcDirection(0,   0), DELTA);
        assertEquals(90.0,  OpenDroneIdParser.Location.calcDirection(90,  0), DELTA);
        assertEquals(179.0, OpenDroneIdParser.Location.calcDirection(179, 0), DELTA);
    }

    @Test
    public void calcDirection_EW1_addsOneEighty() {
        assertEquals(180.0, OpenDroneIdParser.Location.calcDirection(0,   1), DELTA);
        assertEquals(270.0, OpenDroneIdParser.Location.calcDirection(90,  1), DELTA);
        assertEquals(359.0, OpenDroneIdParser.Location.calcDirection(179, 1), DELTA);
    }

    // -----------------------------------------------------------------------
    // getSpeedVert (instance — uses the private SPEED_VERTICAL_MULTIPLIER = 0.5)
    // -----------------------------------------------------------------------

    @Test
    public void getSpeedVert_zero_returnsZero() {
        OpenDroneIdParser.Location loc = new OpenDroneIdParser.Location();
        loc.speedVert = 0;
        assertEquals(0.0, loc.getSpeedVert(), DELTA);
    }

    @Test
    public void getSpeedVert_10_returns5() {
        OpenDroneIdParser.Location loc = new OpenDroneIdParser.Location();
        loc.speedVert = 10;
        assertEquals(5.0, loc.getSpeedVert(), DELTA);
    }

    @Test
    public void getSpeedVert_negative_propagatesSign() {
        OpenDroneIdParser.Location loc = new OpenDroneIdParser.Location();
        loc.speedVert = -20;
        assertEquals(-10.0, loc.getSpeedVert(), DELTA);
    }

    @Test
    public void getSpeedVert_maxByte_returns63_5() {
        // max unsigned byte value = 255 → 255 × 0.5 = 127.5
        // but speedVert is declared int, so test with a realistic max (field is populated
        // from a single signed byte in the wire protocol — range −128 to 127)
        OpenDroneIdParser.Location loc = new OpenDroneIdParser.Location();
        loc.speedVert = 127;
        assertEquals(63.5, loc.getSpeedVert(), DELTA);
    }

    // -----------------------------------------------------------------------
    // getAltitudePressure / getAltitudeGeodetic / getHeight (delegating wrappers)
    // -----------------------------------------------------------------------

    @Test
    public void getAltitudePressure_roundTrip() {
        OpenDroneIdParser.Location loc = new OpenDroneIdParser.Location();
        loc.altitudePressure = 2200; // 2200/2 − 1000 = 100 m
        assertEquals(100.0, loc.getAltitudePressure(), DELTA);
    }

    @Test
    public void getAltitudeGeodetic_roundTrip() {
        OpenDroneIdParser.Location loc = new OpenDroneIdParser.Location();
        loc.altitudeGeodetic = 2400; // 2400/2 − 1000 = 200 m
        assertEquals(200.0, loc.getAltitudeGeodetic(), DELTA);
    }

    @Test
    public void getHeight_roundTrip() {
        OpenDroneIdParser.Location loc = new OpenDroneIdParser.Location();
        loc.height = 1800; // 1800/2 − 1000 = -100 m (below takeoff point)
        assertEquals(-100.0, loc.getHeight(), DELTA);
    }

    // -----------------------------------------------------------------------
    // getDirection / getSpeedHori (instance wrappers)
    // -----------------------------------------------------------------------

    @Test
    public void getDirection_ewFlag_propagatesCorrectly() {
        OpenDroneIdParser.Location loc = new OpenDroneIdParser.Location();
        loc.Direction   = 45;
        loc.EWDirection = 0;
        assertEquals(45.0, loc.getDirection(), DELTA);

        loc.EWDirection = 1;
        assertEquals(225.0, loc.getDirection(), DELTA);
    }

    @Test
    public void getSpeedHori_multFlag_propagatesCorrectly() {
        OpenDroneIdParser.Location loc = new OpenDroneIdParser.Location();
        loc.speedHori = 200;
        loc.speedMult = 0;
        assertEquals(50.0, loc.getSpeedHori(), DELTA); // 200 × 0.25

        loc.speedMult = 1;
        assertEquals(213.75, loc.getSpeedHori(), DELTA); // 200 × 0.75 + 63.75
    }
}
