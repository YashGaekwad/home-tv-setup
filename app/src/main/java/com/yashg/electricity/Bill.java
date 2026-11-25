package com.yashg.electricity;

/**
 * MGVCL RGP urban (RGPU) monthly bill, built the way a real bill is:
 * slab energy charges (GERC schedule from 01.06.2024) + fixed charge + fuel surcharge (FPPPA) per unit
 * - night (22:00-06:00) discount, then 15% electricity duty on the lot.
 * FPPPA changes every quarter: update FPPPA_PER_UNIT from the latest bill (Fuel Adjustment / units).
 */
final class Bill {
    static final double FIXED_PER_MONTH = 70.0;     // sanctioned load above 6 kW
    static final double FPPPA_PER_UNIT = 2.72;      // from the latest bill: Fuel Adjustment / units billed
    static final double NIGHT_DISCOUNT = 0.60;      // per night unit
    static final double NIGHT_SHARE = 0.27;         // share of units used 22:00-06:00, from the latest bill
    static final double DUTY = 0.15;

    private static final double[][] SLABS = {{50, 3.05}, {50, 3.50}, {150, 4.15}, {Double.MAX_VALUE, 5.20}};

    private Bill() {}

    static double energy(double units) {
        double left = units, total = 0;
        for (double[] s : SLABS) {
            double take = Math.min(left, s[0]);
            if (take <= 0) break;
            total += take * s[1];
            left -= take;
        }
        return total;
    }

    /** The next slab boundary above this many units (50, 100, 250), or -1 past the last one. */
    static double nextBoundary(double units) {
        double edge = 0;
        for (double[] s : SLABS) {
            if (s[0] == Double.MAX_VALUE) return -1;
            edge += s[0];
            if (units < edge) return edge;
        }
        return -1;
    }

    /** What one more unit costs at this point in the month, surcharge, night discount and duty included. */
    static double unitRate(double units) {
        double edge = 0;
        for (double[] s : SLABS) {
            edge += s[0];
            if (units < edge) return (s[1] + FPPPA_PER_UNIT - NIGHT_SHARE * NIGHT_DISCOUNT) * (1 + DUTY);
        }
        return 0;
    }

    /** Estimated bill in rupees for a month with this many units. */
    static double total(double units) {
        if (units < 0) units = 0;
        double board = energy(units) + FIXED_PER_MONTH + units * FPPPA_PER_UNIT
                - units * NIGHT_SHARE * NIGHT_DISCOUNT;
        return board * (1 + DUTY);
    }
}
