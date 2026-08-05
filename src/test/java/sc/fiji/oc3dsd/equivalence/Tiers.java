package sc.fiji.oc3dsd.equivalence;

import java.util.Locale;

/**
 * The column contract: which columns must match exactly, which may differ within
 * a stated tolerance, and which are known to differ for a stated reason.
 * <p>
 * <strong>This class is the declaration itself, not a copy of one.</strong> Every
 * value here was fixed before the code it judges was written, which is the only
 * thing that makes any of them meaningful — a tolerance chosen after seeing a
 * failure is not a tolerance, it is a way of passing. Keeping them where the
 * harness reads them, rather than in prose beside it, is what makes that rule
 * enforceable: changing one is a diff in version control, reviewable as such,
 * and there is no second copy for it to disagree with.
 * <p>
 * Tier 1 is the default. A column this class has never heard of is either new or
 * renamed, both of which a user sees, so it gets no tolerance rather than
 * inheriting a convenient one — see {@link #tierOf}.
 */
final class Tiers {

    enum Tier {
        /** Bit-identical. No exceptions, no tolerance. */
        ONE,
        /** Within a pre-declared, justified tolerance. */
        TWO,
        /** Known algorithmic difference; requires written sign-off. */
        THREE
    }

    private Tiers() {
    }

    /**
     * Tier for a statistics column.
     * <p>
     * Unknown columns are Tier 1 on purpose. A column this method has never
     * heard of is either new or renamed, and both are user-visible changes that
     * should stop the harness rather than slip through under a default
     * tolerance.
     */
    static Tier tierOf(String column) {
        String name = column == null ? "" : column.trim();
        String lower = name.toLowerCase(Locale.ROOT);

        // Tier 3 — the bounded Feret estimate. Resolved explicitly, either by
        // accepting the difference or by matching it, with a CHANGELOG entry;
        // never left to be discovered at release time.
        if (lower.startsWith("morph_feret")) return Tier.THREE;

        // Tier 2 — surface and everything derived from it.
        if (lower.startsWith("surface (")) return Tier.TWO;
        if ("morph_sphericity".equals(lower)) return Tier.TWO;
        if ("morph_compactness".equals(lower)) return Tier.TWO;
        if ("morph_elongation".equals(lower)) return Tier.TWO;

        // Everything else, including every integer count, the bounding box, the
        // centroids, the intensity statistics and the detector diagnostics.
        return Tier.ONE;
    }

    /**
     * Declared relative tolerance for a column. Tier 1 columns return exactly
     * zero, and that is asserted rather than assumed.
     * <p>
     * <strong>Tier 2 is zero in this repository, and that is not an oversight.</strong>
     * In {@code 3D Objects Counter+} the surface columns are expected to move,
     * because the classic and Lindblad-corrected definitions genuinely differ.
     * Here they are not: this plugin already implements the Lindblad (2005)
     * corrected surface in {@code ij} alone, which its {@code pom.xml} states at
     * length and {@code LabelMeasurements} documents as reproducing mcib3d's
     * {@code Object3DVoxels.computeContours()} exactly. Adopting core's
     * accumulator must therefore reproduce it, and a surface change in <em>this</em>
     * repo is a regression to diagnose, not a migration delta to absorb.
     * See SD stage 03: "If one appears, stop and diagnose; do not tolerance it."
     */
    static double relativeToleranceFor(String column) {
        Tier tier = tierOf(column);
        if (tier == Tier.ONE) return 0.0;

        String lower = column == null ? "" : column.trim().toLowerCase(Locale.ROOT);

        // Elongation comes from the eigenvalues of the moment tensor. Declared
        // at <= 1e-9 relative, because a different but algebraically
        // equivalent eigenvalue solver can differ in the last bits without any
        // difference in meaning. This is the one tolerance in this repo that is
        // genuinely expected to be exercised.
        if ("morph_elongation".equals(lower)) return 1.0e-9;

        // Surface, sphericity and compactness: same definition on both sides, so
        // no tolerance. See the class note above.
        if (tier == Tier.TWO) return 0.0;

        // Feret is Tier 3 and unresolved. Until the accept-or-match decision is
        // signed off, any movement is a finding, so it is compared exactly. A
        // tolerance here before the decision would pre-empt it.
        return 0.0;
    }

    /**
     * True when two values of a column agree under the declared contract.
     * NaN is treated as a value in its own right: NaN on one side and a number
     * on the other is a difference, and NaN on both is agreement, because
     * "this could not be measured" is itself a user-visible result.
     */
    static boolean agree(String column, double golden, double candidate) {
        if (Double.isNaN(golden) || Double.isNaN(candidate)) {
            return Double.isNaN(golden) && Double.isNaN(candidate);
        }
        if (golden == candidate) return true;
        double tolerance = relativeToleranceFor(column);
        if (tolerance <= 0.0) return false;
        if (Double.isInfinite(golden) || Double.isInfinite(candidate)) return false;
        double scale = Math.max(Math.abs(golden), Math.abs(candidate));
        if (scale == 0.0) return true;
        return Math.abs(golden - candidate) / scale <= tolerance;
    }

    /** Relative difference used for the Tier 2 delta table. */
    static double relativeDifference(double golden, double candidate) {
        if (Double.isNaN(golden) && Double.isNaN(candidate)) return 0.0;
        if (Double.isNaN(golden) || Double.isNaN(candidate)) return Double.POSITIVE_INFINITY;
        if (golden == candidate) return 0.0;
        double scale = Math.max(Math.abs(golden), Math.abs(candidate));
        if (scale == 0.0) return 0.0;
        return Math.abs(golden - candidate) / scale;
    }
}
